package com.codequest.infra.db

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.model.ChallengeLanguageId
import com.codequest.core.model.ProblemId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [integration] ChallengeRepository(主档 + 语言配置)集成测试(WP14)。 */
class ChallengeRepositoryIntegrationTest {
    private val fixedInstant = Instant.parse("2026-08-14T10:00:00Z")

    private fun challenge(
        id: String,
        problemId: String = "p-1",
        createdAt: Instant = fixedInstant,
    ) = Challenge(ChallengeId(id), ProblemId(problemId), "工作区-$id", createdAt)

    private fun language(id: String, challengeId: String) = ChallengeLanguage(
        id = ChallengeLanguageId(id),
        challengeId = ChallengeId(challengeId),
        language = "kotlin",
        languageVersion = "2.4",
        template = "fun main() {}",
        entryFilePath = "src/Main.kt",
    )

    @Test
    fun `save find and upsert challenge`() = integrationTest { db ->
        val repo = ExposedChallengeRepository(db)
        repo.save(challenge("c-1"))
        assertEquals(challenge("c-1"), repo.findById(ChallengeId("c-1")))

        repo.save(challenge("c-1", createdAt = fixedInstant.plusSeconds(60)))
        assertEquals(fixedInstant.plusSeconds(60), repo.findById(ChallengeId("c-1"))?.createdAt)
    }

    @Test
    fun `find by problem returns all workspaces`() = integrationTest { db ->
        val repo = ExposedChallengeRepository(db)
        repo.save(challenge("c-1", problemId = "p-1"))
        repo.save(challenge("c-2", problemId = "p-1"))
        repo.save(challenge("c-3", problemId = "p-2"))

        assertEquals(
            setOf("c-1", "c-2"),
            repo.findByProblem(ProblemId("p-1")).map { it.id.value }.toSet(),
        )
    }

    @Test
    fun `delete removes workspace and cascades languages`() = integrationTest { db ->
        val repo = ExposedChallengeRepository(db)
        repo.save(challenge("c-1"))
        repo.saveLanguage(language("l-1", "c-1"))
        repo.saveLanguage(language("l-2", "c-1"))

        assertTrue(repo.delete(ChallengeId("c-1")))
        assertFalse(repo.delete(ChallengeId("c-1"))) // 二次删除返回 false
        assertNull(repo.findById(ChallengeId("c-1")))
        assertTrue(repo.findLanguages(ChallengeId("c-1")).isEmpty())
    }

    @Test
    fun `language save find and delete`() = integrationTest { db ->
        val repo = ExposedChallengeRepository(db)
        repo.save(challenge("c-1"))
        val lang = language("l-1", "c-1")
        repo.saveLanguage(lang)
        assertEquals(listOf(lang), repo.findLanguages(ChallengeId("c-1")))

        repo.saveLanguage(lang.copy(template = "fun main() { /* v2 */ }"))
        assertEquals(1, repo.findLanguages(ChallengeId("c-1")).size)
        assertEquals("fun main() { /* v2 */ }", repo.findLanguages(ChallengeId("c-1")).first().template)

        assertTrue(repo.deleteLanguage(ChallengeLanguageId("l-1")))
        assertFalse(repo.deleteLanguage(ChallengeLanguageId("l-1")))
        assertTrue(repo.findLanguages(ChallengeId("c-1")).isEmpty())
    }
}
