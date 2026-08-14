package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Solution
import com.codequest.core.model.SolutionId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId
import com.codequest.core.model.TestCaseSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [integration] SolutionRepository + TestCaseRepository 集成测试(WP14)。 */
class SolutionAndTestCaseIntegrationTest {
    private val fixedInstant = Instant.parse("2026-08-14T09:30:00.5Z")

    private fun solution(id: String, challengeId: String = "c-1") = Solution(
        id = SolutionId(id),
        challengeId = ChallengeId(challengeId),
        name = "解法一",
        noteMd = "## 思路\n双指针从两端向中间收缩。",
        filePath = "solutions/$id.kt",
        createdAt = fixedInstant,
    )

    private fun testCase(id: String, challengeId: String = "c-1") = TestCase(
        id = TestCaseId(id),
        challengeId = ChallengeId(challengeId),
        input = "3\n1 2 3",
        expected = "6",
        source = TestCaseSource.USER,
    )

    @Test
    fun `solution save find upsert delete`() = integrationTest { db ->
        val repo = ExposedSolutionRepository(db)
        val sol = solution("s-1")
        repo.save(sol)
        assertEquals(sol, repo.findById(SolutionId("s-1")))

        repo.save(sol.copy(name = "解法一(优化)"))
        assertEquals("解法一(优化)", repo.findById(SolutionId("s-1"))?.name)
        assertEquals(1, repo.findByChallenge(ChallengeId("c-1")).size)

        assertTrue(repo.delete(SolutionId("s-1")))
        assertFalse(repo.delete(SolutionId("s-1")))
        assertNull(repo.findById(SolutionId("s-1")))
    }

    @Test
    fun `solution find by challenge scopes rows`() = integrationTest { db ->
        val repo = ExposedSolutionRepository(db)
        repo.save(solution("s-1", "c-1"))
        repo.save(solution("s-2", "c-1"))
        repo.save(solution("s-3", "c-2"))

        assertEquals(setOf("s-1", "s-2"), repo.findByChallenge(ChallengeId("c-1")).map { it.id.value }.toSet())
    }

    @Test
    fun `test case save find upsert delete`() = integrationTest { db ->
        val repo = ExposedTestCaseRepository(db)
        val tc = testCase("tc-1")
        repo.save(tc)
        assertEquals(tc, repo.findById(TestCaseId("tc-1")))

        repo.save(tc.copy(source = TestCaseSource.PLATFORM))
        assertEquals(TestCaseSource.PLATFORM, repo.findById(TestCaseId("tc-1"))?.source)

        assertTrue(repo.delete(TestCaseId("tc-1")))
        assertFalse(repo.delete(TestCaseId("tc-1")))
        assertTrue(repo.findByChallenge(ChallengeId("c-1")).isEmpty())
    }
}
