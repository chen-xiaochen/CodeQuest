package com.codequest.platform.hackerrank

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
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
) : SubmissionCapable,
    SubmissionDetailCapable {
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

    /**
     * WP36:详情重拉(contest/slug 由 problemRemoteId 反解,不依赖内存轮询上下文)。
     * message 沿用旧 updateSpecificSubmissionRecord 事实(CE 取 compile_message,其余
     * status 原文);testcase_status 通过计数进 extras;耗时/内存(codechecker_time)
     * 与错误用例三件套(提交响应不含 input/expected,那是 run-code 响应字段)旧 Form
     * 均未展示,如实为 null。
     */
    override suspend fun fetchSubmissionDetail(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): SubmissionDetail? {
        val (contestSlug, challengeSlug) = parseChallengeRemoteId(problemRemoteId)
        val result = api.fetchSubmissionResult(contestSlug, challengeSlug, remoteSubmissionId)
        val verdict = HackerRankApi.submissionStatusToResult(result.status, result.scoreProcessed)
        return SubmissionDetail(
            verdict = verdict,
            message =
                if (verdict is SubmissionResult.CompileError && result.compileMessage != null) {
                    result.compileMessage
                } else {
                    result.status
                },
            extras =
                if (result.testcaseStatus.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf(
                        SUBMISSION_EXTRA_TESTCASES to
                            "${result.testcaseStatus.count { it == TESTCASE_PASSED }}/${result.testcaseStatus.size}",
                    )
                },
        )
    }

    /** 旧 showSubmissionDetails URL 事实:master 与竞赛两形态。 */
    override fun submissionViewUrl(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): String? {
        if (problemRemoteId.isEmpty()) return null
        val (contestSlug, challengeSlug) =
            runCatching { parseChallengeRemoteId(problemRemoteId) }.getOrNull() ?: return null
        return if (contestSlug == HACKERRANK_MASTER_CONTEST) {
            "https://$HACKERRANK_DOMAIN/challenges/$challengeSlug/submissions/code/$remoteSubmissionId"
        } else {
            "https://$HACKERRANK_DOMAIN/contests/$contestSlug/challenges/$challengeSlug/submissions/code/$remoteSubmissionId"
        }
    }

    companion object {
        /** 旧实现轮询固定 3s 间隔(Temporal.sleep(3.second))。 */
        const val POLL_INTERVAL_MS: Long = 3_000L

        /** extras 键:用例通过计数 "passed/total"(旧 Form 的 Testcases 标签事实)。 */
        const val SUBMISSION_EXTRA_TESTCASES: String = "testcases"

        /** 旧 Form 判定通过的事实常量:testcasestatus == 1 记通过。 */
        private const val TESTCASE_PASSED = 1

        private const val FIXED_BACKOFF = 1.0
    }
}
