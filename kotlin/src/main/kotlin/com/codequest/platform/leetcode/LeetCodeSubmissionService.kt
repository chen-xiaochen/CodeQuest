package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmissionStatusSnapshot
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * LeetCode 提交(WP19):SubmissionCapable 实现。
 *
 * **适配器自身不写轮询循环**(SPI 验证点):submit 拿到平台 submissionId 包装为
 * [SubmissionHandle];watchSubmission 委托给基于 [BaseSubmissionFlow] 的内部状态机,
 * 只实现 fetchRemoteStatus 钩子(单次 GET /submissions/detail/{id}/check/),
 * 退避/封顶/超时全部复用 spi 状态机。
 *
 * 约定:[SubmitRequest.code] 必须是**已提取的提交代码**(区域标记提取归 WP21 的
 * 编辑器 action,spec 2026-08-03 的 begin/end region 标记不在本层处理)。
 */
class LeetCodeSubmissionService(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
    pollConfig: BaseSubmissionFlow.PollConfig = BaseSubmissionFlow.PollConfig(),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SubmissionCapable {
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
}

/** /check/ 响应 → 平台无关判定快照(状态映射沿用旧实现 fromLeetCodeRunResult 的事实)。 */
internal fun LeetCodeApi.LeetCodeSubmissionCheck.toSnapshot(): SubmissionStatusSnapshot {
    val result =
        when {
            state.equals("SUCCESS", ignoreCase = true) -> mapStatusMessage(statusMsg)
            state.equals("STARTED", ignoreCase = true) -> SubmissionResult.Judging
            else -> SubmissionResult.Pending // PENDING 及未知非终态
        }
    return SubmissionStatusSnapshot(
        result = result,
        timeMs = statusRuntime?.extractMillis(),
        memoryKb = memory,
    )
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
