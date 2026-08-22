package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.spi.TestEvaluationCapable
import com.codequest.spi.TestRunRequest
import com.codequest.spi.TestRunStatus
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP53:LeetCode 云端测试(TestEvaluationCapable)——
 * interpret_solution 请求体协议(snake_case 四字段透传,旧 LeetCodeRunRequest 事实)、
 * interpret_id 解析、check 轮询→终态字段全集映射(旧 LeetCodeRunResult.Success 事实)、
 * CE/RE full_* 全文优先、非终态(PENDING)宽容解析并继续轮询、退避节奏与超时、
 * 平台能力归属;假 transport 不上网络。
 */
class LeetCodeTestEvaluationTest {
    private val request =
        TestRunRequest(
            problemRemoteId = "two-sum",
            questionId = "12488",
            languageId = "kotlin",
            code = "fun twoSum(){}",
            dataInput = "[2,7,11,15]\n9",
        )

    /** interpret_solution 响应(旧 LeetCodeRunResponse 字段全集)。 */
    private val interpretResponse =
        """{"interpret_id":"runcode_123456","test_case":"[2,7,11,15]\n9","interpret_expected_id":null}"""

    /** 非终态响应:仅有 state(其余字段宽容为空,不解析失败)。 */
    private val pendingCheck = """{"state":"PENDING"}"""

    /** 终态 SUCCESS 夹具(字段名 = 旧 runCode.scala 模型,snake_case)。 */
    private val successCheck =
        """
        {
          "code_answer": ["[0,1]", "[1,0]"],
          "expected_code_answer": ["[0,1]", "[1,0]"],
          "compare_result": "11",
          "total_correct": 2,
          "total_testcases": 2,
          "state": "SUCCESS",
          "status_code": 10,
          "status_runtime": "0 ms",
          "status_memory": "41.2 MB",
          "status_msg": "Accepted",
          "task_finish_time": 1699999999999,
          "task_name": "runcode",
          "run_success": true,
          "full_compile_error": null,
          "compile_error": null,
          "full_runtime_error": null,
          "runtime_error": null
        }
        """.trimIndent()

    private class Harness(
        val service: LeetCodeTestEvaluationService,
        val transport: FakeOJTransport,
        val delays: MutableList<Long>,
    )

    /** checkBodies 按序消费,耗尽后重复最后一项(超时场景恒 PENDING)。 */
    private fun fixture(
        variant: LeetCodeVariant = LeetCodeVariant.LEETCODE,
        checkBodies: List<String>,
        maxAttempts: Int = 20,
    ): Harness {
        val transport = FakeOJTransport()
        var checkIndex = 0
        transport.handler = { req ->
            when {
                req.method == "GET" && req.url.endsWith("/api/home/") -> ""
                req.method == "POST" && req.url.endsWith("/problems/two-sum/interpret_solution/") -> interpretResponse
                req.method == "GET" && req.url.endsWith("/submissions/detail/runcode_123456/check/") -> {
                    val body = checkBodies[checkIndex.coerceAtMost(checkBodies.lastIndex)]
                    checkIndex++
                    body
                }
                else -> throw OJException("unexpected ${req.method} ${req.url}")
            }
        }
        val delays = mutableListOf<Long>()
        val service =
            LeetCodeTestEvaluationService(
                variant = variant,
                api = LeetCodeApi(variant, transport),
                pollConfig =
                com.codequest.spi.BaseSubmissionFlow.PollConfig(
                    maxAttempts = maxAttempts,
                ),
                delayFn = { ms -> delays += ms },
            )
        return Harness(service, transport, delays)
    }

    @Test
    fun `interpret solution posts snake case body and parses interpret id`() = runBlocking {
        val harness = fixture(checkBodies = listOf(successCheck))

        val statuses = harness.service.watchTestRun(request).toList()

        assertEquals(1, statuses.size)
        val post = harness.transport.recorded.first { it.method == "POST" }
        assertEquals("https://leetcode.com/problems/two-sum/interpret_solution/", post.url)
        assertTrue("x-csrftoken" in post.headers, "interpret POST 应携带 CSRF 头")
        val body = Json.parseToJsonElement(requireNotNull(post.body)).jsonObject
        assertEquals("kotlin", body["lang"]!!.jsonPrimitive.content)
        assertEquals("[2,7,11,15]\n9", body["data_input"]!!.jsonPrimitive.content)
        assertEquals("12488", body["question_id"]!!.jsonPrimitive.content)
        assertEquals("fun twoSum(){}", body["typed_code"]!!.jsonPrimitive.content)
        assertEquals(1, harness.transport.recorded.count { it.method == "GET" && it.url.endsWith("/check/") })
    }

