package com.codequest.core.review

import com.codequest.core.model.ProblemId
import com.codequest.core.model.ReviewSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 复习排期存取端口(1.7.0 F3):core 用例服务不依赖 infra,Exposed 仓库实现本接口
 * (方法契约与 WP59 的 ExposedReviewRepository 完全一致,实现零适配直接挂接口)。
 */
interface ReviewScheduleStore {
    suspend fun findByProblem(problemId: ProblemId): ReviewSchedule?

    suspend fun upsert(record: ReviewSchedule)

    /** 全部排期行(todayView 分组装配用)。 */
    suspend fun listAll(): List<ReviewSchedule>
}

/**
 * 复习间隔配置原始值端口(settings_kv 键 [ReviewService.SETTINGS_KEY],逗号分隔天数文本)。
 *
 * 传**原始文本**而非解析结果:解析与非法回退归 core([ReviewService.parseIntervals],
 * 单测直接注入坏值覆盖回退路径),infra 实现只做无语义透读。
 */
fun interface ReviewIntervalsSource {
    suspend fun rawIntervals(): String?
}

/** 今日视图行(REST 层联查 problem 表补标题/难度/平台元数据)。 */
data class ReviewItem(
    val problemId: ProblemId,
    /** 下次到期日(epoch 秒,零点对齐)——该题计划日中的最早者。 */
    val nextDate: Long,
)

/** todayView 聚合结果:三组行 + 追踪总数。 */
data class ReviewTodayView(
    /** 已过期:plan 存在小于今天零点的计划日。 */
    val overdue: List<ReviewItem>,
    /** 今日到期:plan 含今天零点且无更早日期。 */
    val today: List<ReviewItem>,
    /** 未来:全部计划日大于今天零点。 */
    val upcoming: List<ReviewItem>,
    /** 追踪中的题目总数(含排期已全部完成的历史行,不进三组)。 */
    val totalTracked: Int,
)

/**
 * 间隔重复复习调度(1.7.0 F3,spec §3.2):`review_schedule` 表之上的平台无关用例。
 *
 * - 「一天」按注入时区的**零点**对齐([todayEpoch]),跨零点自然翻组;
 * - 排期按生效间隔自基准日平移 n×86400 秒生成;完成复习即移除今天及过去全部
 *   计划日(未来保留),history 追加今天零点(同日重复完成不重复记);
 * - 间隔配置经 [ReviewIntervalsSource] 注入原始文本,解析失败/空/非正整数/
 *   非严格升序一律回退默认 `1,4,7,14,28,60`;
 * - 无状态:仓库按调用解析(切库即时生效),注入假件与固定 clock 即可单测。
 */
