package com.codequest.platform.codeforces

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.AntiBotChallengeHandler
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmitRequest
import com.codequest.spi.TurnstileConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP24:CodeForcesSubmissionService——Turnstile 四元组获取、提交表单
 * 字段(旧 UrlForm 逐字事实)、HTML 错误/成功解析、经 BaseSubmissionFlow 的
 * 轮询状态转换(verdict 映射)、超时与上下文丢失。经假传输(postForm 夹具,
 * WP25 起为 OJTransport 正式方法),不上网络。
 */
class CodeForcesSubmissionServiceTest {
    /** 可脚本化的反爬处理器:默认返回合法四元组。 */
    private class FakeAntiBot(
        var result: String? = "csrf-1,ts-1,ftaa-1,bfaa-1",
    ) : AntiBotChallengeHandler {
        val solved = mutableListOf<TurnstileConfig>()

        override suspend fun solve(challenge: TurnstileConfig): String? {
            solved += challenge
            return result
        }
    }

    private val submitOkHtml =
        """
        <div class="shiftUp"></div>
        <div class="datatable"><table><tr data-submission-id="291234567"><td>291234567</td></tr></table></div>
        """.trimIndent()

    private fun transport(verdictScript: List<String>): FakeOJTransport {
        val transport = FakeOJTransport()
        var verdictIndex = 0
        transport.formHandler = { record ->
            val url = record.url.substringBefore('?')
            when {
                url.endsWith("/problemset/submit") || url.endsWith("/problemsets/acmsguru/submit") -> submitOkHtml
                url.endsWith("/data/submitSource") -> {
                    val verdict = verdictScript[verdictIndex.coerceAtMost(verdictScript.size - 1)]
                    verdictIndex++
                    verdict
                }
                else -> error("unexpected $url")
            }
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
    ) = CodeForcesSubmissionService(
        CodeForcesApi(transport),
        antiBotSource = { antiBot },
        pollConfig = config(maxAttempts),
        delayFn = { ms -> delays.add(ms) },
    )

    @Test
    fun `submit solves turnstile posts legacy form fields and wraps submission id`() = runBlocking {
        val transport = transport(verdictScript = listOf("""{"verdict":"Accepted"}"""))
        val antiBot = FakeAntiBot()
        val svc = service(transport, antiBot)

        val handle = svc.submit(SubmitRequest("1927A", "89", "fun main() {}"))

        assertEquals("codeforces", handle.platformId.value)
        assertEquals("291234567", handle.remoteSubmissionId)
        // Turnstile 配置为旧 CloudflareTurnstileDialog 常量
        val solved = antiBot.solved.single()
        assertEquals(CODEFORCES_SUBMIT_TURNSTILE, solved)
        // 提交表单字段:旧 UrlForm 逐字事实
        val form = transport.forms.first { it.url.endsWith("/problemset/submit") }.form
        assertEquals("csrf-1", form["csrf_token"])
        assertEquals("ts-1", form["turnstileToken"])
        assertEquals("ts-1", form["cf-turnstile-response"])
        assertEquals("ftaa-1", form["ftaa"])
        assertEquals("bfaa-1", form["bfaa"])
        assertEquals("submitSolutionFormSubmitted", form["action"])
        assertEquals("1927", form["contestId"])
        assertEquals("A", form["submittedProblemIndex"])
        assertEquals("89", form["programTypeId"])
        assertEquals("fun main() {}", form["source"])
        assertEquals("4", form["tabSize"])
        assertEquals("", form["sourceFile"])
        assertEquals("460", form["_tta"])
    }

    @Test
    fun `acmsguru remote id posts to problemsets submit url`() = runBlocking {
        val transport = transport(verdictScript = listOf("""{"verdict":"Accepted"}"""))

        service(transport).submit(SubmitRequest("99999A", "31", "print(1)"))

        assertTrue(transport.forms.single { it.url.endsWith("/submit") }.url.endsWith("/problemsets/acmsguru/submit"))
    }

    @Test
    fun `submit rejects unsupported language and invalid remote id`() {
        assertFailsWith<OJException> {
            runBlocking { service(transport(emptyList())).submit(SubmitRequest("1927A", "cobol", "x")) }
        }
        assertFailsWith<OJException> {
            runBlocking { service(transport(emptyList())).submit(SubmitRequest("not-a-cf-id", "89", "x")) }
        }
    }

    @Test
    fun `cancelled antibot throws AntiBotChallengeException`() {
        val svc = service(transport(emptyList()), antiBot = FakeAntiBot(result = null))
        assertFailsWith<AntiBotChallengeException> {
            runBlocking { svc.submit(SubmitRequest("1927A", "89", "x")) }
        }
    }

    @Test
    fun `malformed turnstile payload throws AntiBotChallengeException`() {
        val svc = service(transport(emptyList()), antiBot = FakeAntiBot(result = "csrf-only"))
        assertFailsWith<AntiBotChallengeException> {
            runBlocking { svc.submit(SubmitRequest("1927A", "89", "x")) }
        }
    }

    @Test
    fun `submit error span maps to OJException with page text`() {
        val transport = transport(emptyList())
        transport.formHandler = { record ->
            if (record.url.endsWith("/problemset/submit")) {
                """<div class="shiftUp"><span class="error for__source">You have submitted exactly the same code before</span></div>"""
            } else {
                error("unexpected ${record.url}")
            }
        }
        val error =
            assertFailsWith<OJException> {
                runBlocking { service(transport).submit(SubmitRequest("1927A", "89", "x")) }
            }
        assertTrue(error.message!!.contains("same code before"))
    }

    @Test
    fun `watchSubmission transitions Judging to terminal via BaseSubmissionFlow`() = runBlocking {
        val delays = mutableListOf<Long>()
        val transport =
            transport(
                verdictScript =
                    listOf(
                        """{"verdict":"Running on test 3"}""",
                        """{"verdict":"Wrong answer on test 5","checkerStdoutAndStderr#1":"expected 1"}""",
                    ),
            )
        val svc = service(transport, delays = delays)
        val handle = svc.submit(SubmitRequest("1927A", "89", "code"))

        val results = svc.watchSubmission(handle).toList()

        assertEquals(listOf(SubmissionResult.Judging, SubmissionResult.WrongAnswer), results)
        // 轮询间隔来自 spi 状态机(适配器零自写循环)
        assertEquals(listOf(100L), delays)
        // 轮询请求:submissionId + csrf_token 字段 + x-csrf-token/Referer 头(旧实现事实)
        val poll = transport.forms.first { it.url.contains("/data/submitSource") }
        assertEquals("291234567", poll.form["submissionId"])
        assertEquals("csrf-1", poll.form["csrf_token"])
        assertEquals("csrf-1", poll.headers["x-csrf-token"])
        assertEquals("https://codeforces.com/contest/1927/my", poll.headers["Referer"])
        assertTrue(poll.url.contains("rv="))
    }

    @Test
    fun `verdict mapping covers legacy result table`() = runBlocking {
        listOf(
            """{"verdict":"Accepted"}""" to SubmissionResult.Accepted,
            """{"verdict":"Compilation error"}""" to SubmissionResult.CompileError,
            """{"verdict":"Time limit exceeded on test 7"}""" to SubmissionResult.TimeLimitExceeded,
            """{"verdict":"Runtime error on test 2"}""" to SubmissionResult.RuntimeError,
            """{"verdict":"Memory limit exceeded on test 1"}""" to SubmissionResult.MemoryLimitExceeded,
            """{"verdict":"Hacked"}""" to SubmissionResult.Unknown("Hacked"),
        ).forEach { (body, expected) ->
            val transport = transport(verdictScript = listOf(body))
            val svc = service(transport)
            val handle = svc.submit(SubmitRequest("1927A", "89", "code"))
            assertEquals(expected, svc.watchSubmission(handle).toList().single(), "verdict=$body")
        }
    }

    @Test
    fun `missing verdict means judging not started`() {
        // 旧实现事实:/data/submitSource 无 verdict 字段 = 评测未开始(Processing)
        assertEquals(SubmissionResult.Pending, verdictToResult(null))
        assertEquals(SubmissionResult.Pending, verdictToResult(""))
    }

    @Test
    fun `exhausted attempts throw SubmissionTimeoutException from state machine`() {
        val transport = transport(verdictScript = listOf("""{"verdict":"Running on test 3"}"""))
        val svc = service(transport, maxAttempts = 3)
        assertFailsWith<SubmissionTimeoutException> {
            runBlocking {
                svc.watchSubmission(svc.submit(SubmitRequest("1927A", "89", "code"))).toList()
            }
        }
    }

    @Test
    fun `watching an unknown handle fails fast with explicit context error`() {
        val transport = transport(verdictScript = listOf("""{"verdict":"Accepted"}"""))
        val svc = service(transport)
        val handle =
            com.codequest.spi.SubmissionHandle(
                CODEFORCES_PLATFORM_ID,
                "888888888", // 未经过本服务 submit,无 csrf 上下文
            )
        val error =
            assertFailsWith<OJException> {
                runBlocking { svc.watchSubmission(handle).toList() }
            }
        assertTrue(error.message!!.contains("csrf"))
    }

    @Test
    fun `tokens parser rejects wrong arity`() {
        assertNull(CodeForcesApi.SubmitTokens.parse("a,b,c"))
        assertNull(CodeForcesApi.SubmitTokens.parse("a,b,c,d,e"))
        assertNull(CodeForcesApi.SubmitTokens.parse("a,,c,d"))
        assertEquals(
            CodeForcesApi.SubmitTokens("a", "b", "c", "d"),
            CodeForcesApi.SubmitTokens.parse("a,b,c,d"),
        )
    }
}
