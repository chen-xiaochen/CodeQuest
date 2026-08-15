package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP19:LeetCodeSubmissionService——提交句柄构造、经 BaseSubmissionFlow +
 * 假 transport 的轮询状态转换(Pending→Judging→终态)、超时、快照指标。
 * 适配器自身不写轮询循环(状态机节奏全部来自 spi)。
 */
class LeetCodeSubmissionServiceTest {
    /** 按序吐出 /check/ 状态脚本;耗尽后重复最后一个。 */
    private fun transportWithCheckScript(
        questionDataContent: String =
            """{"data":{"question":{"questionId":"1234","titleSlug":"two-sum"}}}""",
        checkStates: List<String>,
    ): FakeOJTransport {
        val transport = FakeOJTransport()
        var checkIndex = 0
        transport.handler = { request ->
            when {
                request.method == "POST_GRAPHQL" -> questionDataContent
                request.method == "GET" && request.url.endsWith("/api/home/") -> "" // csrf 前置请求
                request.method == "POST" && request.url.endsWith("/problems/two-sum/submit/") ->
                    """{"submission_id": 42}"""
                request.method == "GET" && request.url.contains("/submissions/detail/42/check/") -> {
                    val state = checkStates[checkIndex.coerceAtMost(checkStates.size - 1)]
                    checkIndex++
                    state
                }
                else -> error("unexpected request: ${request.method} ${request.url}")
            }
        }
        return transport
    }

    private fun config(maxAttempts: Int = 10) =
        BaseSubmissionFlow.PollConfig(initialIntervalMs = 100, maxIntervalMs = 100, maxAttempts = maxAttempts)

    private fun service(
        transport: FakeOJTransport,
        delays: MutableList<Long> = mutableListOf(),
        maxAttempts: Int = 10,
    ) = LeetCodeSubmissionService(
        LeetCodeVariant.LEETCODE,
        LeetCodeApi(LeetCodeVariant.LEETCODE, transport),
        pollConfig = config(maxAttempts),
        delayFn = { ms -> delays.add(ms) },
    )

    @Test
    fun `submit resolves question id by slug and wraps platform submission id`() = runBlocking {
        val transport =
            transportWithCheckScript(
                checkStates = listOf("""{"state":"SUCCESS","status_msg":"Accepted","memory":16900,"status_runtime":"4 ms"}"""),
            )

        val handle = service(transport).submit(SubmitRequest("two-sum", "kotlin", "fun solve() = 1"))

        assertEquals("leetcode", handle.platformId.value)
        assertEquals("42", handle.remoteSubmissionId)
        // 提交请求体:snake_case 信封(协议事实)
        val submitBody =
            Json.parseToJsonElement(
                transport.recorded.first { it.method == "POST" }.body!!,
            ).jsonObject
        assertEquals("kotlin", submitBody["lang"]!!.jsonPrimitive.content)
        assertEquals("1234", submitBody["question_id"]!!.jsonPrimitive.content)
        assertEquals("fun solve() = 1", submitBody["typed_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `submit rejects unsupported language id`() {
        val transport = transportWithCheckScript(checkStates = listOf("""{"state":"SUCCESS"}"""))
        assertFailsWith<OJException> {
            runBlocking { service(transport).submit(SubmitRequest("two-sum", "cobol", "x")) }
        }
    }

    @Test
    fun `watchSubmission transitions Pending to Judging to Accepted via BaseSubmissionFlow`() = runBlocking {
        val delays = mutableListOf<Long>()
        val transport =
            transportWithCheckScript(
                checkStates =
                    listOf(
                        """{"state":"PENDING"}""",
                        """{"state":"STARTED"}""",
                        """{"state":"SUCCESS","status_msg":"Accepted","memory":16900,"status_runtime":"4 ms"}""",
                    ),
            )
        val handle = service(transport, delays).submit(SubmitRequest("two-sum", "kotlin", "code"))

        val results = service(transport, delays).watchSubmission(handle).toList()

        assertEquals(
            listOf(SubmissionResult.Pending, SubmissionResult.Judging, SubmissionResult.Accepted),
            results,
        )
        // 两次轮询间隔(状态机节奏,非适配器循环)
        assertEquals(listOf(100L, 100L), delays)
    }

    @Test
    fun `watchSnapshots carries time and memory metrics`() = runBlocking {
        val transport =
            transportWithCheckScript(
                checkStates =
                    listOf("""{"state":"SUCCESS","status_msg":"Wrong Answer","memory":20480,"status_runtime":"1 s"}"""),
            )
        val svc = service(transport)
        val handle = svc.submit(SubmitRequest("two-sum", "kotlin", "code"))

        val snapshots = svc.watchSnapshots(handle).toList()

        val snapshot = snapshots.single()
        assertEquals(SubmissionResult.WrongAnswer, snapshot.result)
        assertEquals(1000, snapshot.timeMs)
        assertEquals(20480, snapshot.memoryKb)
    }

    @Test
    fun `terminal failure states map to domain results`() = runBlocking {
        listOf(
            "Compile Error" to SubmissionResult.CompileError,
            "Runtime Error" to SubmissionResult.RuntimeError,
            "Time Limit Exceeded" to SubmissionResult.TimeLimitExceeded,
            "Memory Limit Exceeded" to SubmissionResult.MemoryLimitExceeded,
            "Output Limit Exceeded" to SubmissionResult.OutputLimitExceeded,
        ).forEach { (msg, expected) ->
            val transport =
                transportWithCheckScript(checkStates = listOf("""{"state":"SUCCESS","status_msg":"$msg"}"""))
            val result = service(transport).watchSubmission(SubmissionHandleOf()).toList().single()
            assertEquals(expected, result, "status_msg=$msg")
        }
    }

    @Test
    fun `unknown status message maps to Unknown keeping raw text`() = runBlocking {
        val transport =
            transportWithCheckScript(checkStates = listOf("""{"state":"SUCCESS","status_msg":"Something Odd"}"""))

        val result = service(transport).watchSubmission(SubmissionHandleOf()).toList().single()

        assertEquals(SubmissionResult.Unknown("Something Odd"), result)
    }

    @Test
    fun `exhausted attempts throw SubmissionTimeoutException from state machine`() {
        val transport = transportWithCheckScript(checkStates = listOf("""{"state":"PENDING"}"""))
        val svc = service(transport, maxAttempts = 3)
        assertFailsWith<SubmissionTimeoutException> {
            runBlocking { svc.watchSubmission(SubmissionHandleOf()).toList() }
        }
        // 轮询次数来自 BaseSubmissionFlow 的 maxAttempts,适配器未自循环
        assertTrue(transport.recorded.count { it.method == "GET" } >= 3)
    }

    private fun SubmissionHandleOf() =
        com.codequest.spi.SubmissionHandle(LeetCodeVariant.LEETCODE.id, "42")
}
