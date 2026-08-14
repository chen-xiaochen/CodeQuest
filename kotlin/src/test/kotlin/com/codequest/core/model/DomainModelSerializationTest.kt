package com.codequest.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * 领域模型 JSON 序列化往返单测(WP13)。
 *
 * 每个模型 encode → decode 后必须与原对象相等;这些签名是 WP14(数据层映射)
 * 与 WP16(工作区)的契约,字段增删都会在此暴露。
 */
class DomainModelSerializationTest {
    private val json = Json { encodeDefaults = true }

    private val fixedInstant = Instant.parse("2026-08-14T12:34:56.789Z")

    private fun <T> assertRoundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val encoded = json.encodeToString(serializer, value)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(value, decoded, "序列化往返后不相等,原文: $value, 编码: $encoded")
    }

    @Test
    fun `problem round trip`() {
        val problem = Problem(
            id = ProblemId("p-1"),
            platformId = PlatformId("codeforces"),
            remoteId = "1927A",
            title = "Make it White",
            difficulty = Difficulty("easy"),
            url = "https://codeforces.com/problemset/problem/1927/A",
            tags = listOf(Tag(TagId("t-1"), "greedy"), Tag(TagId("t-2"), "implementation")),
            fetchedAt = fixedInstant,
        )
        assertRoundTrip(Problem.serializer(), problem)
    }

    @Test
    fun `problem detail round trip`() {
        val detail = ProblemDetail(
            problemId = ProblemId("p-1"),
            contentMd = "# 题面\n给定 \$n\$ 个整数…",
            contentFormat = ContentFormat.MARKDOWN,
        )
        assertRoundTrip(ProblemDetail.serializer(), detail)

        val htmlDetail = detail.copy(contentFormat = ContentFormat.HTML)
        assertRoundTrip(ProblemDetail.serializer(), htmlDetail)
    }

    @Test
    fun `challenge round trip`() {
        val challenge = Challenge(
            id = ChallengeId("c-1"),
            problemId = ProblemId("p-1"),
            name = "Make it White - 尝试1",
            createdAt = fixedInstant,
        )
        assertRoundTrip(Challenge.serializer(), challenge)
    }

    @Test
    fun `challenge language round trip`() {
        val language = ChallengeLanguage(
            id = ChallengeLanguageId("cl-1"),
            challengeId = ChallengeId("c-1"),
            language = "kotlin",
            languageVersion = "2.4",
            template = "fun main() {\n    // TODO\n}",
            entryFilePath = "src/Main.kt",
        )
        assertRoundTrip(ChallengeLanguage.serializer(), language)
    }

    @Test
    fun `solution round trip`() {
        val solution = Solution(
            id = SolutionId("s-1"),
            challengeId = ChallengeId("c-1"),
            name = "双指针解法",
            noteMd = "## 思路\n维护一个窗口…",
            filePath = "solutions/two-pointers.kt",
            createdAt = fixedInstant,
        )
        assertRoundTrip(Solution.serializer(), solution)
    }

    @Test
    fun `test case round trip for both sources`() {
        val userCase = TestCase(
            id = TestCaseId("tc-1"),
            challengeId = ChallengeId("c-1"),
            input = "3\n1 2 3",
            expected = "6",
            source = TestCaseSource.USER,
        )
        assertRoundTrip(TestCase.serializer(), userCase)

        val platformCase = userCase.copy(id = TestCaseId("tc-2"), source = TestCaseSource.PLATFORM)
        assertRoundTrip(TestCase.serializer(), platformCase)
    }

    @Test
    fun `submission round trip with verdict variants`() {
        val base = Submission(
            id = SubmissionId("sub-1"),
            challengeId = ChallengeId("c-1"),
            platformId = PlatformId("codeforces"),
            remoteSubmissionId = "284567890",
            language = "Kotlin 2.1",
            codeSnapshot = "fun main() { println(42) }",
            verdict = SubmissionResult.Accepted,
            timeMs = 171,
            memoryKb = 32768,
            submittedAt = fixedInstant,
        )
        assertRoundTrip(Submission.serializer(), base)

        val pending = base.copy(
            id = SubmissionId("sub-2"),
            verdict = SubmissionResult.Judging,
            timeMs = null,
            memoryKb = null,
        )
        assertRoundTrip(Submission.serializer(), pending)

        val partial = base.copy(
            id = SubmissionId("sub-3"),
            verdict = SubmissionResult.PartialScore(score = 0.5),
        )
        assertRoundTrip(Submission.serializer(), partial)
    }

    @Test
    fun `contest round trip with all phases`() {
        ContestPhase.entries.forEach { phase ->
            val contest = Contest(
                id = ContestId("ct-1"),
                platformId = PlatformId("codeforces"),
                remoteId = "1901",
                title = "Codeforces Round 913",
                startTime = fixedInstant,
                durationMinutes = 120,
                phase = phase,
            )
            assertRoundTrip(Contest.serializer(), contest)
        }
    }

    @Test
    fun `contest reminder round trip`() {
        val reminder = ContestReminder(
            contestId = ContestId("ct-1"),
            remindAt = fixedInstant,
            notified = false,
        )
        assertRoundTrip(ContestReminder.serializer(), reminder)

        val notified = reminder.copy(notified = true)
        assertRoundTrip(ContestReminder.serializer(), notified)
    }
}
