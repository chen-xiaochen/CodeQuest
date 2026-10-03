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
import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.submission.ChallengeEditorGate
import com.codequest.ui.submission.ChallengeTarget
import com.codequest.ui.submission.RegionExtraction
import com.codequest.ui.submission.SubmissionGuard
import com.codequest.ui.submission.SubmissionNotifications
import com.codequest.ui.submission.SubmissionPlatforms
import com.codequest.ui.submission.SubmitRegionExtractor
import com.codequest.ui.toolwindow.ProblemViewTabNavigator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.logging.Logger

/**
 * 云端测试协调器(WP55,项目级服务):「提交测试」与「全示例测试」(WP74 浮动栏
 * 第四键)两枚云测键的后端,同壳不同数据源(工作区用例 / 题面 exampleTestcases)。
 *
 * 时序(EDT 只做保存/读文本/门禁,网络与查库全部在 pooled 线程):
 * 定位挑战 → 区域标记提取(缺标记文案同提交流程)→ 拼数据源 dataInput →
 * questionId 经平台 fetchProblemDetail 换取(全示例路径与 exampleTestcases 同次)→
 * TestEvaluationCapable.watchTestRun 收集至终态 → 右窗「测试用例」tab 展示
 * (1.5.0 起对话框删除):经 [ProblemViewTabNavigator] 激活右窗并选中测试用例
 * tab,结果 JSON 经桥 `testrun:result` 推送页面([TestRunResultState.resultPayload]
 * 组装,全示例携带 mode=examples 供页面标题区分);tab 首建/页面重载的时序竞态由
 * `testrun:latest` 请求-应答补齐(缓存最近结果)。
 *
 * - 同一文件测试中防重复触发([SubmissionGuard],action.update 同步置灰;
 *   两键共用同文件 guard,任一云测进行中另一键亦置灰);
 * - 平台无 TestEvaluationCapable / 未登录为发起前置分支(浮动键隐藏/置灰在
 *   action.update 处理,此处兜底同文案);
 * - 测试不产生正式提交记录,不落库。
 */
