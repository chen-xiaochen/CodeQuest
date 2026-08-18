package com.codequest.platform.hackerrank

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP27:HackerRankSubmissionService——语言校验/提交(CSRF + JSON body
 * 逐字)/经 BaseSubmissionFlow 的轮询状态转换(Processing→终态)与 3s 固定
 * 间隔、超时与上下文丢失。经 FakeOJTransport + 假 delay,不上网络。
 */
class HackerRankSubmissionServiceTest {
    private fun transport(): FakeOJTransport = FakeOJTransport()

    private fun service(
        transport: FakeOJTransport,
        delays: MutableList<Long>? = null,
        maxAttempts: Int = 20,
    ): HackerRankSubmissionService =
        HackerRankSubmissionService(
            api = HackerRankApi(transport),
            pollConfig =
                BaseSubmissionFlow.PollConfig(
                    initialIntervalMs = HackerRankSubmissionService.POLL_INTERVAL_MS,
                    maxIntervalMs = HackerRankSubmissionService.POLL_INTERVAL_MS,
                    backoffMultiplier = 1.0,
                    maxAttempts = maxAttempts,
                ),
            delayFn = { ms -> delays?.add(ms) },
        )

    private fun scriptSubmit(transport: FakeOJTransport) {
        transport.handler = { request ->
            when (request.url) {
                "https://www.hackerrank.com/challenges/solve-me-first/problem" -> problemHtml()
                "https://www.hackerrank.com/rest/contests/master/challenges/solve-me-first/submissions" ->
                    """{"model":{"id":73920325}}"""
                else -> throw OJException("unexpected ${request.method} ${request.url}")
            }
        }
    }

    @Test
    fun `submit validates language posts legacy body and returns handle`() = runBlocking {
        val transport = transport()
        scriptSubmit(transport)
        val submissionService = service(transport)

        val handle = submissionService.submit(SubmitRequest("solve-me-first", "python3", "print(1)"))

        assertEquals("hackerrank", handle.platformId.value)
        assertEquals("73920325", handle.remoteSubmissionId)
        val submitCall = transport.recorded.single { it.url.endsWith("/submissions") && it.method == "POST" }
        assertEquals(
            """{"language":"python3","contest_slug":"master","challenge_slug":"solve-me-first","code":"print(1)"}""",
            submitCall.body,
        )
        assertEquals("csrf-1", submitCall.headers["x-csrf-token"])
        // CSRF 取自题目页(旧 getChallengeCSRFToken 事实)
        assertTrue(transport.recorded.any { it.method == "GET" && it.url.endsWith("/challenges/solve-me-first/problem") })
    }

    @Test
    fun `submit rejects unknown language id`() {
        val submissionService = service(transport())
        assertFailsWith<OJException> {
            runBlocking { submissionService.submit(SubmitRequest("solve-me-first", "coffeescript", "x")) }
        }
    }

    @Test
    fun `watch transitions judging to accepted with legacy 3s interval`() = runBlocking {
        val transport = transport()
        val delays = mutableListOf<Long>()
        var poll = 0
        transport.handler = { request ->
            when (request.url) {
                "https://www.hackerrank.com/challenges/solve-me-first/problem" -> problemHtml()
                "https://www.hackerrank.com/rest/contests/master/challenges/solve-me-first/submissions" ->
                    """{"model":{"id":73920325}}"""
                "https://www.hackerrank.com/rest/contests/master/challenges/solve-me-first/submissions/73920325" -> {
                    poll++
                    if (poll < 3) """{"model":{"id":73920325,"status":"Processing","score_processed":0}}"""
                    else """{"model":{"id":73920325,"status":"Accepted","score_processed":3}}"""
                }
                else -> throw OJException("unexpected ${request.url}")
            }
        }
        val submissionService = service(transport, delays)
        val handle = submissionService.submit(SubmitRequest("solve-me-first", "python3", "print(1)"))

        val results = submissionService.watchSubmission(handle).toList()

        assertEquals(
            listOf(
                com.codequest.core.model.SubmissionResult.Judging,
                com.codequest.core.model.SubmissionResult.Judging,
                com.codequest.core.model.SubmissionResult.Accepted,
            ),
            results,
        )
        // 旧实现固定 3s 间隔(Temporal.sleep(3.second))
        assertEquals(listOf(3_000L, 3_000L), delays)
    }

    @Test
    fun `watch times out when never reaching score processed 3`() = runBlocking {
        val transport = transport()
        transport.handler = { request ->
            when (request.url) {
                "https://www.hackerrank.com/challenges/solve-me-first/problem" -> problemHtml()
                "https://www.hackerrank.com/rest/contests/master/challenges/solve-me-first/submissions" ->
                    """{"model":{"id":1}}"""
                else -> """{"model":{"id":1,"status":"Processing","score_processed":0}}"""
            }
        }
        val submissionService = service(transport, maxAttempts = 2)
        val handle = submissionService.submit(SubmitRequest("solve-me-first", "python3", "print(1)"))

        assertFailsWith<SubmissionTimeoutException> {
            runBlocking { submissionService.watchSubmission(handle).toList() }
        }
    }

    @Test
    fun `polling unknown handle without submit context maps to OJException`() {
        val submissionService = service(transport())
        val orphan = SubmissionHandle(HACKERRANK_PLATFORM_ID, "999")
        assertFailsWith<OJException> {
            runBlocking { submissionService.watchSubmission(orphan).toList() }
        }
    }

    private fun problemHtml(): String {
        val detail =
            """{"community":{"challenges":{"challenge":{"master/solve-me-first":{"detail":{
               "id":44,"slug":"solve-me-first","name":"Solve Me First","contest_slug":"master",
               "difficulty_name":"easy","max_score":10,"body_html":"<p>body</p>"
               }}}}}}""".trimIndent().replace("\n", "")
        return """<html><head><meta id="csrf-token" content="csrf-1"></head><body>
               <script id="initialData" type="application/json">${urlEncode(detail)}</script>
               </body></html>""".trimIndent().replace("\n", "")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
