package com.codequest.infra.messaging

import com.codequest.infra.db.DatabaseManager
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

    val httpServer: ResourceHttpServer =
        ResourceHttpServer(
            // WP20:题目数据端点——平台经 SPI 注册表,存储按请求解析(数据库切换即时生效)
            problemApi =
                ProblemApi(
                    store = { project.getService(DatabaseManager::class.java).problemStore },
                ),
            // WP21:提交记录端点——本地库分页查询,同样按请求解析(切库即时生效)
            submissionsApi =
                SubmissionsApi(
                    submissions = { project.getService(DatabaseManager::class.java).submissionRepository },
                    challenges = { project.getService(DatabaseManager::class.java).challengeRepository },
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
