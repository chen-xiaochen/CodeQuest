package com.codequest.infra.db

import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [integration] TagRepository + ContestReminderRepository 集成测试(WP14)。 */
class TagAndContestReminderIntegrationTest {
    @Test
    fun `tag save find by name and find all`() = integrationTest { db ->
        val repo = ExposedTagRepository(db)
        repo.save(Tag(TagId("t-2"), "dp"))
        repo.save(Tag(TagId("t-1"), "greedy"))

        assertEquals(Tag(TagId("t-2"), "dp"), repo.findByName("dp"))
        assertNull(repo.findByName("missing"))
        assertEquals(Tag(TagId("t-1"), "greedy"), repo.findById(TagId("t-1")))
        assertEquals(listOf("dp", "greedy"), repo.findAll().map { it.name })

        repo.save(Tag(TagId("t-1"), "greedy-v2")) // upsert 同 id
        assertEquals("greedy-v2", repo.findById(TagId("t-1"))?.name)
    }

    @Test
    fun `tag find by problem follows associations`() = integrationTest { db ->
        val problemRepo = ExposedProblemRepository(db)
        val tagRepo = ExposedTagRepository(db)
        val greedy = Tag(TagId("t-1"), "greedy")
        val dp = Tag(TagId("t-2"), "dp")
        problemRepo.save(
            com.codequest.core.model.Problem(
                id = ProblemId("p-1"),
                platformId = com.codequest.core.model.PlatformId("codeforces"),
                remoteId = "1A",
                title = "T",
                difficulty = com.codequest.core.model.Difficulty("easy"),
                url = "u",
                tags = listOf(greedy, dp),
                fetchedAt = Instant.EPOCH,
            ),
        )

        assertEquals(setOf("greedy", "dp"), tagRepo.findByProblem(ProblemId("p-1")).map { it.name }.toSet())
        assertTrue(tagRepo.findByProblem(ProblemId("p-2")).isEmpty())
    }

    @Test
    fun `reminder upsert keyed by contest id keeps single row`() = integrationTest { db ->
        val repo = ExposedContestReminderRepository(db)
        val at = Instant.parse("2026-08-15T18:00:00Z")
        repo.save(ContestReminder(ContestId("ct-1"), at, notified = false))
        repo.save(ContestReminder(ContestId("ct-1"), at.plusSeconds(600), notified = false))
        repo.save(ContestReminder(ContestId("ct-2"), at.plusSeconds(1200), notified = false))

        val rows = db.queryLong("SELECT COUNT(*) FROM contest_reminder")
        assertEquals(2L, rows)
        assertEquals(at.plusSeconds(600), repo.findByContest(ContestId("ct-1"))?.remindAt)
    }

    @Test
    fun `find due filters notified and future`() = integrationTest { db ->
        val repo = ExposedContestReminderRepository(db)
        val now = Instant.parse("2026-08-15T12:00:00Z")
        repo.save(ContestReminder(ContestId("ct-due"), now.minusSeconds(60), notified = false))
        repo.save(ContestReminder(ContestId("ct-now"), now, notified = false)) // 恰好等于 now 也算到期
        repo.save(ContestReminder(ContestId("ct-future"), now.plusSeconds(3600), notified = false))
        repo.save(ContestReminder(ContestId("ct-notified"), now.minusSeconds(60), notified = true))

        val due = repo.findDue(now)
        assertEquals(setOf("ct-due", "ct-now"), due.map { it.contestId.value }.toSet())
    }

    @Test
    fun `mark notified and delete`() = integrationTest { db ->
        val repo = ExposedContestReminderRepository(db)
        val now = Instant.parse("2026-08-15T12:00:00Z")
        repo.save(ContestReminder(ContestId("ct-1"), now.minusSeconds(1), notified = false))

        repo.markNotified(ContestId("ct-1"))
        assertTrue(repo.findByContest(ContestId("ct-1"))?.notified == true)
        assertTrue(repo.findDue(now).isEmpty())

        assertTrue(repo.delete(ContestId("ct-1")))
        assertFalse(repo.delete(ContestId("ct-1")))
        assertNull(repo.findByContest(ContestId("ct-1")))
    }
}