    @Test
    fun `success check maps full result field set`() = runBlocking {
        val result =
            fixture(checkBodies = listOf(successCheck))
                .service.watchTestRun(request).single() as TestRunStatus.Finished

        assertEquals(2, result.result.correctCount)
        assertEquals(2, result.result.totalCount)
        assertEquals(listOf("[0,1]", "[1,0]"), result.result.codeAnswers)
        assertEquals(listOf("[0,1]", "[1,0]"), result.result.expectedCodeAnswers)
        assertEquals("11", result.result.compareResult)
        assertEquals("0 ms", result.result.statusRuntime)
        assertEquals("41.2 MB", result.result.statusMemory)
        assertEquals("SUCCESS", result.result.state)
        assertNull(result.result.compileError)
        assertNull(result.result.runtimeError)
    }

    @Test
    fun `compile and runtime error prefer full text over short`() = runBlocking {
        val check =
            successCheck
                .replace("\"code_answer\": [\"[0,1]\", \"[1,0]\"],", "\"code_answer\": null,")
                .replace("\"full_compile_error\": null", "\"full_compile_error\": \"Line 3: expectation failed (full)\"")
                .replace("\"compile_error\": null", "\"compile_error\": \"Line 3: expectation\"")
                .replace("\"full_runtime_error\": null", "\"full_runtime_error\": \"NPE at Solution.kt:7 (full)\"")
                .replace("\"runtime_error\": null", "\"runtime_error\": \"NPE\"")

        val result =
            fixture(checkBodies = listOf(check))
                .service.watchTestRun(request).single() as TestRunStatus.Finished

        assertEquals("Line 3: expectation failed (full)", result.result.compileError)
        assertEquals("NPE at Solution.kt:7 (full)", result.result.runtimeError)
        // code_answer 为 null 时宽容映射为空列表(非终态字段宽容解析)
        assertEquals(emptyList(), result.result.codeAnswers)
    }

    @Test
    fun `compile error falls back to short text when full missing`() = runBlocking {
        val check =
            successCheck
                .replace("\"compile_error\": null", "\"compile_error\": \"Line 3: expectation\"")

        val result =
            fixture(checkBodies = listOf(check))
                .service.watchTestRun(request).single() as TestRunStatus.Finished

        assertEquals("Line 3: expectation", result.result.compileError)
    }

    @Test
    fun `pending state keeps polling with backoff until success`() = runBlocking {
        val harness = fixture(checkBodies = listOf(pendingCheck, pendingCheck, pendingCheck, successCheck))

        val statuses = harness.service.watchTestRun(request).toList()

        assertEquals(
            listOf(TestRunStatus.Pending, TestRunStatus.Pending, TestRunStatus.Pending),
            statuses.dropLast(1),
        )
        assertTrue(statuses.last() is TestRunStatus.Finished)
        // 退避节奏 = BaseSubmissionFlow 语义:首次立即,后续 1s→2s→4s 指数(封顶前)
        assertEquals(listOf(1000L, 2000L, 4000L), harness.delays)
        assertEquals(4, harness.transport.recorded.count { it.method == "GET" && it.url.endsWith("/check/") })
    }

    @Test
    fun `polling times out with domain exception after max attempts`() {
        val harness = fixture(checkBodies = listOf(pendingCheck), maxAttempts = 3)

        assertFailsWith<SubmissionTimeoutException> {
            runBlocking { harness.service.watchTestRun(request).toList() }
        }
        assertEquals(3, harness.transport.recorded.count { it.method == "GET" && it.url.endsWith("/check/") })
    }

    @Test
    fun `unsupported language id fails fast`() {
        val harness = fixture(checkBodies = listOf(successCheck))
        val badRequest = request.copy(languageId = "brainfuck")

        assertFailsWith<OJException> {
            runBlocking { harness.service.watchTestRun(badRequest).toList() }
        }
        // 校验先于 interpret POST,不应发出任何业务请求
        assertTrue(harness.transport.recorded.none { it.url.endsWith("/interpret_solution/") })
    }

    @Test
    fun `service and platform variants are test evaluation capable`() {
        assertTrue(fixture(checkBodies = listOf(successCheck)).service is TestEvaluationCapable)

        LeetCodeVariant.entries.forEach { variant ->
            val platform = LeetCodePlatform(variant, FakeOJTransport(), InMemoryCredentialStore())
            assertTrue(platform is TestEvaluationCapable, "${variant.id} 缺少 TestEvaluationCapable")
        }
    }
}
