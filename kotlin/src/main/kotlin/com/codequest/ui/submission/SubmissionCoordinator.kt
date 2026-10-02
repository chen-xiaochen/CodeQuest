package com.codequest.ui.submission

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.Problem
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionResult
import com.codequest.core.review.ReviewIntervalsSource
import com.codequest.core.review.ReviewService
import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.codequest.spi.AuthCapable
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmitRequest
import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.toolwindow.ProblemViewTabNavigator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * 提交协调器(WP21,项目级服务):编辑器提交 action 的后端。
 *
 * 时序(全部网络/落库在 pooled 线程,EDT 只做通知与对话框):
 * 定位 challenge 工作区 → 区域标记提取 → 平台 SPI submit → watchSubmission 收集
 * → 终态 Submission 落库 → IntelliJ 通知(AC 绿/失败红)→ CQBridge
 * `submission:updated` 推送 submissions 页刷新。
 *
 * - AuthExpired → [LeetCodeCookieDialog] 粘贴 cookie → ensureLoggedIn → 自动重试一次;
 * - 同一文件提交中防重复触发([SubmissionGuard]);
 * - 持有 submissions 工具窗口桥引用,供提交完成事件桥推送(页面不轮询)。
 */
@Service(Level.PROJECT)
class SubmissionCoordinator(
    private val project: Project,
) {
    private val logger = Logger.getLogger(SubmissionCoordinator::class.java.name)

    private val guard = SubmissionGuard()

    /** WP65:多播桥注册(原单槽兼容改造)——submissions 页刷新与题面页计时器订阅同一 topic。 */
    private val submissionsBridges = SubmissionsBridgeRegistry()

    /** submissions/题面工具窗口创建内容时挂接桥(随内容销毁 [detachSubmissionsBridge])。 */
    fun attachSubmissionsBridge(bridge: CQBridge) {
        submissionsBridges.attach(bridge) { topic, payload -> bridge.postMessage(topic, payload) }
    }

    fun detachSubmissionsBridge(bridge: CQBridge) {
        submissionsBridges.detach(bridge)
    }

    /**
     * 提交入口:[file] 当前编辑器内容为 [text](含未保存修改)。
     * EDT 安全:只做 guard 判定与后台调度,重活全部下沉 pooled 线程。
     */
    fun submitFile(
        file: VirtualFile,
        text: String,
    ) {
        val path =
            runCatching { file.toNioPath() }.getOrNull()
                ?: run {
                    SubmissionNotifications.notifyError(project, PluginBundle.message("submission.error.notChallengeFile"))
                    return
                }
        if (!guard.begin(path.toString())) {
            SubmissionNotifications.notifyError(project, PluginBundle.message("submission.error.alreadyInProgress"))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread<Any> {
            try {
                runBlocking { runSubmission(path, text) }
            } catch (error: Exception) {
                logger.severe("Submission failed unexpectedly: $error")
                notifySubmitFailure(error)
            } finally {
                guard.end(path.toString())
            }
        }
    }

    // ------------------------------------------------------------------
    // 提交主流程(pooled 线程)
    // ------------------------------------------------------------------

    private suspend fun runSubmission(
        path: Path,
        text: String,
    ) {
        val target = resolveTarget(path) ?: return notifyError("submission.error.notChallengeFile")

        val code =
            when (val extraction = SubmitRegionExtractor.extract(text, target.language.language)) {
                is RegionExtraction.Extracted -> {
                    extraction.code
                }

                RegionExtraction.MissingBeginMarker, RegionExtraction.MissingEndMarker -> {
                    val (begin, end) = ChallengeTemplates.regionMarkers(target.language.language)
                    return notifyError("submission.error.regionMarkersMissing", begin, end)
                }
            }
        if (code.isBlank()) return notifyError("submission.error.emptyRegion")

        val platform =
            SubmissionPlatforms.resolve(project, target.problem.platformId)
                ?: return notifyError("submission.error.platformNotSubmittable", target.problem.platformId.value)
        val capable =
            platform as? SubmissionCapable
                ?: return notifyError("submission.error.platformNotSubmittable", target.problem.platformId.value)
        val languageInfo =
            platform.info.supportedLanguages.firstOrNull { it.workspaceLanguageId == target.language.language }
                ?: return notifyError(
                    "submission.error.languageNotSubmittable",
                    target.language.language,
                    platform.info.displayName,
                )

        val challengeName = target.challenge.name
        val progress = SubmissionNotifications.notifySubmitting(project, challengeName)
        val flow =
            SubmissionFlow(
                submissionRepository = databaseManager().submissionRepository,
            )
        try {
            val submission =
                flow.submit(
                    platform = platform,
                    capable = capable,
                    request =
                        SubmitRequest(
                            problemRemoteId = target.problem.remoteId,
                            languageId = languageInfo.id,
                            code = code,
                        ),
                    challengeId = target.challenge.id,
                    languageLabel = languageInfo.displayName,
                    hooks = coordinatorHooks(platform, challengeName, progress),
                )
            SubmissionNotifications.notifyCompleted(project, challengeName, submission.verdict)
            broadcastUpdated(submission, challengeName, target.problem)
            // 1.7.0 F3:AC 即排/完成间隔复习(平台无关;失败仅告警,不影响提交流程)
            if (submission.verdict.isSuccessful) {
                runCatching { reviewService().onAccepted(target.problem.id) }
                    .onFailure { error -> logger.warning("Review scheduling failed: $error") }
            }
            // 1.5.0:底部挑战工具窗删除,提交记录并入右窗「题面」工具窗三 tab——
            // 导航改为「激活右窗 + 选中提交记录 tab」,challengeId 定向过滤透传给页面 URL
            project
                .getService(ProblemViewTabNavigator::class.java)
                .showSubmissions(submission.challengeId.value)
        } catch (error: AuthExpiredException) {
            SubmissionNotifications.notifyError(
                project,
                PluginBundle.message("submission.error.authExpired", error.message.orEmpty()),
            )
        } catch (error: Exception) {
            notifySubmitFailure(error)
        } finally {
            progress.expire()
        }
    }

    /** AuthExpired → ensureLoggedIn(内部弹 cookie 对话框)→ 由 SubmissionFlow 重试一次。 */
    private fun coordinatorHooks(
        platform: com.codequest.spi.OJPlatform,
        challengeName: String,
        progress: com.intellij.notification.Notification,
    ) = object : SubmissionFlow.Hooks {
        override fun onState(result: SubmissionResult) {
            if (result == SubmissionResult.Judging) {
                progress.setContent(PluginBundle.message("submission.progress.judging", challengeName))
            }
        }

        override suspend fun relogin(): Boolean {
            val auth = platform as? AuthCapable ?: return false
            return try {
                auth.ensureLoggedIn()
                true
            } catch (error: AuthExpiredException) {
                logger.info("Re-login refused or failed: ${error.message}")
                false
            }
        }
    }

    // ------------------------------------------------------------------
    // 工作区定位
    // ------------------------------------------------------------------

    /** 挑战定位(WP55 起委托 [ChallengeEditorGate]:TTL 快照缓存,与浮动工具栏共单一定位实现)。 */
    private suspend fun resolveTarget(path: Path): ChallengeTarget? = project.getService(ChallengeEditorGate::class.java).targetFor(path)

    // ------------------------------------------------------------------
    // 前端刷新推送
    // ------------------------------------------------------------------

    /** 提交落库后向已挂接桥(submissions 页刷新/题面页计时器)多播 `submission:updated`。 */
    private fun broadcastUpdated(
        submission: Submission,
        challengeName: String,
        problem: Problem,
    ) {
        if (submissionsBridges.isEmpty) return
        val payload = buildSubmissionUpdatedPayload(submission, challengeName, problem)
        submissionsBridges.broadcast(TOPIC_SUBMISSION_UPDATED, payload) { error ->
            logger.warning("Failed to push $TOPIC_SUBMISSION_UPDATED: $error")
        }
    }

    // ------------------------------------------------------------------
    // 支撑
    // ------------------------------------------------------------------

    private fun notifyError(
        key: String,
        vararg params: Any,
    ) {
        SubmissionNotifications.notifyError(project, PluginBundle.message(key, *params))
    }

    private fun notifySubmitFailure(error: Exception) {
        SubmissionNotifications.notifyError(
            project,
            PluginBundle.message("submission.error.submitFailed", error.message ?: error.toString()),
        )
    }

    private fun databaseManager(): DatabaseManager = project.getService(DatabaseManager::class.java)

    /** 复习调度服务(无状态,按需构造;仓库随切库指向新句柄,与 databaseManager 同语义)。 */
    private fun reviewService(): ReviewService {
        val manager = databaseManager()
        return ReviewService(
            store = manager.reviewRepository,
            intervalsSource =
                ReviewIntervalsSource { manager.settingsKvRepository.get(ReviewService.SETTINGS_KEY) },
        )
    }

    companion object {
        /** 提交完成事件 topic(Kotlin → JS,页面收到后重拉 /api/submissions;WP65 起题面页计时器亦订阅)。 */
        const val TOPIC_SUBMISSION_UPDATED = "submission:updated"
    }
}

/**
 * `submission:updated` payload(WP65 增补 `platformId`/`problemRemoteId`,
 * 题面页计时器按 `(platformId, problemRemoteId)` 匹配当前题;既有字段不动)。
 */
internal fun buildSubmissionUpdatedPayload(
    submission: Submission,
    challengeName: String,
    problem: Problem,
): String =
    JsonObject(
        mapOf(
            "challengeId" to JsonPrimitive(submission.challengeId.value),
            "submissionId" to JsonPrimitive(submission.id.value),
            "challengeName" to JsonPrimitive(challengeName),
            "platformId" to JsonPrimitive(problem.platformId.value),
            "problemRemoteId" to JsonPrimitive(problem.remoteId),
            "verdict" to Json.encodeToJsonElement(SubmissionResult.serializer(), submission.verdict),
        ),
    ).toString()

/**
 * 桥多播注册表(WP65):[SubmissionCoordinator] 原 `submissionsBridge` 单槽改多播,
 * submissions 页与题面页桥各自订阅同一 topic。attach 幂等(同桥重复挂接不重复收),
 * detach 精确摘除;broadcast 遍历发送,单桥失败仅告警不中断其余桥——单注册时
 * 行为与原单槽逐字节一致(同一 payload 字符串、同一 topic、一次 postMessage)。
 */
internal class SubmissionsBridgeRegistry {
    private class Slot(
        val key: Any,
        val send: (String, String) -> Unit,
    )

    private val slots = CopyOnWriteArrayList<Slot>()

    val isEmpty: Boolean
        get() = slots.isEmpty()

    fun attach(
        key: Any,
        send: (String, String) -> Unit,
    ) {
        if (slots.none { it.key === key }) slots.add(Slot(key, send))
    }

    fun detach(key: Any) {
        slots.removeAll { it.key === key }
    }

    fun broadcast(
        topic: String,
        payload: String,
        onFailure: (Throwable) -> Unit = {},
    ) {
        for (slot in slots.toList()) {
            runCatching { slot.send(topic, payload) }.onFailure { onFailure(it) }
        }
    }
}
