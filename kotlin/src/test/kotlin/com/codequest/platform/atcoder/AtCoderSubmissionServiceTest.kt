package com.codequest.platform.atcoder

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.AntiBotChallengeHandler
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import com.codequest.spi.TurnstileConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP25:AtCoderSubmissionService——Turnstile 二元组获取(旧常量逐字)、
 * 提交表单字段(旧 UrlForm 逐字事实)、HTML 错误/成功解析、经 BaseSubmissionFlow
 * 的轮询状态转换(WJ/WR→Judging,短码映射)、超时与上下文丢失。
 * 经假 transport(FakeOJTransport 的 postForm 夹具),不上网络。
 */
class AtCoderSubmissionServiceTest {
    /** 可脚本化的反爬处理器:默认返回合法二元组。 */
    private class FakeAntiBot(
        var result: String? = "csrf-1,ts-1",
    ) : AntiBotChallengeHandler {
        val solved = mutableListOf<TurnstileConfig>()

        override suspend fun solve(challenge: TurnstileConfig): String? {
            solved += challenge
            return result
        }
    }

    private val submitOkHtml =
        """
        <div class="container"><table>
          <tr><td class="text-center">2025-01-01</td><td class="submission-score" data-id="65432101">0</td></tr>
        </table></div>
        """.trimIndent()

    private fun statusPageHtml(
        title: String,
        label: String,
    ): String =
        """
        <table><tr><td id="judge-status"><span title="$title" class="label">$label</span></td></tr></table>
        """.trimIndent()

    /** GET(提交详情页)按序脚本;POST(提交)固定成功响应。 */
    private fun transport(statusScript: List<Pair<String, String>>): FakeOJTransport {
        val transport = FakeOJTransport()
        var statusIndex = 0
        transport.formHandler = { record ->
            check(record.url.endsWith("/contests/abc100/submit")) { "unexpected form POST ${record.url}" }
            submitOkHtml
        }
        transport.handler = { request ->
            check(request.method == "GET" && request.url.endsWith("/contests/abc100/submissions/65432101")) {
                "unexpected ${request.method} ${request.url}"
            }
            val (title, label) = statusScript[statusIndex.coerceAtMost(statusScript.size - 1)]
            statusIndex++
            statusPageHtml(title, label)
        }
        return transport
    }

    private fun config(maxAttempts: Int = 10) =
        BaseSubmissionFlow.PollConfig(initialIntervalMs = 100, maxIntervalMs = 100, maxAttempts = maxAttempts)

    private fun service(
        transport: FakeOJTransport,
        antiBot: FakeAntiBot = FakeAntiBot(),
        delays: MutableList<Long> = mutableListOf(),
        maxAttempts: Int = 10,
    ) = AtCoderSubmissionService(
        AtCoderApi(transport),
        antiBotSource = { antiBot },
        pollConfig = config(maxAttempts),
        delayFn = { ms -> delays.add(ms) },
    )

    @Test
    fun `submit solves turnstile posts legacy form fields and wraps submission id`() = runBlocking {
        val transport = transport(statusScript = listOf("Accepted" to "AC"))
        val antiBot = FakeAntiBot()
        val svc = service(transport, antiBot)

        val handle = svc.submit(SubmitRequest("abc100_a", "5005", "fun main() {}"))

        assertEquals("atcoder", handle.platformId.value)
        assertEquals("65432101", handle.remoteSubmissionId)
        // Turnstile 配置为旧 CloudflareTurnstileDialog 常量(pageUrl 随 contest)
        assertEquals(atCoderSubmitTurnstile("abc100"), antiBot.solved.single())
        assertEquals("https://atcoder.jp/contests/abc100/submit", antiBot.solved.single().pageUrl)
        assertEquals("0x4AAAAAAA6HJUmmLP7mLxx0", antiBot.solved.single().sitekey)
        assertEquals("div.cf-challenge", antiBot.solved.single().challengeSelector)
        // 提交表单字段:旧 UrlForm 逐字事实
        val form = transport.forms.single().form
        assertEquals("abc100_a", form["data.TaskScreenName"])
        assertEquals("5005", form["data.LanguageId"])
        assertEquals("fun main() {}", form["sourceCode"])
        assertEquals("csrf-1", form["csrf_token"])
        assertEquals("ts-1", form["cf-turnstile-response"])
    }

    @Test
    fun `submit rejects unsupported language and invalid remote id`() {
        assertFailsWith<OJException> {
            runBlocking { service(transport(emptyList())).submit(SubmitRequest("abc100_a", "cobol", "x")) }
        }
        assertFailsWith<OJException> {
            runBlocking { service(transport(emptyList())).submit(SubmitRequest("no-underscore", "5005", "x")) }
        }
    }

    @Test
    fun `cancelled antibot throws AntiBotChallengeException`() {
        val svc = service(transport(emptyList()), antiBot = FakeAntiBot(result = null))
        assertFailsWith<AntiBotChallengeException> {
            runBlocking { svc.submit(SubmitRequest("abc100_a", "5005", "x")) }
        }
    }

