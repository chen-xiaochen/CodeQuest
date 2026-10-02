package com.codequest.core

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP29:StatisticsService 聚合——空库/单日多条/平台过滤/streak 断续与跨月/
 * verdict 口径(仅 Accepted 计 AC)/窗口与汇总口径分离/参数防御;
 * 1.7.0 F2 追加:remote_submission 合并(并集/同键本地优先/AC 判定/计数)。
 */
class StatisticsServiceTest {
    private var now: Instant = Instant.parse("2026-08-18T12:00:00Z")
    private val zone = ZoneOffset.UTC
    private val rows = mutableListOf<Submission>()
    private val remoteRows = mutableListOf<RemoteSubmissionRecord>()

    private val service =
        StatisticsService(
            submissions = { rows.toList() },
            clock = { now },
            zone = zone,
        )

    /** 1.7.0 F2:注入站内历史数据源的合并形态。 */
    private val mergedService =
        StatisticsService(
            submissions = { rows.toList() },
            clock = { now },
            zone = zone,
            remoteSubmissions = RemoteSubmissionQuery { remoteRows.toList() },
        )

    @BeforeTest
    fun setUp() {
        rows.clear()
        remoteRows.clear()
        now = Instant.parse("2026-08-18T12:00:00Z")
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun submission(
        id: String,
        day: String,
        platform: String = "leetcode",
        verdict: SubmissionResult = SubmissionResult.Accepted,
    ) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId("c-1"),
        platformId = PlatformId(platform),
        remoteSubmissionId = "remote-$id",
        language = "Kotlin",
        codeSnapshot = "class Solution",
        verdict = verdict,
        timeMs = null,
        memoryKb = null,
        submittedAt = Instant.parse("${day}T10:00:00Z"),
    )

    private fun heatmapCounts(data: StatisticsData): List<Int> = data.heatmap.map { it.count }

    private fun remoteRecord(
        remoteId: String,
        day: String,
        platform: String = "leetcode",
        statusDisplay: String = "AC",
    ) = RemoteSubmissionRecord(
        platformId = PlatformId(platform),
        remoteId = remoteId,
        titleSlug = "two-sum",
        title = "Two Sum",
        statusDisplay = statusDisplay,
        lang = "kotlin",
        runtimeMs = 64,
        memoryKb = 67891,
        submittedAt = Instant.parse("${day}T10:00:00Z"),
    )

    // ------------------------------------------------------------------
    // 空库
    // ------------------------------------------------------------------

    @Test
    fun `empty repository yields zero summary and zero-filled windows`() =
        runBlocking {
            val data = service.statistics(heatmapDays = 7, trendDays = 3)

            assertEquals(StatisticsSummary(0, 0, 0.0, 0, 0), data.summary)
            assertEquals(7, data.heatmap.size)
            assertEquals(List(7) { 0 }, heatmapCounts(data))
            assertEquals(
                listOf("2026-08-12", "2026-08-13", "2026-08-14", "2026-08-15", "2026-08-16", "2026-08-17", "2026-08-18"),
                data.heatmap.map { it.date.toString() },
            )
            assertEquals(3, data.trend.size)
            assertEquals(
                listOf("2026-08-16", "2026-08-17", "2026-08-18"),
                data.trend.map { it.date.toString() },
            )
        }

    // ------------------------------------------------------------------
    // 单日多条
    // ------------------------------------------------------------------

    @Test
    fun `same-day submissions aggregate into one heatmap day`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18")
            rows += submission("s-2", "2026-08-18", verdict = SubmissionResult.WrongAnswer)
            rows += submission("s-3", "2026-08-18", verdict = SubmissionResult.Accepted)

            val data = service.statistics(heatmapDays = 7)

