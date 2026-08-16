package com.codequest.ui.submission

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.model.ChallengeLanguageId
import com.codequest.core.model.ProblemId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] WP21:编辑器文件 → challenge 工作区定位(纯路径匹配,不依赖 IDE)。
 */
class WorkspaceTargetResolverTest {
    @TempDir
    lateinit var temp: Path

    private val fixedInstant = Instant.parse("2026-08-15T08:00:00Z")

    private fun challenge(id: String) = Challenge(ChallengeId(id), ProblemId("p-$id"), "题-$id", fixedInstant)

    private fun language(
        id: String,
        challengeId: String,
        lang: String,
        entry: String,
    ) = ChallengeLanguage(
        id = ChallengeLanguageId(id),
        challengeId = ChallengeId(challengeId),
        language = lang,
        languageVersion = "",
        template = "",
        entryFilePath = entry,
    )

    private fun rootFor(challenge: Challenge): Path = temp.resolve("ws-${challenge.id.value}")

    private fun file(vararg parts: String): Path {
        val path = temp.resolve(parts.joinToString("/"))
        Files.createDirectories(path.parent)
        Files.createFile(path)
        return path
    }

    @Test
    fun `resolves entry file to its challenge and language`() {
        val c1 = challenge("c1")
        val lang = language("l1", "c1", "kotlin", "TwoSum.kt")
        val entry = file("ws-c1/TwoSum.kt")

        val resolved =
            WorkspaceTargetResolver.resolve(
                filePath = entry,
                challenges = listOf(c1 to listOf(lang)),
                rootFor = ::rootFor,
            )

        assertEquals(c1, resolved?.challenge)
        assertEquals(lang, resolved?.language)
    }

    @Test
    fun `resolves solution file by extension when path does not match any entry`() {
        val c1 = challenge("c1")
        val lang = language("l1", "c1", "kotlin", "TwoSum.kt")
        val solution = file("ws-c1/solutions/Alt.kt")

        val resolved =
            WorkspaceTargetResolver.resolve(solution, listOf(c1 to listOf(lang)), ::rootFor)

        assertEquals(lang, resolved?.language)
    }

    @Test
    fun `returns null for files outside any workspace`() {
        val c1 = challenge("c1")
        val outside = file("other/TwoSum.kt")

        assertNull(WorkspaceTargetResolver.resolve(outside, listOf(c1 to listOf(language("l1", "c1", "kotlin", "TwoSum.kt"))), ::rootFor))
    }

    @Test
    fun `entry path match wins over extension match`() {
        val c1 = challenge("c1")
        val kotlinEntry = language("l1", "c1", "kotlin", "Main.kt")
        val javaEntry = language("l2", "c1", "java", "Helper.kt")
        val entryFile = file("ws-c1/Main.kt")

        val resolved = WorkspaceTargetResolver.resolve(entryFile, listOf(c1 to listOf(kotlinEntry, javaEntry)), ::rootFor)

        assertEquals(kotlinEntry, resolved?.language)
    }

    @Test
    fun `single language is the fallback for unmatched files inside the workspace`() {
        val c1 = challenge("c1")
        val lang = language("l1", "c1", "cpp", "main.cc")
        val stray = file("ws-c1/notes/sketch.txt")

        val resolved = WorkspaceTargetResolver.resolve(stray, listOf(c1 to listOf(lang)), ::rootFor)

        assertEquals(lang, resolved?.language)
    }

    @Test
    fun `ambiguous multi language workspace without entry or extension match returns null`() {
        val c1 = challenge("c1")
        val kotlinEntry = language("l1", "c1", "kotlin", "Main.kt")
        val cppEntry = language("l2", "c1", "cpp", "main.cc")
        val stray = file("ws-c1/notes/sketch.txt")

        assertNull(WorkspaceTargetResolver.resolve(stray, listOf(c1 to listOf(kotlinEntry, cppEntry)), ::rootFor))
    }

    @Test
    fun `solution file resolves by extension even with multiple languages`() {
        val c1 = challenge("c1")
        val kotlinEntry = language("l1", "c1", "kotlin", "Main.kt")
        val cppEntry = language("l2", "c1", "cpp", "main.cc")
        val solution = file("ws-c1/solutions/Alt.kt")

        val resolved = WorkspaceTargetResolver.resolve(solution, listOf(c1 to listOf(kotlinEntry, cppEntry)), ::rootFor)

        assertEquals(kotlinEntry, resolved?.language)
    }
}
