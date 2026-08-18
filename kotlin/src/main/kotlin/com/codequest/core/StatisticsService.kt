package com.codequest.core

import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统计数据源端口(WP29):跨工作区列出(全部平台的)提交记录,供聚合。
 *
 * 之所以不是直接复用 [com.codequest.core.repository.SubmissionRepository]:
 * 该接口(WP13)只有按 challenge 的查询,跨平台聚合需要「列全量」能力——
 * 以本端口最小化补充,避免改动既有 core 接口及其实现/测试假件;
 * infra 侧由 Exposed 仓库适配(见 infra.messaging.StatisticsApi)。
 */
fun interface SubmissionQuery {
    /** 全量提交(顺序不限;聚合不依赖顺序)。 */
    suspend fun list(): List<Submission>
}

/** 汇总卡片(WP29):口径=全量历史,只受平台过滤,不受热力图/趋势窗口影响。 */
data class StatisticsSummary(
    val totalSubmissions: Int,
    val acceptedSubmissions: Int,
    /** AC 率 = accepted / total,空库为 0.0,范围 [0, 1]。 */
    val acceptanceRate: Double,
    /** 当前连续刷题天数:今天有提交则从今天倒数;今天还没有则从昨天倒数(当日未结束不算断)。 */
    val currentStreakDays: Int,
    /** 历史最长连续刷题天数。 */
    val longestStreakDays: Int,
)

/** 单日计数(热力图与趋势共用的序列元素)。 */
data class DailyCount(
    val date: LocalDate,
    val count: Int,
)

/** 聚合结果:summary + 热力图窗口 + 趋势窗口(均为零提交日占位的完整日历)。 */
data class StatisticsData(
    val summary: StatisticsSummary,
    /** 今天往前 [StatisticsService.statistics] 的 heatmapDays 天(含今天),升序。 */
    val heatmap: List<DailyCount>,
    /** 今天往前 trendDays 天(含今天),升序。 */
    val trend: List<DailyCount>,
)

/**
 * 刷题统计聚合用例(WP29,M5):对 submission 表口径做纯内存聚合。
 *
 * - AC 判定用 [com.codequest.core.model.SubmissionResult.isSuccessful]
 *   (仅 Accepted;PartialScore 不算完全通过);
 * - 「一天」按注入时区把 submittedAt 投到本地日期(本地工具,用户视角的一天);
 * - streak 按提交日期的连续日历天:当天无提交不立即断(从昨天续算),
 *   当天与昨天都无提交才归零——与 GitHub contribution 语义一致;
 * - 平台过滤作用于全部维度;时间范围仅作用于热力图/趋势窗口(summary 保持全量,
 *   卡片数字不随窗口漂移)。
 *
 * 纯逻辑、无持久化依赖,注入 [SubmissionQuery] 假件即可单测。
 */
class StatisticsService(
    private val submissions: SubmissionQuery,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * 聚合统计。
     *
     * @param platformId 平台过滤;null = 全部平台
     * @param heatmapDays 热力图窗口天数(含今天),1..[MAX_WINDOW_DAYS]
     * @param trendDays 趋势窗口天数(含今天),1..[MAX_WINDOW_DAYS]
     */
    suspend fun statistics(
        platformId: PlatformId? = null,
        heatmapDays: Int = DEFAULT_HEATMAP_DAYS,
        trendDays: Int = DEFAULT_TREND_DAYS,
    ): StatisticsData {
        require(heatmapDays in 1..MAX_WINDOW_DAYS) { "heatmapDays must be in 1..$MAX_WINDOW_DAYS: $heatmapDays" }
        require(trendDays in 1..MAX_WINDOW_DAYS) { "trendDays must be in 1..$MAX_WINDOW_DAYS: $trendDays" }
        val today = LocalDate.ofInstant(clock(), zone)
        var total = 0
        var accepted = 0
        val byDate = HashMap<LocalDate, Int>()
        for (submission in submissions.list()) {
            if (platformId != null && submission.platformId != platformId) continue
            total++
            if (submission.verdict.isSuccessful) accepted++
            byDate.merge(LocalDate.ofInstant(submission.submittedAt, zone), 1, Int::plus)
        }
        return StatisticsData(
            summary =
                StatisticsSummary(
                    totalSubmissions = total,
                    acceptedSubmissions = accepted,
                    acceptanceRate = if (total == 0) 0.0 else accepted.toDouble() / total,
                    currentStreakDays = currentStreak(byDate.keys, today),
                    longestStreakDays = longestStreak(byDate.keys),
                ),
            heatmap = window(today, heatmapDays, byDate),
            trend = window(today, trendDays, byDate),
        )
    }

    /** 今天往前 days 天(含今天)的完整日历序列,无提交日计 0,升序。 */
    private fun window(
        today: LocalDate,
        days: Int,
        byDate: Map<LocalDate, Int>,
    ): List<DailyCount> {
        val window = days - 1 downTo 0
        return window.map { back ->
            val date = today.minusDays(back.toLong())
            DailyCount(date, byDate[date] ?: 0)
        }
    }

    /** 当前连续天数:锚点=今天;今天无提交且昨天有 → 锚点=昨天;否则 0。 */
    private fun currentStreak(
        activeDays: Set<LocalDate>,
        today: LocalDate,
    ): Int {
        val yesterday = today.minusDays(1)
        val anchor =
            when {
                today in activeDays -> today
                yesterday in activeDays -> yesterday
                else -> return 0
            }
        var streak = 0
        var cursor = anchor
        while (cursor in activeDays) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 历史最长连续天数:排序后扫描相邻日期差一天的游程。 */
    private fun longestStreak(activeDays: Set<LocalDate>): Int {
        if (activeDays.isEmpty()) return 0
        val sorted = activeDays.sorted()
        var longest = 1
        var run = 1
        for (index in 1 until sorted.size) {
            run = if (sorted[index] == sorted[index - 1].plusDays(1)) run + 1 else 1
            if (run > longest) longest = run
        }
        return longest
    }

    companion object {
        /** 热力图默认窗口:近一年(含今天)。 */
        const val DEFAULT_HEATMAP_DAYS = 365

        /** 趋势默认窗口:近 30 天(含今天)。 */
        const val DEFAULT_TREND_DAYS = 30

        /** 窗口上限:两年余量,防误传巨型窗口撑爆响应。 */
        const val MAX_WINDOW_DAYS = 731
    }
}
