package com.codequest.platform.codeforces

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.AntiBotChallengeHandler
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.OJRuntime
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmissionStatusSnapshot
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * CodeForces 提交(WP24):SubmissionCapable 实现。
 *
 * **适配器自身不写轮询循环**(SPI 验证点):流程为
 * 1. `submit`:[OJRuntime.antiBotHandler] 以 [CODEFORCES_SUBMIT_TURNSTILE](旧
 *    CloudflareTurnstileDialog 常量)弹出人机验证,解析 `csrf,turnstile,ftaa,bfaa`
 *    四元组 → 表单 POST `/problemset/submit`(字段见 [CodeForcesApi.submitAnswer])
 *    → HTML 解析出 submissionId,csrf 随提交上下文留在内存表;
 * 2. `watchSubmission`:委托基于 [BaseSubmissionFlow] 的内部状态机,只实现
 *    fetchRemoteStatus 钩子(单次 POST `/data/submitSource`),退避/封顶/超时
 *    全部复用 spi 状态机。
 *
 * **快照指标如实为 null**:`/data/submitSource` 只返回 verdict(+checker 输出),
 * 耗时/内存要等赛后表格刷新,旧实现同样不在此端点取指标。
 *
 * csrf 上下文生命周期:进程内(提交→轮询是短生命周期操作);服务重启后对旧
 * handle 轮询抛 [OJException](去网站查看结果),不做隐式重新验证。
 */
class CodeForcesSubmissionService(
    private val api: CodeForcesApi,
    private val antiBotSource: () -> AntiBotChallengeHandler = OJRuntime::antiBotHandler,
    pollConfig: BaseSubmissionFlow.PollConfig = BaseSubmissionFlow.PollConfig(),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SubmissionCapable {
    /** submissionId → 提交时 Turnstile 弹窗取得的 csrf_token(轮询端点必带,旧实现闭包持有)。 */
    private val csrfTokens = ConcurrentHashMap<String, SubmissionContext>()

    /** 轮询上下文(csrf + 所属竞赛 id,构造轮询 Referer 用)。 */
    private data class SubmissionContext(
        val csrfToken: String,
        val contestId: Long,
    )

    private val flow =
        object : BaseSubmissionFlow(pollConfig, delayFn) {
            override suspend fun fetchRemoteStatus(handle: SubmissionHandle): SubmissionStatusSnapshot {
                val context =
                    csrfTokens[handle.remoteSubmissionId]
                        ?: throw OJException(
                            "提交 ${handle.remoteSubmissionId} 无轮询上下文(csrf 未随提交留存," +
                                "可能因插件重启;请在 codeforces.com 查看结果)",
                        )
                val verdict = api.fetchSubmissionVerdict(handle.remoteSubmissionId, context.contestId, context.csrfToken)
                return verdictToResult(verdict).toSnapshot()
            }
        }

    override suspend fun submit(request: SubmitRequest): SubmissionHandle {
        if (CodeForcesLanguages.find(request.languageId) == null) {
            throw OJException("CodeForces 不支持的提交语言 programTypeId: ${request.languageId}")
        }
        val (contestId, index) = parseProblemRemoteId(request.problemRemoteId)
        val rawSolved =
            antiBotSource().solve(CODEFORCES_SUBMIT_TURNSTILE)
                ?: throw turnstileFailed("用户取消或验证环境不可用(提交未发出)")
        val tokens =
            CodeForcesApi.SubmitTokens.parse(rawSolved)
                ?: throw turnstileFailed("弹窗回传格式不符,期望 'csrf,turnstile,ftaa,bfaa': $rawSolved")
        val submissionId =
            api.submitAnswer(
                contestId = contestId,
                index = index,
                programTypeId = request.languageId,
                source = request.code,
                tokens = tokens,
            )
        csrfTokens[submissionId] = SubmissionContext(tokens.csrfToken, contestId)
        return SubmissionHandle(platformId = CODEFORCES_PLATFORM_ID, remoteSubmissionId = submissionId)
    }

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = flow.watchSubmission(handle)

    /** 快照流(供落库等需要完整快照的调用方,对应 BaseSubmissionFlow.watchSnapshots)。 */
    fun watchSnapshots(handle: SubmissionHandle) = flow.watchSnapshots(handle)
}

/**
 * verdict 原文 → 平台无关判定。
 *
 * contains 匹配沿用旧实现(getSubmitAnswerResult)事实与顺序:先 Accepted 再
 * Running;差异:旧实现未映射的 verdict(如 "Hacked"/"Presentation error")
 * 维持 Processing 继续轮询,本实现映射 [SubmissionResult.Unknown](终态)立即
 * 结束——避免语义上已终局的结果被轮询到超时。
 */
internal fun verdictToResult(verdict: String?): SubmissionResult {
    if (verdict.isNullOrEmpty()) return SubmissionResult.Pending
    return when {
        verdict.contains("Accepted") -> SubmissionResult.Accepted
        verdict.contains("Running") -> SubmissionResult.Judging
        verdict.contains("Compilation error") -> SubmissionResult.CompileError
        verdict.contains("Wrong answer") -> SubmissionResult.WrongAnswer
        verdict.contains("Time limit exceeded") -> SubmissionResult.TimeLimitExceeded
        verdict.contains("Memory limit exceeded") -> SubmissionResult.MemoryLimitExceeded
        verdict.contains("Runtime error") -> SubmissionResult.RuntimeError
        else -> SubmissionResult.Unknown(verdict)
    }
}

private fun SubmissionResult.toSnapshot(): SubmissionStatusSnapshot =
    // 轮询端点不返回耗时/内存(旧实现事实),指标留空
    SubmissionStatusSnapshot(result = this, timeMs = null, memoryKb = null)