class ReviewService(
    private val store: ReviewScheduleStore,
    private val intervalsSource: ReviewIntervalsSource? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * AC 回调(提交链路平台无关触发点):plan 为空(未排期/已全部完成)→ 按
     * 「AC 日零点 + n 天」生成全量排期;plan 非空 → 等价完成本次复习
     * (移除今天及过去、history 记今天),未来计划保留。
     */
    suspend fun onAccepted(problemId: ProblemId): ReviewSchedule {
        val today = todayEpoch()
        val existing = store.findByProblem(problemId)
        return if (existing == null || existing.planDates.isEmpty()) {
            persist(problemId, planFrom(today), existing?.historyDates.orEmpty())
        } else {
            existing.completed(today)
        }
    }

    /**
     * 手动加入(已 AC 老题冷启动):plan 为空 → 按默认语义「明天零点起」全量排期
     * (与首 AC 同一生成式,间隔表首项 1 天即明天);已有 plan 幂等不动。
     */
    suspend fun addManual(problemId: ProblemId): ReviewSchedule {
        val existing = store.findByProblem(problemId)
        if (existing != null && existing.planDates.isNotEmpty()) return existing
        return persist(problemId, planFrom(todayEpoch()), existing?.historyDates.orEmpty())
    }

    /**
     * 手动标记完成当日复习(REST):等价 [onAccepted] 的移除分支,**不生成新排期**
     * (移除后 plan 空 = 本就是最后一天,自然结束);未排期返回 null(REST 映射 404)。
     */
    suspend fun completeToday(problemId: ProblemId): ReviewSchedule? {
        val existing = store.findByProblem(problemId) ?: return null
        if (existing.planDates.isEmpty()) return existing
        return existing.completed(todayEpoch())
    }

    /** 当日视图:按各题最早计划日与今天零点的大小分三组(组内按到期日、题号稳定排序)。 */
    suspend fun todayView(): ReviewTodayView {
        val today = todayEpoch()
        val schedules = store.listAll()
        val overdue = ArrayList<ReviewItem>()
        val dueToday = ArrayList<ReviewItem>()
        val upcoming = ArrayList<ReviewItem>()
        for (schedule in schedules) {
            // plan 空最小值为 null = 排期已全部完成,不进任何组,仅计入 totalTracked
            val next = schedule.planDates.minOrNull() ?: continue
            val item = ReviewItem(schedule.problemId, next)
            when {
                next < today -> overdue += item
                next == today -> dueToday += item
                else -> upcoming += item
            }
        }

        fun sortedRows(rows: List<ReviewItem>) = rows.sortedWith(compareBy({ it.nextDate }, { it.problemId.value }))
        return ReviewTodayView(sortedRows(overdue), sortedRows(dueToday), sortedRows(upcoming), schedules.size)
    }

    /** 当前生效间隔(解析 settings 原始值,非法回退默认);`GET /api/review/config` 数据源。 */
    suspend fun reviewIntervals(): List<Long> = parseIntervals(intervalsSource?.rawIntervals())

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 完成一次复习:移除今天及过去全部计划日,history 追加今天(同日重复不重复记)。 */
    private suspend fun ReviewSchedule.completed(today: Long): ReviewSchedule {
        val history = if (historyDates.lastOrNull() == today) historyDates else historyDates + today
        return persist(problemId, planDates.filter { it > today }, history)
    }

    private suspend fun persist(
        problemId: ProblemId,
        planDates: List<Long>,
        historyDates: List<Long>,
    ): ReviewSchedule = ReviewSchedule(problemId, planDates.sorted(), historyDates, clock()).also { store.upsert(it) }

    /** 自基准日零点生成全量排期(升序)。 */
    private suspend fun planFrom(baseToday: Long): List<Long> = reviewIntervals().map { baseToday + it * SECONDS_PER_DAY }

    /** 今天零点(epoch 秒,注入时区)。 */
    private fun todayEpoch(): Long = LocalDate.ofInstant(clock(), zone).atStartOfDay(zone).toEpochSecond()

    companion object {
        /** settings_kv 键名(值为逗号分隔天数文本,如 "1,4,7,14,28,60")。 */
        const val SETTINGS_KEY = "review_intervals"

        /** 默认间隔(天):AC 后第 1/4/7/14/28/60 天各复习一次。 */
        val DEFAULT_INTERVALS = listOf(1L, 4L, 7L, 14L, 28L, 60L)

        /** [DEFAULT_INTERVALS] 的存储文本形态(默认值提示/文档用)。 */
        const val DEFAULT_INTERVALS_RAW = "1,4,7,14,28,60"

        private const val SECONDS_PER_DAY = 86_400L

        /**
         * 解析间隔文本:空/含非正整数/非严格升序(重复也算)→ 回退 [DEFAULT_INTERVALS];
         * 允许单项(如 "3" = 只复习一次);元素两端空白容忍。
         */
        fun parseIntervals(raw: String?): List<Long> {
            if (raw.isNullOrBlank()) return DEFAULT_INTERVALS
            val values = raw.split(',').map { it.trim().toLongOrNull() }
            if (values.any { it == null || it <= 0 }) return DEFAULT_INTERVALS
            val intervals = values.filterNotNull()
            if (intervals.zipWithNext().any { (earlier, later) -> later <= earlier }) return DEFAULT_INTERVALS
            return intervals
        }
    }
}