            assertEquals(StatisticsSummary(3, 2, 2.0 / 3.0, 1, 1), data.summary)
            assertEquals(listOf(0, 0, 0, 0, 0, 0, 3), heatmapCounts(data))
            assertEquals(3, data.trend.last().count)
        }

    @Test
    fun `only fully accepted verdicts count as accepted`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18", verdict = SubmissionResult.Accepted)
            rows += submission("s-2", "2026-08-17", verdict = SubmissionResult.PartialScore(0.5))
            rows += submission("s-3", "2026-08-16", verdict = SubmissionResult.Pending)
            rows += submission("s-4", "2026-08-15", verdict = SubmissionResult.Unknown("IO"))

            val data = service.statistics()

            assertEquals(4, data.summary.totalSubmissions)
            assertEquals(1, data.summary.acceptedSubmissions)
            assertEquals(0.25, data.summary.acceptanceRate)
        }

    // ------------------------------------------------------------------
    // 平台过滤
    // ------------------------------------------------------------------

    @Test
    fun `platform filter scopes summary heatmap and trend`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18", platform = "leetcode")
            rows += submission("s-2", "2026-08-17", platform = "leetcode")
            rows += submission("s-3", "2026-08-17", platform = "codeforces", verdict = SubmissionResult.WrongAnswer)

            val all = service.statistics(heatmapDays = 3)
            assertEquals(3, all.summary.totalSubmissions)
            assertEquals(listOf(0, 2, 1), heatmapCounts(all))

            val filtered = service.statistics(platformId = PlatformId("codeforces"), heatmapDays = 3)
            assertEquals(1, filtered.summary.totalSubmissions)
            assertEquals(0, filtered.summary.acceptedSubmissions)
            assertEquals(0.0, filtered.summary.acceptanceRate)
            assertEquals(listOf(0, 1, 0), heatmapCounts(filtered))
        }

    // ------------------------------------------------------------------
    // streak:断续 / 今天缺席 / 跨月
    // ------------------------------------------------------------------

    @Test
    fun `current streak counts back from today and survives a missing today`() =
        runBlocking {
            // 今天(08-18)无提交;昨天链 08-16..08-17 → 当前 streak=2;08-14 断开;
            // 历史最长 08-08..08-10 = 3
            for (day in listOf("2026-08-17", "2026-08-16", "2026-08-14", "2026-08-10", "2026-08-09", "2026-08-08")) {
                rows += submission("s-$day", day)
            }

            val data = service.statistics()

            assertEquals(2, data.summary.currentStreakDays)
            assertEquals(3, data.summary.longestStreakDays)
        }

    @Test
    fun `streak continues across a month boundary`() =
        runBlocking {
            now = Instant.parse("2026-08-01T12:00:00Z")
            for (day in listOf("2026-07-30", "2026-07-31", "2026-08-01")) {
                rows += submission("s-$day", day)
            }

            val data = service.statistics()

            assertEquals(3, data.summary.currentStreakDays)
            assertEquals(3, data.summary.longestStreakDays)
        }

    @Test
    fun `current streak is zero when both today and yesterday are inactive`() =
        runBlocking {
            rows += submission("s-1", "2026-08-14")

            val data = service.statistics()

            assertEquals(0, data.summary.currentStreakDays)
            assertEquals(1, data.summary.longestStreakDays)
        }

    // ------------------------------------------------------------------
    // 窗口与汇总口径
    // ------------------------------------------------------------------

    @Test
    fun `heatmap window drops old days while summary keeps them`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18")
            rows += submission("s-2", "2025-01-01")

            val data = service.statistics(heatmapDays = 7)

            assertEquals(2, data.summary.totalSubmissions)
            assertEquals(listOf(0, 0, 0, 0, 0, 0, 1), heatmapCounts(data))
            assertEquals(LocalDate.of(2026, 8, 18), data.heatmap.last().date)
        }

    @Test
    fun `trend window is independent from heatmap window`() =
        runBlocking {
            rows += submission("s-1", "2026-08-10")
            rows += submission("s-2", "2026-08-18")

            val data = service.statistics(heatmapDays = 10, trendDays = 3)

            assertEquals(10, data.heatmap.size)
            // 窗口 08-09..08-18:08-10 是第二格(第一格 08-09 为 0)
            assertEquals(0, data.heatmap.first().count)
            assertEquals(1, data.heatmap[1].count)
            assertEquals(3, data.trend.size)
            assertEquals(0, data.trend.first().count)
            assertEquals(1, data.trend.last().count)
        }

    // ------------------------------------------------------------------
    // 1.7.0 F2:remote_submission 合并口径
    // ------------------------------------------------------------------

    @Test
    fun `remote submissions merge into union counts`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18") // 本地 AC
            rows += submission("s-2", "2026-08-17", verdict = SubmissionResult.WrongAnswer)
            remoteRows += remoteRecord("r-1", "2026-08-17") // 站内 AC
            remoteRows += remoteRecord("r-2", "2026-08-16", statusDisplay = "Wrong Answer")

            val data = mergedService.statistics(heatmapDays = 3)

            // 并集:2 本地 + 2 站内;AC = 本地 1 + 站内 1
            assertEquals(4, data.summary.totalSubmissions)
            assertEquals(2, data.summary.acceptedSubmissions)
            assertEquals(0.5, data.summary.acceptanceRate)
            assertEquals(listOf(1, 2, 1), heatmapCounts(data)) // 16/17/18 日各计
            assertEquals(2, data.remoteSyncedCount)
        }

    @Test
    fun `duplicate platform and remote id keeps local verdict priority`() =
        runBlocking {
            // 两表同键 (leetcode, remote-s-1):本地 Accepted 优先,站内 WA 条目跳过
            // (submission 夹具的 remoteSubmissionId = "remote-s-1",与站内记录同键)
            rows += submission("s-1", "2026-08-18")
            remoteRows += remoteRecord("remote-s-1", "2026-08-18", statusDisplay = "Wrong Answer")

            val data = mergedService.statistics(heatmapDays = 3)

            assertEquals(1, data.summary.totalSubmissions)
            assertEquals(1, data.summary.acceptedSubmissions) // 本地完整 verdict 胜出
            assertEquals(listOf(0, 0, 1), heatmapCounts(data))
            // 计数为站内历史原始条数(展示「已同步 N 条」,含重合)
            assertEquals(1, data.remoteSyncedCount)
        }

    @Test
    fun `remote accepted judgment is statusDisplay AC only`() =
        runBlocking {
            remoteRows += remoteRecord("r-1", "2026-08-18", statusDisplay = "AC")
            remoteRows += remoteRecord("r-2", "2026-08-18", statusDisplay = "Wrong Answer")
            remoteRows += remoteRecord("r-3", "2026-08-18", statusDisplay = "Time Limit Exceeded")

            val data = mergedService.statistics(heatmapDays = 3)

            assertEquals(3, data.summary.totalSubmissions)
            assertEquals(1, data.summary.acceptedSubmissions)
        }

    @Test
    fun `platform filter scopes remote rows and remote synced count`() =
        runBlocking {
            remoteRows += remoteRecord("r-1", "2026-08-18", platform = "leetcode")
            remoteRows += remoteRecord("r-2", "2026-08-18", platform = "leetcodecn")

            val all = mergedService.statistics(heatmapDays = 3)
            assertEquals(2, all.summary.totalSubmissions)
            assertEquals(2, all.remoteSyncedCount)

            val filtered = mergedService.statistics(platformId = PlatformId("leetcode"), heatmapDays = 3)
            assertEquals(1, filtered.summary.totalSubmissions)
            assertEquals(1, filtered.remoteSyncedCount)
        }

    @Test
    fun `absent remote source keeps legacy behavior`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18")

            val data = service.statistics(heatmapDays = 3)

            assertEquals(1, data.summary.totalSubmissions)
            assertEquals(0, data.remoteSyncedCount)
        }

    // ------------------------------------------------------------------
    // 参数防御
    // ------------------------------------------------------------------

    @Test
    fun `non-positive windows are rejected`() {        assertFailsWith<IllegalArgumentException> { runBlocking { service.statistics(heatmapDays = 0) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { service.statistics(trendDays = -1) } }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.statistics(heatmapDays = StatisticsService.MAX_WINDOW_DAYS + 1) }
        }
    }

    @Test
    fun `windows are ordered ascending and end at today`() =
        runBlocking {
            rows += submission("s-1", "2026-08-18")

            val data = service.statistics(heatmapDays = 5)

            assertTrue(data.heatmap.zipWithNext().all { (a, b) -> a.date < b.date })
            assertEquals(LocalDate.of(2026, 8, 18), data.heatmap.last().date)
        }
}
