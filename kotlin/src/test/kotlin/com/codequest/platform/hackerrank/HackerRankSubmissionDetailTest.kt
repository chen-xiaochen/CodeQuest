package com.codequest.platform.hackerrank

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.platform.leetcode.FakeOJTransport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [unit] WP36:HackerRank 提交详情拉取(SubmissionDetailCapable)——
 * 轮询端点详情超集解析(testcase_status 列表,旧 HACKERRANK_SUBMISSION_CASE 事实)、
 * 用例通过计数 extras、CE message 取 compile_message、viewUrl(master/竞赛两形态,
 * 旧 HackerRankSubmissionService.showSubmissionDetails 事实)。
 */
class HackerRankSubmissionDetailTest {
    private fun service(checkBody: String): HackerRankSubmissionService {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when {
                request.url == "https://www.hackerrank.com/rest/contests/master/challenges/solve-me-first/submissions/73920325" ->
                    checkBody
                else -> throw OJException("unexpected ${request.method} ${request.url}")
            }
        }
        return HackerRankSubmissionService(api = HackerRankApi(transport))
    }

    private val wrongAnswerBody =
        """
        {"model":{"id":73920325,"challenge_id":2089,"status":"Wrong Answer","score_processed":3,
          "compile_message":null,"testcase_status":[1,1,0,0],
          "testcase_message":["","Passed","Wrong Answer","Wrong Answer"]}}
        """.trimIndent()

    @Test
    fun `wrong answer maps verdict testcase count extras and null metrics`() = runBlocking {
        val detail =
            service(wrongAnswerBody)
                .fetchSubmissionDetail(remoteSubmissionId = "73920325", problemRemoteId = "solve-me-first")

        requireNotNull(detail)
        assertEquals(SubmissionResult.WrongAnswer, detail.verdict)
        assertEquals("Wrong Answer", detail.message)
        // 旧 Form 事实:用例区只展示 通过数/总数
        assertEquals("2/4", detail.extras["testcases"])
        // 旧实现未提取耗时/内存作指标(codechecker_time 仅落库,Form 不展示),如实为 null
        assertNull(detail.timeMs)
        assertNull(detail.memoryKb)
        assertNull(detail.language)
        // 提交响应不含失败用例 input/expected(那是 run-code 响应的字段),如实为 null
        assertNull(detail.errorCase)
    }

    @Test
    fun `compile error message prefers compile_message`() = runBlocking {
        val body =
            wrongAnswerBody
                .replace("\"status\":\"Wrong Answer\"", "\"status\":\"Compilation error\"")
                .replace("\"compile_message\":null", "\"compile_message\":\"solution.cc:5:5: error: expected ';'\"")
                .replace("[1,1,0,0]", "[]")

        val detail = service(body).fetchSubmissionDetail("73920325", "solve-me-first")

        requireNotNull(detail)
        assertEquals(SubmissionResult.CompileError, detail.verdict)
        assertEquals("solution.cc:5:5: error: expected ';'", detail.message)
        assertNull(detail.extras["testcases"])
    }

    @Test
    fun `view url distinguishes master and contest challenges`() {
        val submissionService = service(wrongAnswerBody)

        assertEquals(
            "https://www.hackerrank.com/challenges/solve-me-first/submissions/code/73920325",
            submissionService.submissionViewUrl("73920325", "solve-me-first"),
        )
        assertEquals(
            "https://www.hackerrank.com/contests/projecteuler/challenges/multiples-of-3-and-5/submissions/code/73920325",
            submissionService.submissionViewUrl("73920325", "projecteuler/multiples-of-3-and-5"),
        )
        assertNull(submissionService.submissionViewUrl("73920325", ""))
    }

    @Test
    fun `detail fetch without problem remote id fails fast`() {
        val submissionService = service(wrongAnswerBody)

        assertFailsWith<OJException> {
            runBlocking { submissionService.fetchSubmissionDetail("73920325", "") }
        }
    }
}
