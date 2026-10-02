package com.codequest.core.review

import com.codequest.core.model.ProblemId
import com.codequest.core.model.ReviewSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] 1.7.0 F3(WP62):ReviewService——首 AC 排期(默认间隔)/再 AC 完成移除
 * (今天+过去全移、未来保留)/过期判定分组/手动加入(明天起、幂等)/间隔解析回退/
 * 跨零点边界(注入固定 clock 测昨天今天明天)。
 */
class ReviewServiceTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400L
    private var now: Instant = Instant.parse("2026-10-02T12:00:00Z")
    private val store = FakeStore()

    /** 间隔原始文本(null = 未配置);闭包读 var,测试可中途改配置。 */
    private var rawIntervals: String? = null

    private val service =
        ReviewService(
            store = store,
            intervalsSource = ReviewIntervalsSource { rawIntervals },
            clock = { now },
            zone = zone,
        )

    @BeforeTest
    fun setUp() {
        now = Instant.parse("2026-10-02T12:00:00Z")
        store.rows.clear()
        rawIntervals = null
    }

    /** 当前 now 对应的今天零点(epoch 秒)。 */
    private fun today0(): Long = LocalDate.ofInstant(now, zone).atStartOfDay(zone).toEpochSecond()

    private fun seed(
        problemId: String,
        plan: List<Long>,
        history: List<Long> = emptyList(),
    ) = ReviewSchedule(ProblemId(problemId), plan, history, Instant.parse("2026-10-01T00:00:00Z"))

    // ------------------------------------------------------------------
    // onAccepted
    // ------------------------------------------------------------------

    @Test
    fun `first accepted schedules default intervals from today`() = runBlocking {
        val schedule = service.onAccepted(ProblemId("p-1"))

        assertEquals(ReviewService.DEFAULT_INTERVALS.map { today0() + it * day }, schedule.planDates)
        assertTrue(schedule.historyDates.isEmpty())
        // 已落库
        assertEquals(schedule, store.rows["p-1"])
    }

    @Test
    fun `custom intervals from source are respected`() = runBlocking {
        rawIntervals = "2,5"
        val schedule = service.onAccepted(ProblemId("p-1"))
        assertEquals(listOf(today0() + 2 * day, today0() + 5 * day), schedule.planDates)
    }

    @Test
    fun `re-accepted removes today and past keeping future and records history`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = listOf(today0() - day, today0(), today0() + day, today0() + 4 * day))

        val schedule = service.onAccepted(ProblemId("p-1"))

        assertEquals(listOf(today0() + day, today0() + 4 * day), schedule.planDates)
        assertEquals(listOf(today0()), schedule.historyDates)
    }

    @Test
    fun `re-accepted twice same day does not duplicate history`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = listOf(today0(), today0() + day))
        service.onAccepted(ProblemId("p-1"))
        val schedule = service.onAccepted(ProblemId("p-1"))

        assertEquals(listOf(today0()), schedule.historyDates)
        assertEquals(listOf(today0() + day), schedule.planDates)
    }

    @Test
    fun `accepted after schedule exhausted regenerates full plan keeping history`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = emptyList(), history = listOf(today0() - 7 * day))

        val schedule = service.onAccepted(ProblemId("p-1"))

        assertEquals(ReviewService.DEFAULT_INTERVALS.map { today0() + it * day }, schedule.planDates)
        assertEquals(listOf(today0() - 7 * day), schedule.historyDates)
    }

    // ------------------------------------------------------------------
    // addManual / completeToday
    // ------------------------------------------------------------------

    @Test
    fun `addManual schedules from tomorrow when no plan exists`() = runBlocking {
        val schedule = service.addManual(ProblemId("p-1"))

        assertEquals(ReviewService.DEFAULT_INTERVALS.map { today0() + it * day }, schedule.planDates)
        assertEquals(today0() + day, schedule.planDates.first()) // 首个复习日 = 明天零点
        assertTrue(schedule.historyDates.isEmpty())
    }

    @Test
    fun `addManual on exhausted schedule regenerates keeping history`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = emptyList(), history = listOf(today0() - 7 * day))

        val schedule = service.addManual(ProblemId("p-1"))

        assertEquals(ReviewService.DEFAULT_INTERVALS.map { today0() + it * day }, schedule.planDates)
        assertEquals(listOf(today0() - 7 * day), schedule.historyDates)
    }

    @Test
    fun `addManual is idempotent when plan already exists`() = runBlocking {
        val existing = seed("p-1", plan = listOf(today0() + 3 * day))
        store.rows["p-1"] = existing

        assertEquals(existing.planDates, service.addManual(ProblemId("p-1")).planDates)
    }

    @Test
    fun `completeToday removes today and past without generating new plan`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = listOf(today0() - day, today0(), today0() + day))

        val schedule = service.completeToday(ProblemId("p-1"))!!

        assertEquals(listOf(today0() + day), schedule.planDates)
        assertEquals(listOf(today0()), schedule.historyDates)
    }

    @Test
    fun `completeToday on last day empties plan and repeats idempotently`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = listOf(today0()))
        assertEquals(emptyList(), service.completeToday(ProblemId("p-1"))!!.planDates)

        val again = service.completeToday(ProblemId("p-1"))!!
        assertEquals(emptyList(), again.planDates)
        assertEquals(listOf(today0()), again.historyDates) // 同日不重复记
    }

    @Test
    fun `completeToday returns null when untracked`() = runBlocking {
        assertNull(service.completeToday(ProblemId("nope")))
    }

    // ------------------------------------------------------------------
    // todayView 分组与跨零点边界
    // ------------------------------------------------------------------

    @Test
    fun `todayView groups by earliest plan date`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = listOf(today0() - day))                              // 过期
        store.rows["p-2"] = seed("p-2", plan = listOf(today0()))                                    // 今日
        store.rows["p-3"] = seed("p-3", plan = listOf(today0() + day))                              // 未来
        store.rows["p-4"] = seed("p-4", plan = listOf(today0() - day, today0() + day))              // 混合 → 过期
        store.rows["p-5"] = seed("p-5", plan = emptyList(), history = listOf(today0() - 2 * day))   // 已完成

        val view = service.todayView()

        assertEquals(listOf("p-1", "p-4"), view.overdue.map { it.problemId.value })
        assertEquals(listOf("p-2"), view.today.map { it.problemId.value })
        assertEquals(listOf("p-3"), view.upcoming.map { it.problemId.value })
        assertEquals(5, view.totalTracked)
        assertEquals(today0() - day, view.overdue[1].nextDate) // p-4 的 nextDate = 过期日
    }

    @Test
    fun `midnight boundary keeps today grouping until zero point crosses`() = runBlocking {
        store.rows["p-1"] = seed("p-1", plan = listOf(today0()))

        // 当天 23:59:59 仍是「今日」
        now = Instant.parse("2026-10-02T23:59:59Z")
        assertEquals(listOf("p-1"), service.todayView().today.map { it.problemId.value })

        // 跨过零点即翻为「已过期」
        now = Instant.parse("2026-10-03T00:00:01Z")
        assertEquals(listOf("p-1"), service.todayView().overdue.map { it.problemId.value })
    }

    // ------------------------------------------------------------------
    // 间隔解析
    // ------------------------------------------------------------------

    @Test
    fun `parseIntervals parses valid and falls back on invalid`() {
        assertEquals(listOf(1L, 4L, 7L), ReviewService.parseIntervals("1,4,7"))
        assertEquals(listOf(2L, 5L), ReviewService.parseIntervals(" 2 , 5 "))
        assertEquals(listOf(3L), ReviewService.parseIntervals("3"))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals(null))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals(""))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals("  "))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals("1,x,7"))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals("0,4"))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals("-1,4"))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals("7,4,1"))
        assertEquals(ReviewService.DEFAULT_INTERVALS, ReviewService.parseIntervals("1,1,2"))
    }

    @Test
    fun `invalid stored intervals fall back to default when scheduling`() = runBlocking {
        rawIntervals = "9,5"
        val schedule = service.onAccepted(ProblemId("p-1"))
        assertEquals(ReviewService.DEFAULT_INTERVALS.map { today0() + it * day }, schedule.planDates)
    }

    /** 内存假件:排期行按 problemId 存取。 */
    private class FakeStore : ReviewScheduleStore {
        val rows = LinkedHashMap<String, ReviewSchedule>()

        override suspend fun findByProblem(problemId: ProblemId): ReviewSchedule? = rows[problemId.value]

        override suspend fun upsert(record: ReviewSchedule) {
            rows[record.problemId.value] = record
        }

        override suspend fun listAll(): List<ReviewSchedule> = rows.values.toList()
    }
}