    @Test
    fun `malformed turnstile payload throws AntiBotChallengeException`() {
        val svc = service(transport(emptyList()), antiBot = FakeAntiBot(result = "csrf-only"))
        assertFailsWith<AntiBotChallengeException> {
            runBlocking { svc.submit(SubmitRequest("abc100_a", "5005", "x")) }
        }
    }

    @Test
    fun `submit alert div maps to OJException with page text`() {
        val transport = transport(emptyList())
        transport.formHandler = { record ->
            check(record.url.endsWith("/contests/abc100/submit")) { "unexpected ${record.url}" }
            """<div role="alert" class="alert alert-danger">You have already submitted exactly the same code.</div>"""
        }
        val error =
            assertFailsWith<OJException> {
                runBlocking { service(transport).submit(SubmitRequest("abc100_a", "5005", "x")) }
            }
        assertTrue(error.message!!.contains("same code"))
    }

    @Test
    fun `watchSubmission transitions Judging to terminal via BaseSubmissionFlow`() = runBlocking {
        val delays = mutableListOf<Long>()
        val transport =
            transport(
                statusScript =
                    listOf(
                        "Judging" to "WJ",
                        "Wrong Answer" to "WA",
                    ),
            )
        val svc = service(transport, delays = delays)
        val handle = svc.submit(SubmitRequest("abc100_a", "5005", "code"))

        val results = svc.watchSubmission(handle).toList()

        assertEquals(listOf(SubmissionResult.Judging, SubmissionResult.WrongAnswer), results)
        // 轮询间隔来自 spi 状态机(适配器零自写循环)
        assertEquals(listOf(100L), delays)
        // 轮询 GET 到该 contest 的提交详情页(旧实现 URL 事实)
        val polls = transport.recorded.filter { it.method == "GET" }
        assertTrue(polls.all { it.url.endsWith("/contests/abc100/submissions/65432101") })
        assertEquals(2, polls.size)
    }

    @Test
    fun `judge status mapping covers legacy code table`() {
        // 旧 judgeStatusToSubmissionResult 事实(title/短码 → 平台无关判定)
        assertEquals(SubmissionResult.Accepted, judgeStatusToResult("Accepted", "AC"))
        assertEquals(SubmissionResult.WrongAnswer, judgeStatusToResult("Wrong Answer", "WA"))
        assertEquals(SubmissionResult.TimeLimitExceeded, judgeStatusToResult("TLE", "TLE"))
        assertEquals(SubmissionResult.MemoryLimitExceeded, judgeStatusToResult("MLE", "MLE"))
        assertEquals(SubmissionResult.RuntimeError, judgeStatusToResult("RE", "RE"))
        assertEquals(SubmissionResult.CompileError, judgeStatusToResult("CE", "CE"))
        assertEquals(SubmissionResult.OutputLimitExceeded, judgeStatusToResult("OLE", "OLE"))
        assertEquals(SubmissionResult.Judging, judgeStatusToResult("Judging", "WJ"))
        assertEquals(SubmissionResult.Judging, judgeStatusToResult("Waiting for Judge", "WR"))
        // 与旧实现的差异:无通用 Failure/InternalError,QLE/IE 归 Unknown 保留原文(终态)
        assertEquals(SubmissionResult.Unknown("QLE"), judgeStatusToResult("QLE", "QLE"))
        assertEquals(SubmissionResult.Unknown("IE"), judgeStatusToResult("IE", "IE"))
        assertEquals(SubmissionResult.Unknown("XX"), judgeStatusToResult("XX", "XX"))
    }

    @Test
    fun `exhausted attempts throw SubmissionTimeoutException from state machine`() {
        val transport = transport(statusScript = listOf("Judging" to "WJ"))
        val svc = service(transport, maxAttempts = 3)
        assertFailsWith<SubmissionTimeoutException> {
            runBlocking {
                svc.watchSubmission(svc.submit(SubmitRequest("abc100_a", "5005", "code"))).toList()
            }
        }
    }

    @Test
    fun `watching an unknown handle fails fast with explicit context error`() {
        val transport = transport(statusScript = listOf("Accepted" to "AC"))
        val svc = service(transport)
        val handle =
            SubmissionHandle(
                ATCODER_PLATFORM_ID,
                "88888888", // 未经过本服务 submit,无 contestId 上下文
            )
        val error =
            assertFailsWith<OJException> {
                runBlocking { svc.watchSubmission(handle).toList() }
            }
        assertTrue(error.message!!.contains("contestId"))
    }

    @Test
    fun `remote id parse handles multi char problem index`() {
        // problemIndex 可为多字符(如 APG4b 课程的 cp):按最后一个 `_` 拆分
        assertEquals("APG4b" to "APG4b_cp", parseProblemRemoteId("APG4b_cp"))
        assertEquals("abc100" to "abc100_a", parseProblemRemoteId("abc100_a"))
    }
}
