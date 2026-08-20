package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionErrorCase
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmissionStatusSnapshot
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * LeetCode 提交(WP19):SubmissionCapable 实现;WP36 增实现 [SubmissionDetailCapable]。
 *
 * **适配器自身不写轮询循环**(SPI 验证点):submit 拿到平台 submissionId 包装为
 * [SubmissionHandle];watchSubmission 委托给基于 [BaseSubmissionFlow] 的内部状态机,
 * 只实现 fetchRemoteStatus 钩子(单次 GET /submissions/detail/{id}/check/),
 * 退避/封顶/超时全部复用 spi 状态机。
 *
 * 提交详情(WP36,M7-A):重拉同一 /check/ 端点的全量字段(判定之外的增强数据,
 * 字段名 = 旧 LeetCodeSubmitAnswerResult.Success 事实)——**错误用例三件套
 * (input_formatted/expected_output/code_output)LC 系必含**;CE/RE 的 message
 * 取旧 formatErrorMessage 事实(full_* 优先);percentile/用例计数进 extras。
 * 旧响应中存在但旧 Form 未展示的字段(std_output/last_testcase/elapsed_time/
 * compare_result)不进 DTO(YAGNI,旧展示面即事实)。
 *
 * 约定:[SubmitRequest.code] 必须是**已提取的提交代码**(区域标记提取归 WP21 的
 * 编辑器 action,spec 2026-08-03 的 begin/end region 标记不在本层处理)。
 */
