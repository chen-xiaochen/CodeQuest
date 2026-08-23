package com.codequest.platform.atcoder

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.AntiBotChallengeHandler
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.OJRuntime
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmissionStatusSnapshot
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * AtCoder 提交(WP25):SubmissionCapable 实现。
 *
 * **适配器自身不写轮询循环**(SPI 验证点):流程为
 * 1. `submit`:[OJRuntime.antiBotHandler] 以 [atCoderSubmitTurnstile](旧
 *    CloudflareTurnstileDialog 常量:sitekey/challengeSelector/resultExpression
 *    逐字)弹出该竞赛提交页的人机验证,解析 `csrf,turnstile` 二元组 → 表单
 *    POST `/contests/<id>/submit`(字段见 [AtCoderApi.submitAnswer])→ HTML
 *    解析出 submissionId,contestId 随提交上下文留在内存表(轮询 URL 要用);
 * 2. `watchSubmission`:委托基于 [BaseSubmissionFlow] 的内部状态机,只实现
 *    fetchRemoteStatus 钩子(单次 GET 提交详情页,`td#judge-status > span`
 *    判定),退避/封顶/超时全部复用 spi 状态机。
 *
 * **快照指标如实为 null**:提交详情页的耗时/内存列旧实现从未提取(只读判定),
 * 本实现同样不提取。上下文生命周期:进程内(提交→轮询短生命周期);服务重启后
 * 对旧 handle 轮询抛 [OJException](去网站查看结果),不做隐式重新验证。
 */
class AtCoderSubmissionService(
    private val api: AtCoderApi,
    private val antiBotSource: () -> AntiBotChallengeHandler = OJRuntime::antiBotHandler,
    pollConfig: BaseSubmissionFlow.PollConfig = BaseSubmissionFlow.PollConfig(),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SubmissionCapable,
    SubmissionDetailCapable {
    /** submissionId → 所属竞赛 id(轮询 URL `/contests/<cid>/submissions/<sid>` 要用)。 */
    private val contestIds = ConcurrentHashMap<String, String>()

    private val flow =
        object : BaseSubmissionFlow(pollConfig, delayFn) {
            override suspend fun fetchRemoteStatus(handle: SubmissionHandle): SubmissionStatusSnapshot {
                val contestId =
                    contestIds[handle.remoteSubmissionId]
                        ?: throw OJException(
                            "提交 ${handle.remoteSubmissionId} 无轮询上下文(contestId 未随提交留存," +
                                "可能因插件重启;请在 atcoder.jp 查看结果)",
                        )
                val html = api.fetchSubmissionStatusPage(contestId, handle.remoteSubmissionId)
                val status =
                    AtCoderHtml.extractJudgeStatus(html)
                        ?: throw OJException("AtCoder 提交详情页无 judge-status 单元格(登录态失效或页面结构变化)")
                return judgeStatusToResult(status.title, status.label).toSnapshot()
            }
        }

    override suspend fun submit(request: SubmitRequest): SubmissionHandle {
        if (AtCoderLanguages.find(request.languageId) == null) {
            throw OJException("AtCoder 不支持的提交语言 data.LanguageId: ${request.languageId}")
        }
        val (contestId, problemId) = parseProblemRemoteId(request.problemRemoteId)
        val rawSolved =
            antiBotSource().solve(atCoderSubmitTurnstile(contestId))
                ?: throw turnstileFailed("用户取消或验证环境不可用(提交未发出)")
        val tokens =
            AtCoderApi.SubmitTokens.parse(rawSolved)
                ?: throw turnstileFailed("弹窗回传格式不符,期望 'csrf,turnstile': $rawSolved")
        val submissionId =
            api.submitAnswer(
                contestId = contestId,
                problemId = problemId,
                languageId = request.languageId,
                source = request.code,
                tokens = tokens,
            )
        contestIds[submissionId] = contestId
        return SubmissionHandle(platformId = ATCODER_PLATFORM_ID, remoteSubmissionId = submissionId)
    }

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = flow.watchSubmission(handle)

    /**
     * WP36:不重拉(继承默认 null)。旧 AtCoderSubmissionResultForm 的详情区全部来自
     * 本地数据(判定/时间/语言/代码/消息);提交详情页的耗时/内存列旧实现从未提取
     * (只读判定),本实现同样不提取——如实申报"无详情"而非报错。
     */
    override fun submissionViewUrl(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): String? {
        if (problemRemoteId.isEmpty()) return null
        // 旧 showSubmissionDetails URL 事实:/contests/<cid>/submissions/<sid>/
        val contestId =
            runCatching { parseProblemRemoteId(problemRemoteId).first }.getOrNull() ?: return null
        return atCoderSubmissionUrl(contestId, remoteSubmissionId)
    }
}

/**
 * 判定映射:title 属性 + 短码文本 → 平台无关判定。
 *
 * 匹配顺序与大小写不敏感沿用旧实现(judgeStatusToSubmissionResult)事实:
 * title 为 "Judging" → 评测中;否则按短码 contains(AC→WA→TLE→MLE→RE→CE→
 * QLE→OLE→IE→WJ→WR)。与旧实现的两处差异(与 WP24 CF 同理由):
 * - 旧 `Failure` 兜底(含 QLE)在新体系无通用 Failure,归
 *   [SubmissionResult.Unknown] 终态保留原文,避免已终局结果被轮询到超时;
 * - 旧 InternalError(IE)core 未建模,同样归 Unknown。
 */
internal fun judgeStatusToResult(
    title: String,
    label: String,
): SubmissionResult {
    if (title.equals("Judging", ignoreCase = true)) return SubmissionResult.Judging
    val text = label.uppercase()
    return when {
        "AC" in text -> SubmissionResult.Accepted
        "WA" in text -> SubmissionResult.WrongAnswer
        "TLE" in text -> SubmissionResult.TimeLimitExceeded
        "MLE" in text -> SubmissionResult.MemoryLimitExceeded
        "RE" in text -> SubmissionResult.RuntimeError
        "CE" in text -> SubmissionResult.CompileError
        "WJ" in text -> SubmissionResult.Judging
        "WR" in text -> SubmissionResult.Judging
        "OLE" in text -> SubmissionResult.OutputLimitExceeded
        else -> SubmissionResult.Unknown(label.ifBlank { title })
    }
}

private fun SubmissionResult.toSnapshot(): SubmissionStatusSnapshot =
    // 提交详情页的耗时/内存列旧实现未提取(事实),指标留空
    SubmissionStatusSnapshot(result = this, timeMs = null, memoryKb = null)

/** Turnstile 弹窗取消/格式不符 → 领域异常(提交链路上抛点)。 */
internal fun turnstileFailed(detail: String): AntiBotChallengeException =
    AntiBotChallengeException(
        "AtCoder 人机验证未完成: $detail",
        challengeType = "cloudflare-turnstile",
    )
