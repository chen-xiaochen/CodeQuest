package com.codequest.infra.messaging

import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import java.util.logging.Logger

/**
 * 项目级服务:持有 [ResourceHttpServer] 生命周期(WP11)。
 *
 * 项目打开时由 [CqHttpServerStartupActivity] 触发实例化(启动服务,端口写入 IDE 日志),
 * 项目关闭时随服务销毁调用 [dispose] 停止服务。
 * [ResourceHttpServer.start]/[stop] 本身幂等,重复触发安全。
 */
@Service(Level.PROJECT)
class CodeQuestHttpServerService(
    val project: Project,
) : Disposable {
    private val logger = Logger.getLogger(CodeQuestHttpServerService::class.java.name)

    /** 1.7.0 F1:难度评分服务(内存快照;仓库按调用解析,切库后手动刷新即生效)。 */
    private val ratingService =
        com.codequest.infra.rating.RatingService(
            ratings = { project.getService(DatabaseManager::class.java).ratingRepository },
        )

    val httpServer: ResourceHttpServer =
        ResourceHttpServer(
            // WP20:题目数据端点——平台经 SPI 注册表,存储按请求解析(数据库切换即时生效);
            // WP37:列表项附刷题状态(本地聚合,store 按请求解析);
            // 1.7.0 F1:列表行附难度评分(RatingService 内存 Map 装饰)
            problemApi =
                ProblemApi(
                    store = { project.getService(DatabaseManager::class.java).problemStore },
                    statuses = { project.getService(DatabaseManager::class.java).problemStatusStore },
                    ratings = ratingService,
                ),
            // WP21:提交记录端点——本地库分页查询,同样按请求解析(切库即时生效)
            submissionsApi =
                SubmissionsApi(
                    submissions = { project.getService(DatabaseManager::class.java).submissionRepository },
                    challenges = { project.getService(DatabaseManager::class.java).challengeRepository },
                ),
            // 1.5.0:测试用例端点——右窗「测试用例」页 CRUD(仓库按请求解析,切库即时生效)
            testCasesApi =
                TestCasesApi(
                    testCases = { project.getService(DatabaseManager::class.java).testCaseRepository },
                    challenges = { project.getService(DatabaseManager::class.java).challengeRepository },
                ),
            // WP23:竞赛端点——ContestCapable 平台拉取 + 提醒落库(仓库按请求解析,切库即时生效)
            contestApi =
                ContestApi(
                    reminders = { project.getService(DatabaseManager::class.java).contestReminderRepository },
                ),
            // WP26:题解引用端点——SolutionViewCapable 平台拉取(纯透传,不落库)
            solutionsApi = SolutionsApi(),
            // WP38:账号状态端点——实例源经 EP 反转注入(ui 提交链路缓存实例,
            // 与登录桥/提交流程共享同一登录态),infra 不反向依赖 ui
            authApi =
                AuthApi(
                    platforms = { AuthPlatformSources.forProject(project) },
                ),
            // WP29:统计端点——submission 表经 StatisticsService 聚合(仓库按请求解析,切库即时生效);
            // 1.7.0 F2:合并 remote_submission 站内历史(并集/本地优先/remoteSyncedCount)
            statsApi =
                StatisticsApi(
                    submissions = { project.getService(DatabaseManager::class.java).submissionRepository },
                    remoteSubmissions = { project.getService(DatabaseManager::class.java).remoteSubmissionRepository },
                ),
            // WP30:检索端点——本地库三源聚合 + 标签/平台过滤(store 按请求解析,切库即时生效);
            // WP37:列表项附刷题状态(本地聚合,store 按请求解析)
            searchApi =
                SearchApi(
                    store = { project.getService(DatabaseManager::class.java).searchStore },
                    statuses = { project.getService(DatabaseManager::class.java).problemStatusStore },
                ),
            // WP39:题面自定义 CSS——按请求现读项目设置(settings 在 infra,同层引用),
            // 改设置后无需重启,题面页下次加载拉到新值
            customCss = { CodeQuestSettings.getInstance(project).state.customCss },
            // 1.7.0 F1:评分端点(状态/在线刷新/用户竞赛分;用户竞赛分经 SPI 注册表
            // 解析 UserContestCapable 平台,与其他平台族端点同法)
            ratingApi =
                RatingApi(
                    ratings = ratingService,
                ),
            // 1.7.0 F2:站内提交历史端点——同步经 SPI 注册表解析 SubmissionHistoryCapable
            // 平台,仓库按请求解析(切库即时生效);同步跑在本服务 pooled 工作线程
            submissionHistoryApi =
                SubmissionHistoryApi(
                    syncService =
                        com.codequest.infra.submission.SubmissionHistorySyncService(
                            platforms = com.codequest.spi.OJPlatformRegistry::all,
                            remoteSubmissions = {
                                project.getService(DatabaseManager::class.java).remoteSubmissionRepository
                            },
                        ),
                    remoteSubmissions = { project.getService(DatabaseManager::class.java).remoteSubmissionRepository },
                ),
        )

    /** 服务基地址;未启动时为 null。 */
    val baseUrl: String?
        get() = httpServer.listeningPort?.let { port -> "http://$HOST:$port" }

    init {
        val port = httpServer.start()
        logger.info("CodeQuest resource server started on http://$HOST:$port (static root: webview/)")
    }

    override fun dispose() {
        httpServer.stop()
        logger.info("CodeQuest resource server stopped")
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}

/**
 * 项目启动后活动:主动实例化 [CodeQuestHttpServerService],保证 HTTP 服务随项目启动
 * (projectService 默认懒加载,不 touch 一次则不会启动),并展示 CodeQuest 主工具窗口。
 */
class CqHttpServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(CodeQuestHttpServerService::class.java)
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow(MAIN_TOOL_WINDOW_ID)?.show(null)
        }
    }

    private companion object {
        const val MAIN_TOOL_WINDOW_ID = "CodeQuest"
    }
}