class LeetCodeSubmissionService(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
    pollConfig: BaseSubmissionFlow.PollConfig = BaseSubmissionFlow.PollConfig(),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SubmissionCapable,
    SubmissionDetailCapable {
    private val flow =
        object : BaseSubmissionFlow(pollConfig, delayFn) {
            override suspend fun fetchRemoteStatus(handle: SubmissionHandle): SubmissionStatusSnapshot =
                api.fetchSubmissionCheck(handle.remoteSubmissionId).toSnapshot()
        }

    override suspend fun submit(request: SubmitRequest): SubmissionHandle {
        if (LeetCodeLanguages.find(request.languageId) == null) {
            throw OJException("LeetCode 不支持的提交语言 id: ${request.languageId}")
        }
        // 提交端点需要 question_id(平台侧数字主档 id),remoteId 是 titleSlug,先取详情换 id
        val question = api.fetchQuestionDetail(request.problemRemoteId)
        val submissionId =
            api.submitCode(
                questionId = question.questionId,
                problemSlug = request.problemRemoteId,
                langSlug = request.languageId,
                code = request.code,
            )
        return SubmissionHandle(platformId = variant.id, remoteSubmissionId = submissionId)
    }

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = flow.watchSubmission(handle)

    /** 快照流(供落库等需要耗时/内存指标的调用方,对应 BaseSubmissionFlow.watchSnapshots)。 */
    fun watchSnapshots(handle: SubmissionHandle) = flow.watchSnapshots(handle)

    /** WP36:详情重拉(problemRemoteId 即 titleSlug,详情端点按 submissionId 寻址,无需它)。 */
    override suspend fun fetchSubmissionDetail(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): SubmissionDetail {
        val check = api.fetchSubmissionCheckDetail(remoteSubmissionId)
        val result = mapCheckResult(check.state, check.statusMsg)
        return SubmissionDetail(
            verdict = result,
            timeMs = check.statusRuntime?.extractMillis(),
            memoryKb = check.memory,
            language = check.prettyLang?.takeIf { it.isNotBlank() },
            message = detailMessage(result, check),
            errorCase = detailErrorCase(result, check),
            extras = detailExtras(check),
        )
    }

    /** 旧 showSubmissionDetails URL 事实:`https://<domain>/problems/<slug>/submissions/<id>/`。 */
    override fun submissionViewUrl(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): String? =
        problemRemoteId
            .takeIf { it.isNotBlank() }
            ?.let { slug -> "https://${variant.domain}/problems/$slug/submissions/$remoteSubmissionId/" }

    /** message 映射 = 旧 formatErrorMessage 事实(CE/RE 取 full_* 全文,回退 status_msg)。 */
    private fun detailMessage(
        result: SubmissionResult,
        check: LeetCodeApi.LeetCodeSubmissionCheckDetail,
    ): String? =
        when (result) {
            is SubmissionResult.CompileError -> check.fullCompileError ?: check.compileError ?: check.statusMsg
            is SubmissionResult.RuntimeError -> check.fullRuntimeError ?: check.runtimeError ?: check.statusMsg
            else -> check.statusMsg
        }

    /**
     * 三件套仅 WA 展示(旧 LeetCodeSubmissionResultFormHelper 事实:
     * Success→指标表,Failure(WA)→三件套表,其余→message 文案);
     * input 取 input_formatted,缺失回退 input(同数据未格式化形态)。
     */
    private fun detailErrorCase(
        result: SubmissionResult,
        check: LeetCodeApi.LeetCodeSubmissionCheckDetail,
    ): SubmissionErrorCase? {
        if (result !is SubmissionResult.WrongAnswer) return null
        return SubmissionErrorCase(
            input = check.inputFormatted ?: check.input,
            expectedOutput = check.expectedOutput,
            actualOutput = check.codeOutput,
        )
    }

    /** extras 键契约见 [SubmissionDetail.extras];旧 formatSuccessMetrics 的百分位即此来源。 */
    private fun detailExtras(check: LeetCodeApi.LeetCodeSubmissionCheckDetail): Map<String, String> =
        buildMap {
            check.runtimePercentile?.let { put("runtimePercentile", String.format(Locale.ROOT, "%.2f%%", it)) }
            check.memoryPercentile?.let { put("memoryPercentile", String.format(Locale.ROOT, "%.2f%%", it)) }
            if (check.totalCorrect != null || check.totalTestcases != null) {
                put("testcases", "${check.totalCorrect ?: 0}/${check.totalTestcases ?: 0}")
            }
        }
}

/** /check/ 响应 → 平台无关判定快照(状态映射沿用旧实现 fromLeetCodeRunResult 的事实)。 */
internal fun LeetCodeApi.LeetCodeSubmissionCheck.toSnapshot(): SubmissionStatusSnapshot =
    SubmissionStatusSnapshot(
        result = mapCheckResult(state, statusMsg),
        timeMs = statusRuntime?.extractMillis(),
        memoryKb = memory,
    )

/** state + status_msg → 判定(轮询快照与详情重拉共用;旧实现事实)。 */
internal fun mapCheckResult(
    state: String,
    statusMsg: String?,
): SubmissionResult =
    when {
        state.equals("SUCCESS", ignoreCase = true) -> mapStatusMessage(statusMsg)
        state.equals("STARTED", ignoreCase = true) -> SubmissionResult.Judging
        else -> SubmissionResult.Pending // PENDING 及未知非终态
    }

/** SUCCESS 态按 status_msg 原文映射判定(旧实现 contains 匹配事实)。 */
private fun mapStatusMessage(msg: String?): SubmissionResult {
    if (msg == null) return SubmissionResult.Unknown("null")
    return when {
        msg.contains("Accepted") -> SubmissionResult.Accepted
        msg.contains("Compile Error") -> SubmissionResult.CompileError
        msg.contains("Runtime Error") -> SubmissionResult.RuntimeError
        msg.contains("Time Limit Exceeded") -> SubmissionResult.TimeLimitExceeded
        msg.contains("Memory Limit Exceeded") -> SubmissionResult.MemoryLimitExceeded
        msg.contains("Output Limit Exceeded") -> SubmissionResult.OutputLimitExceeded
        msg.contains("Wrong Answer") -> SubmissionResult.WrongAnswer
        else -> SubmissionResult.Unknown(msg)
    }
}

/** `"4 ms"` / `"1 s"` → 毫秒数;解析失败返回 null。 */
private fun String.extractMillis(): Int? {
    val match = Regex("""^(\d+)\s*(ms|s)$""").find(trim())
    val value = match?.groupValues?.get(1)?.toIntOrNull() ?: return toIntOrNull()
    return if (match.groupValues[2] == "s") value * MILLIS_PER_SECOND else value
}

private const val MILLIS_PER_SECOND = 1000
