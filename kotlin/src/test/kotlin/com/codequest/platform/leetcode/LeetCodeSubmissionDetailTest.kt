package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.SubmissionDetailCapable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP36:LeetCode 提交详情拉取(SubmissionDetailCapable)——
 * /check/ 端点详情超集解析(字段名 = 旧 LeetCodeSubmitAnswerResult.Success 事实)、
 * WA 错误用例三件套映射、CE/RE message 取全文、extras 百分位/用例计数、
 * CN 变体 viewUrl、假 transport 不上网络。
 */
class LeetCodeSubmissionDetailTest {
    /** WA 完整夹具(旧 Scala 模型的字段全集,snake_case 协议事实)。 */
    private val wrongAnswerCheck =
        """
        {
          "code_output": "[2,2]",
          "compile_error": null,
          "elasped_time": 314,
          "expected_output": "[0,1]",
          "full_compile_error": null,
          "full_runtime_error": null,
          "input": "[2,7,11,15]\n9",
          "input_formatted": "nums = [2,7,11,15]\ntarget = 9",
          "lang": "kotlin",
          "last_testcase": "[2,7,11,15]\n9",
          "memory": 4120000,
          "memory_percentile": 87.3,
          "pretty_lang": "Kotlin",
          "runtime_error": null,
          "runtime_percentile": 42.1,
          "state": "SUCCESS",
          "status_code": 10,
          "status_memory": "20.5 MB",
          "status_msg": "Wrong Answer",
          "status_runtime": "68 ms",
          "std_output": "",
          "total_correct": 12,
          "total_testcases": 14
        }
        """.trimIndent()

    private fun service(
        variant: LeetCodeVariant = LeetCodeVariant.LEETCODE,
        checkBody: String,
    ): LeetCodeSubmissionService {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when {
                request.method == "GET" && request.url.endsWith("/api/home/") -> ""
                request.method == "GET" && request.url.contains("/submissions/detail/42/check/") -> checkBody
                else -> throw OJException("unexpected ${request.method} ${request.url}")
            }
        }
        return LeetCodeSubmissionService(variant, LeetCodeApi(variant, transport))
    }

    @Test
    fun `wrong answer check maps verdict metrics language and error case triplet`() = runBlocking {
        val detail =
            service(checkBody = wrongAnswerCheck)
                .fetchSubmissionDetail(remoteSubmissionId = "42", problemRemoteId = "two-sum")

        requireNotNull(detail)
        assertEquals(SubmissionResult.WrongAnswer, detail.verdict)
        assertEquals(68, detail.timeMs)
        assertEquals(4120000, detail.memoryKb)
        assertEquals("Kotlin", detail.language)
        assertEquals("Wrong Answer", detail.message)

        val errorCase = requireNotNull(detail.errorCase)
        // input_formatted 优先(旧 LeetCodeWrongAnswerResultForm 事实)
        assertEquals("nums = [2,7,11,15]\ntarget = 9", errorCase.input)
        assertEquals("[0,1]", errorCase.expectedOutput)
        assertEquals("[2,2]", errorCase.actualOutput)
    }

    @Test
    fun `extras carry percentiles and testcase counts`() = runBlocking {
        val extras =
            service(checkBody = wrongAnswerCheck)
                .fetchSubmissionDetail("42", "two-sum")!!
                .extras

        assertEquals("42.10%", extras["runtimePercentile"])
        assertEquals("87.30%", extras["memoryPercentile"])
        assertEquals("12/14", extras["testcases"])
    }

    @Test
    fun `compile error message prefers full compile error text`() = runBlocking {
        val check =
            wrongAnswerCheck
                .replace("\"status_msg\": \"Wrong Answer\"", "\"status_msg\": \"Compile Error\"")
                .replace("\"compile_error\": null", "\"compile_error\": \"Line 3: expectation\"")
                .replace("\"full_compile_error\": null", "\"full_compile_error\": \"Line 3: expectation failed\"")
                .replace("\"input_formatted\": \"nums = [2,7,11,15]\\ntarget = 9\"", "\"input_formatted\": null")

        val detail = service(checkBody = check).fetchSubmissionDetail("42", "two-sum")

        requireNotNull(detail)
        assertEquals(SubmissionResult.CompileError, detail.verdict)
        assertEquals("Line 3: expectation failed", detail.message)
        // 三件套仅 WA 展示(旧 Form 事实:CE 走 ErrorResultForm 文案)
        assertNull(detail.errorCase)
    }

    @Test
    fun `input falls back to raw input when formatted missing`() = runBlocking {
        val check = wrongAnswerCheck.replace("\"input_formatted\": \"nums = [2,7,11,15]\\ntarget = 9\"", "\"input_formatted\": null")

        val errorCase = service(checkBody = check).fetchSubmissionDetail("42", "two-sum")!!.errorCase

        requireNotNull(errorCase)
        assertEquals("[2,7,11,15]\n9", errorCase.input)
    }

    @Test
    fun `non terminal state maps to non terminal verdict without error case`() = runBlocking {
        val check = """{"state":"PENDING"}"""

        val detail = service(checkBody = check).fetchSubmissionDetail("42", "two-sum")

        requireNotNull(detail)
        assertEquals(SubmissionResult.Pending, detail.verdict)
        assertNull(detail.message)
        assertNull(detail.errorCase)
        assertTrue(detail.extras.isEmpty())
    }

    @Test
    fun `view url uses variant domain and problem slug`() {
        val leetcode = service(checkBody = wrongAnswerCheck)
        val cn = service(variant = LeetCodeVariant.LEETCODE_CN, checkBody = wrongAnswerCheck)

        assertEquals(
            "https://leetcode.com/problems/two-sum/submissions/42/",
            leetcode.submissionViewUrl("42", "two-sum"),
        )
        assertEquals(
            "https://leetcode.cn/problems/two-sum/submissions/42/",
            cn.submissionViewUrl("42", "two-sum"),
        )
        assertNull(leetcode.submissionViewUrl("42", ""))
    }

    @Test
    fun `service itself is the detail capability instance`() {
        // 平台类按能力接口委托到本服务(形态同其他能力),此处锁定接口归属
        val leetcode = service(checkBody = wrongAnswerCheck)
        assertTrue(leetcode is SubmissionDetailCapable)
    }

    @Test
    fun `missing state field fails fast with domain exception`() {
        val transport = FakeOJTransport()
        transport.handler = { _ -> "{}" }
        val leetcode = LeetCodeSubmissionService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        assertFailsWith<OJException> {
            runBlocking { leetcode.fetchSubmissionDetail("42", "two-sum") }
        }
    }
}
