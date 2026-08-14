package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [integration] SubmissionRepository 集成测试(WP14):判定 JSON 列往返 + 时间倒序。 */
class SubmissionRepositoryIntegrationTest {
    private fun submission(
        id: String,
        challengeId: String = "c-1",
        submittedAt: Instant = Instant.parse("2026-08-14T12:00:00Z"),
        verdict: SubmissionResult = SubmissionResult.Accepted,
    ) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId(challengeId),
        platformId = PlatformId("codeforces"),
        remoteSubmissionId = "3000000$id",
        language = "Kotlin 2.1",
        codeSnapshot = "fun main() { readLine() }",
        verdict = verdict,
        timeMs = 171,
        memoryKb = 20480,
        submittedAt = submittedAt,
    )

    @Test
    fun `save and find by id round trips all verdict types`() = integrationTest { db ->
        val repo = ExposedSubmissionRepository(db)
        val verdicts: List<SubmissionResult> = listOf(
            SubmissionResult.Pending,
            SubmissionResult.Judging,
            SubmissionResult.Accepted,
            SubmissionResult.WrongAnswer,
            SubmissionResult.TimeLimitExceeded,
            SubmissionResult.MemoryLimitExceeded,
            SubmissionResult.OutputLimitExceeded,
            SubmissionResult.RuntimeError,
            SubmissionResult.CompileError,
            SubmissionResult.PartialScore(0.42),
            SubmissionResult.Unknown("IDIOTIC_VERDICT_17"),
        )
        verdicts.forEachIndexed { index, verdict ->
            val sub = submission("s-$index", verdict = verdict)
            repo.save(sub)
            assertEquals(verdict, repo.findById(SubmissionId("s-$index"))?.verdict, "判定 $verdict 往返失败")
        }
    }

    @Test
    fun `save and find with null metrics`() = integrationTest { db ->
        val repo = ExposedSubmissionRepository(db)
        val sub = submission("s-1", verdict = SubmissionResult.Pending).copy(timeMs = null, memoryKb = null)
        repo.save(sub)
        assertEquals(sub, repo.findById(SubmissionId("s-1")))
    }

    @Test
    fun `save upserts same id`() = integrationTest { db ->
        val repo = ExposedSubmissionRepository(db)
        repo.save(submission("s-1", verdict = SubmissionResult.Pending))
        repo.save(submission("s-1", verdict = SubmissionResult.Accepted))
        val loaded = repo.findById(SubmissionId("s-1"))
        assertEquals(SubmissionResult.Accepted, loaded?.verdict)
    }

    @Test
    fun `find by challenge returns submissions newest first`() = integrationTest { db ->
        val repo = ExposedSubmissionRepository(db)
        repo.save(submission("s-old", submittedAt = Instant.parse("2026-08-14T10:00:00Z")))
        repo.save(submission("s-new", submittedAt = Instant.parse("2026-08-14T12:00:00Z")))
        repo.save(submission("s-mid", submittedAt = Instant.parse("2026-08-14T11:00:00Z")))
        repo.save(submission("s-other", challengeId = "c-2"))

        assertEquals(
            listOf("s-new", "s-mid", "s-old"),
            repo.findByChallenge(ChallengeId("c-1")).map { it.id.value },
        )
    }

    @Test
    fun `find by id returns null when missing`() = integrationTest { db ->
        val repo = ExposedSubmissionRepository(db)
        assertNull(repo.findById(SubmissionId("nope")))
    }
}
