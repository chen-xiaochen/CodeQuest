package com.codequest.infra.db

import com.codequest.core.model.ProblemId
import com.codequest.core.model.ReviewSchedule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [integration] ExposedReviewRepository 集成测试(WP59):epoch 数组 JSON 往返 + upsert 覆盖。 */
class ReviewRepositoryIntegrationTest {
    private fun schedule(
        problemId: String,
        planDates: List<Long>,
        historyDates: List<Long> = emptyList(),
    ) = ReviewSchedule(
        problemId = ProblemId(problemId),
        planDates = planDates,
        historyDates = historyDates,
        updatedAt = Instant.parse("2026-10-02T08:00:00Z"),
    )

    @Test
    fun `upsert and findByProblem round trips epoch arrays`() = integrationTest { db ->
        val repo = ExposedReviewRepository(db)
        val record =
            schedule(
                "p-1",
                planDates = listOf(1759363200L, 1759622400L, 1760139600L),
                historyDates = listOf(1759276800L),
            )
        repo.upsert(record)
        assertEquals(record, repo.findByProblem(ProblemId("p-1")))
    }

    @Test
    fun `upsert replaces existing schedule`() = integrationTest { db ->
        val repo = ExposedReviewRepository(db)
        repo.upsert(schedule("p-1", planDates = listOf(1759363200L, 1759622400L)))
        // 复习完成:移除今天及过去、history 追加
        repo.upsert(schedule("p-1", planDates = listOf(1760226000L), historyDates = listOf(1759363200L)))
        val reloaded = repo.findByProblem(ProblemId("p-1"))
        assertEquals(listOf(1760226000L), reloaded?.planDates)
        assertEquals(listOf(1759363200L), reloaded?.historyDates)
    }

    @Test
    fun `findByProblem returns null when missing`() = integrationTest { db ->
        val repo = ExposedReviewRepository(db)
        assertNull(repo.findByProblem(ProblemId("nope")))
    }

    @Test
    fun `listAll returns every schedule`() = integrationTest { db ->
        val repo = ExposedReviewRepository(db)
        repo.upsert(schedule("p-1", planDates = listOf(1759363200L)))
        repo.upsert(schedule("p-2", planDates = emptyList(), historyDates = listOf(1759276800L)))
        val all = repo.listAll()
        assertEquals(2, all.size)
        assertTrue(ProblemId("p-1") in all.map { it.problemId })
        assertTrue(all.any { it.problemId == ProblemId("p-2") && it.planDates.isEmpty() })
    }
}
