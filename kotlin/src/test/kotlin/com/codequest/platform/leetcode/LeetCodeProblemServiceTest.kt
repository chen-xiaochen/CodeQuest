package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.spi.ProblemFilter
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [unit] WP19:LeetCodeProblemService——分页 Flow 游走(limit 语义)、题面详情
 * (HTML / CN 回退 translatedContent / 双空报错)。
 */
class LeetCodeProblemServiceTest {
    private fun transportFor(): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                val envelope = Json.parseToJsonElement(request.body!!).jsonObject
                when (envelope["operationName"]!!.jsonPrimitive.content) {
                    "problemsetQuestionListV2" -> {
                        val variables = envelope["variables"]!!.jsonObject
                        val skip = variables["skip"]!!.jsonPrimitive.content.toInt()
                        val limit = variables["limit"]!!.jsonPrimitive.content.toInt()
                        val total = 250
                        val items =
                            (skip until minOf(skip + limit, total)).joinToString(",") { i ->
                                """{"acRate":50.0,"difficulty":"Easy","questionFrontendId":"$i",
                                   "titleSlug":"problem-$i","title":"Problem $i","paidOnly":false,"status":null,"frequency":null}"""
                            }
                        """{"data":{"problemsetQuestionListV2":{"totalLength":$total,"questions":[$items]}}}"""
                    }
                    "problemsetQuestionList" -> {
                        val variables = envelope["variables"]!!.jsonObject
                        val skip = variables["skip"]!!.jsonPrimitive.content.toInt()
                        val limit = variables["limit"]!!.jsonPrimitive.content.toInt()
                        val items =
                            (skip until skip + limit).joinToString(",") { i ->
                                """{"acRate":50.0,"difficulty":"Medium","frontendQuestionId":"$i",
                                   "titleSlug":"cn-$i","title":"CN $i","paidOnly":false,
                                   "topicTags":[{"name":"DP","slug":"dp"}]}"""
                            }
                        """{"data":{"problemsetQuestionList":{"total":1000,"hasMore":true,"questions":[$items]}}}"""
                    }
                    "questionData" ->
                        """{"data":{"question":{"questionId":"7","titleSlug":"two-sum","title":"Two Sum","content":null,
                           "translatedContent":"<p>中文题面</p>","isPaidOnly":false,"difficulty":"Easy","topicTags":[]}}}"""
                    else -> error("unexpected operation")
                }
            } else {
                "{}"
            }
        }
        return transport
    }

    @Test
    fun `queryProblems walks pages until limit is satisfied`() = runBlocking {
        val transport = transportFor()
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        val problems = service.queryProblems(ProblemFilter(limit = 250)).toList()

        assertEquals(250, problems.size)
        assertEquals("problem-0", problems.first().remoteId)
        assertEquals("problem-249", problems.last().remoteId)
        // 三页:100 + 100 + 50(单页上限 100)
        val pageLimits =
            transport.recorded
                .filter { it.method == "POST_GRAPHQL" }
                .map { Json.parseToJsonElement(it.body!!).jsonObject["variables"]!!.jsonObject["limit"]!!.jsonPrimitive.content.toInt() }
        assertEquals(listOf(100, 100, 50), pageLimits)
    }

    @Test
    fun `summaries carry url difficulty and workspace-independent fields`() = runBlocking {
        val transport = transportFor()
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        val problem = service.queryProblems(ProblemFilter(limit = 1)).single()

        assertEquals("https://leetcode.com/problems/problem-0/", problem.url)
        assertEquals(Difficulty("Easy"), problem.difficulty)
        assertEquals(PlatformId("leetcode"), LeetCodeVariant.LEETCODE.id)
    }

    @Test
    fun `cn summaries include topic tags`() = runBlocking {
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE_CN, LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transportFor()))

        val problem = service.queryProblems(ProblemFilter(limit = 1)).single()

        assertEquals(listOf("DP"), problem.tags)
        assertEquals("https://leetcode.cn/problems/cn-0/", problem.url)
    }

    @Test
    fun `detail falls back to translatedContent as HTML`() = runBlocking {
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE_CN, LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transportFor()))

        val statement = service.fetchProblemDetail("two-sum")

        assertEquals("two-sum", statement.remoteId)
        assertEquals("<p>中文题面</p>", statement.content)
        assertEquals(ContentFormat.HTML, statement.contentFormat)
    }

    @Test
    fun `detail with neither content nor translated throws OJException`() {
        val transport = transportFor()
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                """{"data":{"question":{"questionId":"9","titleSlug":"premium","content":null,
                   "translatedContent":null,"isPaidOnly":true,"difficulty":"Hard","topicTags":[]}}}"""
            } else {
                "{}"
            }
        }
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))
        assertFailsWith<OJException> {
            runBlocking { service.fetchProblemDetail("premium") }
        }
    }
}
