package com.codequest.infra.db

import com.codequest.core.model.ProblemRating
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** [integration] ExposedRatingRepository 集成测试(WP59):upsert 覆盖语义 + loadAll/count。 */
class RatingRepositoryIntegrationTest {
    private fun rating(
        frontendId: String,
        value: Double,
        updatedAt: Instant = Instant.parse("2026-10-02T00:00:00Z"),
    ) = ProblemRating(frontendId = frontendId, rating = value, updatedAt = updatedAt)

    @Test
    fun `upsertAll and loadAll round trips`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        repo.upsertAll(
            listOf(
                rating("1", 1317.5),
                rating("42", 1899.25),
                rating("239", 2600.0),
            ),
        )
        assertEquals(mapOf("1" to 1317.5, "42" to 1899.25, "239" to 2600.0), repo.loadAll())
        assertEquals(3L, repo.count())
    }

    @Test
    fun `upsertAll overwrites same frontend id`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        repo.upsertAll(listOf(rating("1", 1317.5), rating("2", 1500.0)))
        repo.upsertAll(
            listOf(
                rating("1", 1400.0),
                rating("3", 1200.0),
            ),
        )
        // 覆盖语义:同 frontend_id 取最新值,既有其余行保留,无重复行
        assertEquals(mapOf("1" to 1400.0, "2" to 1500.0, "3" to 1200.0), repo.loadAll())
        assertEquals(3L, repo.count())
    }

    @Test
    fun `empty database loads empty map`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        assertEquals(emptyMap(), repo.loadAll())
        assertEquals(0L, repo.count())
    }

    @Test
    fun `upsertAll with empty list is a no-op`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        repo.upsertAll(listOf(rating("1", 1317.5)))
        repo.upsertAll(emptyList())
        assertEquals(mapOf("1" to 1317.5), repo.loadAll())
    }
}
