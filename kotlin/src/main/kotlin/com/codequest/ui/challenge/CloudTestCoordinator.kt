package com.codequest.ui.challenge

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.codequest.spi.AuthCapable
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.TestEvaluationCapable
import com.codequest.spi.TestRunRequest
import com.codequest.spi.TestRunResult
import com.codequest.spi.TestRunStatus
import com.codequest.ui.submission.ChallengeEditorGate
import com.codequest.ui.submission.ChallengeTarget
import com.codequest.ui.submission.RegionExtraction
import com.codequest.ui.submission.SubmissionGuard
import com.codequest.ui.submission.SubmissionNotifications
import com.codequest.ui.submission.SubmissionPlatforms
import com.codequest.ui.submission.SubmitRegionExtractor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.logging.Logger

/**
 * 云端测试协调器(WP55,项目级服务):「提交测试」浮动键的后端。
 *
 * 时序(EDT 只做保存/读文本/门禁,网络与查库全部在 pooled 线程):
 * 定位挑战 → 区域标记提取(缺标记文案同提交流程)→ 拉工作区用例拼 dataInput →
 * questionId 经平台 fetchProblemDetail 换取 → TestEvaluationCapable.watchTestRun
 * 收集至终态 → TestRunResultDialog 展示(逐用例输入按位对齐明细表)。
 *
 * - 同一文件测试中防重复触发([SubmissionGuard],action.update 同步置灰);
 * - 平台无 TestEvaluationCapable / 未登录为发起前置分支(浮动键隐藏/置灰在
 *   action.update 处理,此处兜底同文案);
 * - 测试不产生正式提交记录,不落库、不推送前端。
 */
@Service(Level.PROJECT)
class CloudTestCoordinator(
    private val project: Project,
) {
    private val logger = Logger.getLogger(CloudTestCoordinator::class.java.name)

    private val guard = SubmissionGuard()

    /** 该文件是否仍有云端测试进行中(action.update 置灰查询)。 */
    fun isRunning(path: Path): Boolean = guard.isInFlight(path.toString())

    /**
     * 测试入口:[file] 当前编辑器内容为 [text](调用方已 saveDocument)。
     * EDT 安全:只做 guard 判定与后台调度。
     */
    fun runTest(
        file: VirtualFile,
        text: String,
    ) {
        val path =
            runCatching { file.toNioPath() }.getOrNull()
                ?: return notify(TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_CHALLENGE_FILE))
        if (!guard.begin(path.toString())) {
            notify("submission.error.alreadyInProgress")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread<Any> {
            try {
                runBlocking { runTestFlow(path, text) }
            } catch (error: Exception) {
                logger.severe("Cloud test failed unexpectedly: $error")
                notify("testRun.error.failed", error.message ?: error.toString())
            } finally {
                guard.end(path.toString())
            }
        }
    }

    // ------------------------------------------------------------------
    // 测试主流程(pooled 线程)
    // ------------------------------------------------------------------

    private suspend fun runTestFlow(
        path: Path,
        text: String,
    ) {
        val gate = project.getService(ChallengeEditorGate::class.java)
        val target =
            gate.targetFor(path)
                ?: return notify(TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_CHALLENGE_FILE))

        val code =
            extractCode(text, target.language.language)
                ?: return // 缺区域标记:文案已在 extractCode 内通知(同提交流程)

        val request =
            buildRequest(target, code)
                ?: return // 无用例/平台不支持/未登录/语言或 questionId 缺失:文案已在 buildRequest 内通知

        val progress = SubmissionNotifications.notifyTesting(project, target.challenge.name)
        try {
            val finished = watchTerminalResult(request.capable, request.request)
            ApplicationManager.getApplication().invokeLater {
                TestRunResultDialog(project, finished, request.inputs).show()
            }
        } catch (error: AuthExpiredException) {
            notify("submission.error.authExpired", error.message.orEmpty())
        } finally {
            progress.expire()
        }
    }

    /** 区域标记提取;缺标记时通知(文案同提交流程)并返回 null。 */
    private fun extractCode(
        text: String,
        workspaceLanguageId: String,
    ): String? =
        when (val extraction = SubmitRegionExtractor.extract(text, workspaceLanguageId)) {
            is RegionExtraction.Extracted -> {
                extraction.code
            }

            RegionExtraction.MissingBeginMarker, RegionExtraction.MissingEndMarker -> {
                val (begin, end) = ChallengeTemplates.regionMarkers(workspaceLanguageId)
                notify(TestRunPrep.messageKey(TestRunPrep.Refusal.MISSING_REGION_MARKERS), begin, end)
                null
            }
        }

    /** 发起前的就绪产物(逐用例输入按位对齐结果明细表)。 */
    private data class PreparedTest(
        val capable: TestEvaluationCapable,
        val request: TestRunRequest,
        val inputs: List<String>,
    )

    /** 拉用例 → 解析平台与语言 → 换取 questionId → 拼请求;任一前置不满足时通知并返回 null。 */
    private suspend fun buildRequest(
        target: ChallengeTarget,
        code: String,
    ): PreparedTest? {
        val manager = project.getService(DatabaseManager::class.java)
        val cases = manager.testCaseRepository.findByChallenge(target.challenge.id)
        val platform = SubmissionPlatforms.resolve(project, target.problem.platformId)
        when {
            cases.isEmpty() -> {
                notify(TestRunPrep.messageKey(TestRunPrep.Refusal.NO_TEST_CASES))
                return null
            }

            platform == null || platform !is TestEvaluationCapable -> {
                notify(
                    TestRunPrep.messageKey(TestRunPrep.Refusal.PLATFORM_NOT_TESTABLE),
                    platform?.info?.displayName ?: target.problem.platformId.value,
                )
                return null
            }
        }
        val capable = platform as TestEvaluationCapable
        if (platform is AuthCapable && platform.currentSession() == null) {
            notify(TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_LOGGED_IN), platform.info.displayName)
            return null
        }
        val languageInfo = platform.info.supportedLanguages.firstOrNull { it.workspaceLanguageId == target.language.language }
        if (languageInfo == null) {
            notify("submission.error.languageNotSubmittable", target.language.language, platform.info.displayName)
            return null
        }

        // interpret 请求体需要平台侧数字主档 id(remoteId 是 titleSlug,经详情换取)
        val questionId =
            (platform as? ProblemQueryCapable)
                ?.fetchProblemDetail(target.problem.remoteId)
                ?.questionId
                ?.takeIf { it.isNotBlank() }
        if (questionId == null) {
            notify(TestRunPrep.messageKey(TestRunPrep.Refusal.QUESTION_ID_MISSING))
            return null
        }

        return PreparedTest(
            capable = capable,
            request =
                TestRunRequest(
                    problemRemoteId = target.problem.remoteId,
                    questionId = questionId,
                    languageId = languageInfo.id,
                    code = code,
                    dataInput = TestRunPrep.dataInput(cases),
                ),
            inputs = cases.map { it.input },
        )
    }

    /** 收集 watchTestRun 至终态;流正常完成却无终态视为协议异常。 */
    private suspend fun watchTerminalResult(
        capable: TestEvaluationCapable,
        request: TestRunRequest,
    ): TestRunResult {
        var finished: TestRunResult? = null
        capable.watchTestRun(request).collect { status ->
            if (status is TestRunStatus.Finished) finished = status.result
        }
        return checkNotNull(finished) { "watchTestRun 流完成但未发射终态: ${request.problemRemoteId}" }
    }

    private fun notify(
        key: String,
        vararg params: Any,
    ) {
        SubmissionNotifications.notifyError(project, PluginBundle.message(key, *params))
    }
}
