package com.codequest.platform.luogu

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
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
 * [unit] WP26:洛谷提交流程——submit(JSON POST 拿 rid)与 watchSubmission
 * 轮询状态转换(经 BaseSubmissionFlow 状态机:非终态→终态;testCases 回捞;
 * 503 暂态经 LuoguApi 内部重试),全部经 FakeOJTransport 脚本化,不上网络。
 */
class LuoguSubmissionServiceTest {
    private val homeHtml = """<html><head><meta name="csrf-token" content="csrf-1"></head></html>"""

    private fun recordHtml(status: Int): String =
        """<html><body><script>decodeURIComponent("${urlEncode("""{"currentData":{"record":{"status":$status,"detail":{}}}}""")}");</script></body></html>"""

    private fun service(transport: FakeOJTransport): LuoguSubmissionService =
        LuoguSubmissionService(
            api = LuoguApi(transport, retryDelay = { }),
            pollConfig =
                BaseSubmissionFlow.PollConfig(
                    initialIntervalMs = TEST_INTERVAL_MS,
                    maxIntervalMs = TEST_INTERVAL_MS,
                    backoffMultiplier = FIXED_BACKOFF,
                ),
            delayFn = { },
        )

    @Test
    fun `submit posts code and wraps rid into handle`() = runBlocking {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                luoguSubmitUrl("P1001") -> """{"rid":9876543}"""
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val handle = service(transport).submit(SubmitRequest(problemRemoteId = "P1001", languageId = "28", code = "int main(){}"))

        assertEquals("luogu", handle.platformId.value)
        assertEquals("9876543", handle.remoteSubmissionId)
        val post = transport.recorded.single { it.url == luoguSubmitUrl("P1001") }
        assertEquals("""{"lang":28,"enableO2":1,"code":"int main(){}"}""", post.body)
        assertEquals("https://www.luogu.com.cn/problem/P1001", post.headers["Referer"])
    }

    @Test
    fun `unsupported language id is rejected before any request`() {
        val transport = FakeOJTransport()
        assertFailsWith<OJException> {
            runBlocking {
                service(transport).submit(SubmitRequest("P1001", "kotlin", "x"))
            }
        }
        assertTrue(transport.recorded.isEmpty())
    }

    @Test
    fun `watch submission emits non-terminal then terminal verdict`() = runBlocking {
        val transport = FakeOJTransport()
        var recordCalls = 0
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                luoguSubmitUrl("P1001") -> """{"rid":42}"""
                luoguRecordUrl("42") -> {
                    recordCalls++
                    recordHtml(if (recordCalls == 1) 1 else 12)
                }
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val verdicts = service(transport).run {
            val handle = submit(SubmitRequest("P1001", "7", "print(a+b)"))
            watchSubmission(handle).toList()
        }

        assertEquals(listOf(SubmissionResult.Judging, SubmissionResult.Accepted), verdicts)
    }

    @Test
    fun `watch submission maps compile error`() = runBlocking {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                luoguSubmitUrl("P1001") -> """{"rid":7}"""
                luoguRecordUrl("7") -> recordHtml(2)
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val verdicts = service(transport).run {
            watchSubmission(submissionHandle("7")).toList()
        }

        assertEquals(listOf(SubmissionResult.CompileError), verdicts)
    }

    @Test
    fun `record 503 transient is retried inside api before polling sees it`() = runBlocking {
        val transport = FakeOJTransport()
        var recordCalls = 0
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                luoguSubmitUrl("P1001") -> """{"rid":9}"""
                luoguRecordUrl("9") -> {
                    recordCalls++
                    if (recordCalls == 1) throw OJException("HTTP 503: service unavailable") else recordHtml(12)
                }
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val verdicts = service(transport).run {
            watchSubmission(submissionHandle("9")).toList()
        }

        assertEquals(listOf(SubmissionResult.Accepted), verdicts)
        assertEquals(2, recordCalls, "503 应在 LuoguApi 内部重试一次后成功")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val TEST_INTERVAL_MS = 1L

        const val FIXED_BACKOFF = 1.0
    }
}

/** 测试内便捷构造 spi 提交句柄。 */
private fun submissionHandle(rid: String) = SubmissionHandle(platformId = LUOGU_PLATFORM_ID, remoteSubmissionId = rid)
