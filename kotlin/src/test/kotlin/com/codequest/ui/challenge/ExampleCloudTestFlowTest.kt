package com.codequest.ui.challenge

import com.codequest.core.model.OJException
import com.codequest.platform.codeforces.CodeForcesPlatform
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.TestEvaluationCapable
import com.codequest.spi.TestRunRequest
import com.codequest.spi.TestRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [integration] WP74:全示例测试端到端(数据链,假 transport 不上网络)——
 * 题面 detail(exampleTestcaseList,与 questionId 同次拉取)→ action 侧纯函数组装
 * (保序 join/空白过滤)→ TestRunRequest(data_input 透传断言)→ watchTestRun 多用例
 * 终态 → 结果模型(correctCount/totalCount/逐用例对比/桥 payload mode)。
 * 门控/EDT/通知壳与 WP55「提交测试」同构(IntelliJ 平台依赖,仓库无 UI 夹具先例,
 * 门控判定以 [cloudTestCapable] 纯函数覆盖)。
 */
class ExampleCloudTestFlowTest {
    /** 题面详情(questionData):questionId 与 exampleTestcases 同一响应;第 3 项空白验证过滤。 */
    private val questionData =
        """{"data":{"question":{"questionId":"12488","titleSlug":"two-sum","title":"Two Sum",
           "content":"<p>Given an array...</p>","translatedContent":null,"isPaidOnly":false,
           "difficulty":"Easy","topicTags":[],
           "exampleTestcaseList":["[2,7,11,15]\n9","[3,2,4]\n6"," \n","[3,3]\n6"]}}}"""

    /** interpret_solution 响应(旧 LeetCodeRunResponse 字段全集)。 */
    private val interpretResponse =
        """{"interpret_id":"runcode_123456","test_case":"...","interpret_expected_id":null}"""

    /** 三用例终态:前两过第三挂(compare_result 位图 110)。 */
    private val threeCaseSuccess =
        """
        {
          "code_answer": ["[0,1]", "[1,0]", "[0,2]"],
          "expected_code_answer": ["[0,1]", "[1,0]", "[2,0]"],
          "compare_result": "110",
          "total_correct": 2,
          "total_testcases": 3,
          "state": "SUCCESS",
          "status_code": 10,
          "status_runtime": "0 ms",
          "status_memory": "41.2 MB",
          "status_msg": "Finished",
          "run_success": true,
          "full_compile_error": null,
          "compile_error": null,
          "full_runtime_error": null,
          "runtime_error": null
        }
        """.trimIndent()

