package com.codequest.ui.submission

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [unit] WP65:`submission:updated` 桥多播与 payload 增补——
 * 原单槽改多播(submissions 页刷新/题面页计时器同订阅),既有字段不动。
 */
class SubmissionCoordinatorTest {
    private val fixedInstant = Instant.parse("2026-10-02T08:00:00Z")

    private fun acceptedSubmission() =
        Submission(
            id = SubmissionId("sub-1"),
            challengeId = ChallengeId("ch-1"),
            platformId = PlatformId("leetcode"),
            remoteSubmissionId = "rs-1",
            language = "Kotlin 2.1",
            codeSnapshot = "fun main() {}",
            verdict = SubmissionResult.Accepted,
            timeMs = 4,
            memoryKb = 256,
            submittedAt = fixedInstant,
        )

    private fun problem() =
        Problem(
            id = ProblemId("leetcode:two-sum"),
            platformId = PlatformId("leetcode"),
            remoteId = "two-sum",
            title = "Two Sum",
            difficulty = Difficulty("easy"),
            url = "https://leetcode.com/problems/two-sum/",
            tags = emptyList(),
            fetchedAt = fixedInstant,
        )

    /** 记录型假桥发送器(等价 attachSubmissionsBridge 包出的 bridge::postMessage)。 */
    private class RecordingSender : (String, String) -> Unit {
        val messages = mutableListOf<Pair<String, String>>()

        override fun invoke(
            topic: String,
            payload: String,
        ) {
            messages += topic to payload
        }
    }

    // -------------------------------------------------------------------
    // 多播注册表:单注册收一次/多注册各一次/detach 后不再收/幂等挂接
    // -------------------------------------------------------------------

    @Test
    fun `single registration receives exactly once`() {
        val registry = SubmissionsBridgeRegistry()
        val sender = RecordingSender()
        registry.attach(Any(), sender)

        registry.broadcast(SubmissionCoordinator.TOPIC_SUBMISSION_UPDATED, """{"a":1}""")

        assertEquals(listOf(SubmissionCoordinator.TOPIC_SUBMISSION_UPDATED to """{"a":1}"""), sender.messages)
    }

    @Test
    fun `multiple registrations each receive once`() {
        val registry = SubmissionsBridgeRegistry()
        val first = RecordingSender()
        val second = RecordingSender()
        registry.attach(Any(), first)
        registry.attach(Any(), second)

        registry.broadcast("t", "p")

        assertEquals(1, first.messages.size)
        assertEquals(1, second.messages.size)
        assertEquals("t" to "p", first.messages.single())
        assertEquals("t" to "p", second.messages.single())
    }

    @Test
    fun `detached sender no longer receives`() {
        val registry = SubmissionsBridgeRegistry()
        val key = Any()
        val detached = RecordingSender()
        val remaining = RecordingSender()
        registry.attach(key, detached)
        registry.attach(Any(), remaining)

        registry.detach(key)
        registry.broadcast("t", "p")

        assertTrue(detached.messages.isEmpty(), "detach 后不再收")
        assertEquals(1, remaining.messages.size, "其余桥不受影响")
    }

    @Test
    fun `attach is idempotent for same key`() {
        val registry = SubmissionsBridgeRegistry()
        val key = Any()
        val sender = RecordingSender()
        registry.attach(key, sender)
        registry.attach(key, sender)

        registry.broadcast("t", "p")

        assertEquals(1, sender.messages.size, "同桥重复挂接不重复收")
    }

    @Test
    fun `broadcast on empty registry is a no-op`() {
        val registry = SubmissionsBridgeRegistry()
        assertTrue(registry.isEmpty)
        registry.broadcast("t", "p") // 不抛异常即通过
        assertTrue(registry.isEmpty)
    }

    @Test
    fun `one failing sender does not block others`() {
        val registry = SubmissionsBridgeRegistry()
        var reported: Throwable? = null
        val healthy = RecordingSender()
        registry.attach(Any()) { _, _ -> throw IllegalStateException("bridge gone") }
        registry.attach(Any(), healthy)

        registry.broadcast("t", "p") { error -> reported = error }

        assertEquals(1, healthy.messages.size, "单桥失败不中断其余桥")
        assertTrue(reported is IllegalStateException)
    }

    // -------------------------------------------------------------------
    // payload:既有字段不动 + WP65 增补 platformId/problemRemoteId
    // -------------------------------------------------------------------

    @Test
    fun `payload keeps existing fields and adds problem identifiers`() {
        val payload = buildSubmissionUpdatedPayload(acceptedSubmission(), "两数之和", problem())
        val element = Json.parseToJsonElement(payload).jsonObject

        assertEquals("ch-1", element["challengeId"]!!.jsonPrimitive.content)
        assertEquals("sub-1", element["submissionId"]!!.jsonPrimitive.content)
        assertEquals("两数之和", element["challengeName"]!!.jsonPrimitive.content)
        assertEquals("leetcode", element["platformId"]!!.jsonPrimitive.content, "WP65 增补:平台标识")
        assertEquals("two-sum", element["problemRemoteId"]!!.jsonPrimitive.content, "WP65 增补:平台侧题目标识")
        assertEquals("Accepted", element["verdict"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(payload.contains("titleSecondary"), "不夹带无关字段")
    }

    @Test
    fun `payload carries non-accepted verdict unchanged`() {
        val submission =
            acceptedSubmission().copy(verdict = SubmissionResult.WrongAnswer)
        val element = Json.parseToJsonElement(buildSubmissionUpdatedPayload(submission, "两数之和", problem())).jsonObject

        assertEquals("WrongAnswer", element["verdict"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("two-sum", element["problemRemoteId"]!!.jsonPrimitive.content)
    }
}
