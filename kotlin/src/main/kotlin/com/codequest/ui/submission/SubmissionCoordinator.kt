package com.codequest.ui.submission

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionResult
import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.codequest.spi.AuthCapable
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmitRequest
import com.codequest.ui.challenge.ChallengeToolWindowNavigator
import com.codequest.ui.jcef.CQBridge
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

    @Volatile
    private var submissionsBridge: CQBridge? = null

    /** submissions 工具窗口创建内容时挂接桥(随内容销毁 [detachSubmissionsBridge])。 */
    fun attachSubmissionsBridge(bridge: CQBridge) {
        submissionsBridge = bridge
    }

    fun detachSubmissionsBridge(bridge: CQBridge) {
        if (submissionsBridge === bridge) submissionsBridge = null
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
            broadcastUpdated(submission, challengeName)
            // WP51:提交记录并入挑战工具窗——导航改为「激活挑战窗 + 选中提交记录 tab」,
            // challengeId 定向过滤透传给页面 URL(原 SubmissionsToolWindowFactory 已删除)
            project
                .getService(ChallengeToolWindowNavigator::class.java)
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

    /** 提交落库后向 submissions 页推 `submission:updated`(桥推送,页面无需轮询)。 */
    private fun broadcastUpdated(
        submission: Submission,
        challengeName: String,
    ) {
        val bridge = submissionsBridge ?: return
        runCatching {
            val payload =
                JsonObject(
                    mapOf(
                        "challengeId" to JsonPrimitive(submission.challengeId.value),
                        "submissionId" to JsonPrimitive(submission.id.value),
                        "challengeName" to JsonPrimitive(challengeName),
                        "verdict" to Json.encodeToJsonElement(SubmissionResult.serializer(), submission.verdict),
                    ),
                ).toString()
            bridge.postMessage(TOPIC_SUBMISSION_UPDATED, payload)
        }.onFailure { error -> logger.warning("Failed to push $TOPIC_SUBMISSION_UPDATED: $error") }
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

    companion object {
        /** 提交完成事件 topic(Kotlin → JS,页面收到后重拉 /api/submissions)。 */
        const val TOPIC_SUBMISSION_UPDATED = "submission:updated"
    }
}