@Service(Level.PROJECT)
class CloudTestCoordinator(
    private val project: Project,
) {
    private val logger = Logger.getLogger(CloudTestCoordinator::class.java.name)

    private val guard = SubmissionGuard()

    /** 测试用例 tab 的桥引用(随 tab 内容创建/销毁挂接)。 */
    @Volatile
    private var bridge: CQBridge? = null

    /** 最近一次测试结果 (challengeId, payload);供页面加载后 `testrun:latest` 取回。 */
    @Volatile
    private var latestResult: Pair<String, String>? = null

    /** 测试用例 tab 创建内容时挂接桥(随内容销毁 [detachBridge])。 */
    fun attachBridge(attached: CQBridge) {
        bridge = attached
        // 页面加载完成后按 challengeId 取最近结果(桥就绪前的推送不致丢失)
        attached.on(TOPIC_TESTRUN_LATEST) { payload ->
            val challengeId = parseLatestRequest(payload) ?: return@on
            val latest = latestResult
            if (latest != null && latest.first == challengeId) {
                attached.postMessage(TOPIC_TESTRUN_RESULT, latest.second)
            }
        }
    }

    fun detachBridge(detached: CQBridge) {
        if (bridge === detached) bridge = null
    }

    /** 该文件是否仍有云端测试进行中(action.update 置灰查询)。 */
    fun isRunning(path: Path): Boolean = guard.isInFlight(path.toString())

    /**
     * 测试入口:[file] 当前编辑器内容为 [text](调用方已 saveDocument)。
     * EDT 安全:只做 guard 判定与后台调度。
     */
    fun runTest(
        file: VirtualFile,
        text: String,
    ) = start(file, text, TestSource.WORKSPACE_CASES)

    /**
     * 全示例测试入口(WP74,浮动栏第四键):与「提交测试」同壳,仅数据源不同——
     * dataInput 取题面 exampleTestcases(与 questionId 同次 fetchProblemDetail 换取),
     * 结果经同一右窗「测试用例」tab 展示(payload 携带 mode=examples 区分)。
     */
    fun runExamplesTest(
        file: VirtualFile,
        text: String,
    ) = start(file, text, TestSource.PROBLEM_EXAMPLES)

    /** 云测数据源:工作区用例(「提交测试」)或题面示例(「全示例测试」,WP74)。 */
    private enum class TestSource {
        WORKSPACE_CASES,
        PROBLEM_EXAMPLES,
    }

    private fun start(
        file: VirtualFile,
        text: String,
        source: TestSource,
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
                runBlocking { runTestFlow(path, text, source) }
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
        source: TestSource,
    ) {
        val gate = project.getService(ChallengeEditorGate::class.java)
        val target =
            gate.targetFor(path)
                ?: return notify(TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_CHALLENGE_FILE))

        val code =
            extractCode(text, target.language.language)
                ?: return // 缺区域标记:文案已在 extractCode 内通知(同提交流程)

        val request =
            buildRequest(target, code, source)
                ?: return // 无用例/无示例/平台不支持/未登录/语言或 questionId 缺失:文案已在 buildRequest 内通知

        val progress = SubmissionNotifications.notifyTesting(project, target.challenge.name)
        try {
            val finished = watchTerminalResult(request.capable, request.request)
            ApplicationManager.getApplication().invokeLater {
                publishResult(target.challenge.id.value, finished, request.inputs, request.mode)
            }
        } catch (error: AuthExpiredException) {
            notify("submission.error.authExpired", error.message.orEmpty())
        } finally {
            progress.expire()
        }
    }

    /**
     * 结果展示(EDT):激活右窗测试用例 tab + 桥推送 `testrun:result`。
     * 页面尚未就绪(首次建 tab/加载中)时推送会被页面错过——缓存最近结果,
     * 页面加载完成后经 `testrun:latest` 主动取回。
     */
    private fun publishResult(
        challengeId: String,
        result: TestRunResult,
        inputs: List<String>,
        mode: String? = null,
    ) {
        val payload = TestRunResultState.resultPayload(challengeId, result, inputs, mode)
        latestResult = challengeId to payload
        project.getService(ProblemViewTabNavigator::class.java).showTestCases()
        val target = bridge
        if (target == null) {
            logger.info("testcases tab bridge not attached; result kept for testrun:latest pickup")
        } else {
            runCatching {
                target.postMessage(TOPIC_TESTRUN_RESULT, payload)
            }.onFailure { error -> logger.warning("Failed to push $TOPIC_TESTRUN_RESULT: $error") }
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

    /** 发起前的就绪产物(逐用例输入按位对齐结果明细表;mode 供结果页区分来源)。 */
    private data class PreparedTest(
        val capable: TestEvaluationCapable,
        val request: TestRunRequest,
        val inputs: List<String>,
        val mode: String? = null,
    )

    /** 按数据源分发:工作区用例(WP55 原路径)/题面示例(WP74)。 */
    private suspend fun buildRequest(
        target: ChallengeTarget,
        code: String,
        source: TestSource,
    ): PreparedTest? =
        when (source) {
            TestSource.PROBLEM_EXAMPLES -> buildExamplesRequest(target, code)
            TestSource.WORKSPACE_CASES -> buildWorkspaceRequest(target, code)
        }

    /**
     * 全示例测试发起前就绪(WP74):平台/登录/语言前置同工作区路径;questionId 与
     * exampleTestcases 同一次 fetchProblemDetail 取得(SPI 数据面现成通道,不引入
     * REST/本地缓存);无(非空白)示例不发起云测(NO_EXAMPLES 文案)。
     */
    private suspend fun buildExamplesRequest(
        target: ChallengeTarget,
        code: String,
    ): PreparedTest? {
        val platform = SubmissionPlatforms.resolve(project, target.problem.platformId)
        if (platform == null || platform !is TestEvaluationCapable) {
            notify(
                TestRunPrep.messageKey(TestRunPrep.Refusal.PLATFORM_NOT_TESTABLE),
                platform?.info?.displayName ?: target.problem.platformId.value,
            )
            return null
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
        val detail = (platform as? ProblemQueryCapable)?.fetchProblemDetail(target.problem.remoteId)
        val questionId = detail?.questionId?.takeIf { it.isNotBlank() }
        if (detail == null || questionId == null) {
            notify(TestRunPrep.messageKey(TestRunPrep.Refusal.QUESTION_ID_MISSING))
            return null
        }
        val examples = TestRunPrep.exampleCases(detail.exampleTestcases)
        if (examples.isEmpty()) {
            notify(TestRunPrep.messageKey(TestRunPrep.Refusal.NO_EXAMPLES))
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
                    dataInput = examples.joinToString(TestRunPrep.DATA_INPUT_SEPARATOR),
                ),
            inputs = examples,
            mode = PAYLOAD_MODE_EXAMPLES,
        )
    }

    /** 拉用例 → 解析平台与语言 → 换取 questionId → 拼请求;任一前置不满足时通知并返回 null。 */
    private suspend fun buildWorkspaceRequest(
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

    companion object {
        /** 云端测试结果推送 topic(Kotlin → JS,payload 见 [TestRunResultState.resultPayload])。 */
        const val TOPIC_TESTRUN_RESULT = "testrun:result"

        /** 页面取最近结果 topic(JS → Kotlin,payload `{"challengeId":"..."}`)。 */
        const val TOPIC_TESTRUN_LATEST = "testrun:latest"

        /** 结果 payload `mode` 值:全示例测试(WP74)——页面以「全部示例」标题区分「提交测试」。 */
        const val PAYLOAD_MODE_EXAMPLES = "examples"
    }
}

/** 解析 `testrun:latest` payload(纯函数):`{"challengeId":"..."}`;缺字段/非法 JSON 返回 null。 */
internal fun parseLatestRequest(payload: String): String? =
    runCatching {
        Json
            .parseToJsonElement(payload)
            .jsonObject["challengeId"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
