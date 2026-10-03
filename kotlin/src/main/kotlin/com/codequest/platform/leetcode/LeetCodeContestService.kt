package com.codequest.platform.leetcode

import com.codequest.core.model.ContestPhase
import com.codequest.spi.ContestCapable
import com.codequest.spi.ContestDetail
import com.codequest.spi.ContestProblemRef
import com.codequest.spi.ContestSummary
import com.codequest.spi.DailyQuestion
import com.codequest.spi.DailyQuestionCapable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * LeetCode CN 竞赛回顾与每日一题(1.8.0 F1/F2):[ContestCapable] +
 * [DailyQuestionCapable] 实现,仅由 CN 变体平台([LeetCodeCnPlatform])组装——
 * COM 站无对应 API(插件实证),不实现(REST 能力探测自动 404)。
 *
 * - [contests]:CN GraphQL `contestHistory` 近期回顾(2026-10-03 实测仅含已
 *   结束场次、不含未来赛),phase 由 startTime+duration 与注入 clock 推导
 *   (站方无 CF 式 phase 字段,五值枚举仅用 BEFORE/CODING/FINISHED);
 * - [contestDetail]:CN REST `/contest/api/info/{slug}/`(不存在抛领域异常,
 *   题目缺失降级空列表,语义见 [LeetCodeApi.fetchContestInfo]);题目引用
 *   remoteId = 题 title_slug(与题库同源)、index = "Q{n}";**joinable 恒
 *   false**(回顾语义,LC 参赛走 summary.url 外链,同 CF「参加=外链」结论);
 * - [fetchDailyQuestion]:纯透传(三态降级语义见
 *   [LeetCodeApi.fetchDailyQuestion])。
 *
 * 实现照 [com.codequest.platform.codeforces.CodeForcesContestService] 先例。
 */
class LeetCodeContestService(
    private val api: LeetCodeApi,
    private val clock: () -> Instant = Instant::now,
) : ContestCapable,
    DailyQuestionCapable {
    override fun contests(): Flow<ContestSummary> =
        flow {
            api.fetchContestHistory(pageNum = 1, pageSize = CONTEST_PAGE_SIZE).forEach { contest ->
                emit(
                    summaryOf(
                        title = contest.title,
                        titleSlug = contest.titleSlug,
                        startTimeSeconds = contest.startTimeSeconds,
                        durationSeconds = contest.durationSeconds,
                    ),
                )
            }
        }

    override suspend fun contestDetail(remoteId: String): ContestDetail {
        val info = api.fetchContestInfo(remoteId)
        return ContestDetail(
            summary =
                summaryOf(
                    title = info.title,
                    titleSlug = info.titleSlug,
                    startTimeSeconds = info.startTimeSeconds,
                    durationSeconds = info.durationSeconds,
                ),
            problems =
                info.questions.mapIndexed { index, question ->
                    ContestProblemRef(
                        remoteId = question.titleSlug,
                        index = "Q${index + 1}",
                        title = question.title,
                    )
                },
            joinable = false,
        )
    }

    override suspend fun fetchDailyQuestion(): DailyQuestion? = api.fetchDailyQuestion()

    /** CN 站竞赛页 URL 常量(domain 固定 leetcode.cn,CN 专属服务不掺 variant)。 */
    private fun summaryOf(
        title: String,
        titleSlug: String,
        startTimeSeconds: Long,
        durationSeconds: Long,
    ): ContestSummary =
        ContestSummary(
            remoteId = titleSlug,
            title = title,
            startTime = Instant.ofEpochSecond(startTimeSeconds),
            durationMinutes = (durationSeconds / SECONDS_PER_MINUTE).toInt(),
            phase = leetCodeContestPhaseOf(startTimeSeconds, durationSeconds, clock().epochSecond),
            url = "https://leetcode.cn/contest/$titleSlug/",
        )

    companion object {
        private const val SECONDS_PER_MINUTE = 60L

        /** 近期回顾拉取条数(站方按开始时间倒序,10 场约覆盖五周)。 */
        const val CONTEST_PAGE_SIZE: Int = 10
    }
}

/**
 * startTime+duration(秒)与 now(秒)推导 [ContestPhase]——LC 站方无 phase
 * 字段,窗口推导:未开始 BEFORE / 赛中 CODING / 赛后 FINISHED(五值枚举仅用
 * 三值,PENDING_SYSTEM_TEST/SYSTEM_TEST 为 CF 专属概念)。
 */
internal fun leetCodeContestPhaseOf(
    startTimeSeconds: Long,
    durationSeconds: Long,
    nowEpochSeconds: Long,
): ContestPhase =
    when {
        nowEpochSeconds < startTimeSeconds -> ContestPhase.BEFORE
        nowEpochSeconds < startTimeSeconds + durationSeconds -> ContestPhase.CODING
        else -> ContestPhase.FINISHED
    }