    private fun fixture(detailBody: String = questionData): Pair<LeetCodePlatform, FakeOJTransport> {
        val transport = FakeOJTransport()
        transport.handler = { req ->
            when {
                req.method == "GET" && req.url.endsWith("/api/home/") -> ""
                req.method == "POST_GRAPHQL" -> detailBody
                req.method == "POST" && req.url.endsWith("/problems/two-sum/interpret_solution/") -> interpretResponse
                req.method == "GET" && req.url.endsWith("/submissions/detail/runcode_123456/check/") -> threeCaseSuccess
                else -> throw OJException("unexpected ${req.method} ${req.url}")
            }
        }
        return LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, InMemoryCredentialStore()) to transport
    }

    @Test
    fun `examples flow assembles ordered dataInput from statement and maps multi-case result`() = runBlocking {
        val (platform, transport) = fixture()

        // 1) 数据源:SPI fetchProblemDetail 一次同时给出 questionId + exampleTestcases
        val detail = (platform as ProblemQueryCapable).fetchProblemDetail("two-sum")
        assertEquals("12488", detail.questionId)

        // 2) action 侧纯函数组装(照 CloudTestCoordinator 全示例分支):空白项过滤,顺序保持
        val examples = TestRunPrep.exampleCases(detail.exampleTestcases)
        assertEquals(listOf("[2,7,11,15]\n9", "[3,2,4]\n6", "[3,3]\n6"), examples)

        // 3) 请求组装(与「提交测试」同结构,dataInput 换全示例)
        val request =
            TestRunRequest(
                problemRemoteId = "two-sum",
                questionId = detail.questionId!!,
                languageId = "kotlin",
                code = "fun twoSum(){}",
                dataInput = TestRunPrep.exampleDataInput(detail.exampleTestcases),
            )

        // 4) 云测收集至终态(首查即终态,零退避等待)
        val statuses = platform.watchTestRun(request).toList()
        assertTrue(statuses.last() is TestRunStatus.Finished)

        // 5) interpret 请求体:全部示例按序 join 透传(空白项不参与)
        val post = transport.recorded.first { it.method == "POST" && it.url.endsWith("/interpret_solution/") }
        val body = Json.parseToJsonElement(requireNotNull(post.body)).jsonObject
        assertEquals("[2,7,11,15]\n9\n[3,2,4]\n6\n[3,3]\n6", body["data_input"]!!.jsonPrimitive.content)
        assertEquals("12488", body["question_id"]!!.jsonPrimitive.content)

        // 6) 结果模型组装(右窗「测试用例」页消费的等价物):汇总 + 逐用例对比
        val result = (statuses.last() as TestRunStatus.Finished).result
        assertEquals(2 to 3, TestRunResultState.summaryCounts(result))
        val rows = TestRunResultState.caseRows(result, examples)
        assertEquals(examples, rows.map { it.inputSummary })
        assertEquals(listOf(true, true, false), rows.map { it.passed })
        assertEquals("[2,0]", rows[2].expected)
        assertEquals("[0,2]", rows[2].actual)

        // 7) 桥 payload:mode=examples(页面「全部示例」标题区分的载体)
        val payload =
            Json.parseToJsonElement(
                TestRunResultState.resultPayload("c-1", result, examples, mode = CloudTestCoordinator.PAYLOAD_MODE_EXAMPLES),
            ).jsonObject
        assertEquals("examples", payload["mode"]!!.jsonPrimitive.content)
        assertEquals(2, payload["passed"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, payload["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `statement without usable examples refuses before any cloud test`() = runBlocking {
        // 题面详情 exampleTestcaseList 为空数组 → 组装结果为空 → NO_EXAMPLES 文案,不发起云测
        val (platform, transport) = fixture(detailBody = """{"data":{"question":{"questionId":"12488","titleSlug":"two-sum","title":"Two Sum","content":"<p>x</p>","contentFormat":null,"translatedContent":null,"isPaidOnly":false,"difficulty":"Easy","topicTags":[],"exampleTestcaseList":[]}}}""")

        val detail = (platform as ProblemQueryCapable).fetchProblemDetail("two-sum")
        val examples = TestRunPrep.exampleCases(detail.exampleTestcases)

        assertTrue(examples.isEmpty())
        assertEquals("testRun.error.noExamples", TestRunPrep.messageKey(TestRunPrep.Refusal.NO_EXAMPLES))
        assertTrue(transport.recorded.none { it.url.endsWith("/interpret_solution/") })
    }

    @Test
    fun `cloud test keys are visible only for TestEvaluationCapable platforms`() {
        // 门控判定(浮动键 update 的纯函数核):LC 双变体实现能力 → 显;CF 未实现 → 隐
        LeetCodeVariant.entries.forEach { variant ->
            val platform = LeetCodePlatform(variant, FakeOJTransport(), InMemoryCredentialStore())
            assertTrue(platform is TestEvaluationCapable, "${variant.id} 缺少 TestEvaluationCapable")
            assertTrue(cloudTestCapable(platform), "${variant.id} 云测键应可见")
        }
        assertFalse(cloudTestCapable(CodeForcesPlatform(FakeOJTransport(), InMemoryCredentialStore())))
        assertFalse(cloudTestCapable(null))
    }
}
