package com.codequest.platform.hackerrank

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * HackerRank 提交(WP27):SubmissionCapable 实现。
 *
 * **适配器自身不写轮询循环**(SPI 验证点):流程为
 * 1. `submit`:语言 id 校验(HackerRankLanguages 表)→ remoteId 解析出
 *    (contest, slug) → 题目页取 CSRF token → JSON POST
 *    `/rest/contests/{contest}/challenges/{slug}/submissions`
 *    (body `{language, contest_slug, challenge_slug, code}` + x-csrf-token)
 *    → `model.id` 包装为 [SubmissionHandle];contest/slug 随提交上下文留在内存表
 *    (轮询 URL 要用,与 AtCoder 同法);
 * 2. `watchSubmission`:委托基于 [BaseSubmissionFlow] 的内部状态机,只实现
 *    fetchRemoteStatus 钩子(单次 GET 提交结果端点,status/score_processed 判定),
 *    退避/封顶/超时全部复用 spi 状态机;轮询间隔 3s 固定(旧实现 sleep 3s 事实,
 *    PollConfig 封顶同为 3s)。
 *
 * 终止语义:score_processed != 3 → 非终态继续轮询;== 3 后按 status 映射
 * (见 [HackerRankApi.submissionStatusToResult])。提交详情页 URL(浏览器兜底出口):
 * `/challenges/{slug}/submissions/code/{id}`(master)或
 * `/contests/{contest}/challenges/{slug}/submissions/code/{id}`。
 */
class HackerRankSubmissionService(
    private val api: HackerRankApi,
    pollConfig: BaseSubmissionFlow.PollConfig =
        BaseSubmissionFlow.PollConfig(
            initialIntervalMs = POLL_INTERVAL_MS,
            maxIntervalMs = POLL_INTERVAL_MS,
            backoffMultiplier = FIXED_BACKOFF,
        ),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SubmissionCapable {
    /** submissionId → (contestSlug, challengeSlug)(轮询 URL 要用)。 */
    private val challengeKeys = ConcurrentHashMap<String, Pair<String, String>>()

    private val flow =
        object : BaseSubmissionFlow(pollConfig, delayFn) {
            override suspend fun fetchRemoteStatus(handle: SubmissionHandle) =
                challengeKeys[handle.remoteSubmissionId]?.let { (contest, slug) ->
                    api.fetchSubmissionResult(contest, slug, handle.remoteSubmissionId).toSnapshot()
                } ?: throw OJException(
                    "提交 ${handle.remoteSubmissionId} 无轮询上下文(contest/challenge 未随提交留存," +
                        "可能因插件重启;请在 hackerrank.com 查看结果)",
                )
        }

    override suspend fun submit(request: SubmitRequest): SubmissionHandle {
        if (HackerRankLanguages.find(request.languageId) == null) {
            throw OJException("HackerRank 不支持的提交语言: ${request.languageId}")
        }
        val (contestSlug, challengeSlug) = parseChallengeRemoteId(request.problemRemoteId)
        val submissionId = api.submitAnswer(challengeSlug, contestSlug, request.languageId, request.code)
        challengeKeys[submissionId] = contestSlug to challengeSlug
        return SubmissionHandle(platformId = HACKERRANK_PLATFORM_ID, remoteSubmissionId = submissionId)
    }

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = flow.watchSubmission(handle)

    /** 快照流(供落库等需要完整快照的调用方,对应 BaseSubmissionFlow.watchSnapshots)。 */
    fun watchSnapshots(handle: SubmissionHandle) = flow.watchSnapshots(handle)

    companion object {
        /** 旧实现轮询固定 3s 间隔(Temporal.sleep(3.second))。 */
        const val POLL_INTERVAL_MS: Long = 3_000L

        private const val FIXED_BACKOFF = 1.0
    }
}
