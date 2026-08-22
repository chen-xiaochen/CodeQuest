package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.spi.CodeSnippet
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
import kotlin.test.assertTrue

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
                                   "titleSlug":"problem-$i","title":"Problem $i","paidOnly":false,"status":${if (i == 0) "\"ac\"" else if (i == 1) "\"\"" else "null"},"frequency":null}"""
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
                    // WP42:公司列表(COM 走 data.companyTags)
                    "questionCompanyTagsV2" ->
                        """{"data":{"companyTags":[
                           {"name":"Amazon","slug":"amazon","id":"1","questionCount":38},
                           {"name":"Google","slug":"google","id":"2","questionCount":30}]}}"""
                    // WP42:公司题单(total 为 totalLength 别名;首题会员 + translatedTitle 空)
                    "favoriteQuestionList" ->
                        """{"data":{"favoriteQuestionList":{"total":2,"questions":[
                           {"difficulty":"Medium","id":"1","paidOnly":true,"questionFrontendId":"1","status":"NOT_AC",
                            "title":"Two Sum","titleSlug":"two-sum","translatedTitle":null,"isInMyFavorites":false,
                            "frequency":0.5,"acRate":50.0,"topicTags":[{"name":"Array","slug":"array"}]},
                           {"difficulty":"Easy","id":"2","paidOnly":false,"questionFrontendId":"2","status":"AC",
                            "title":"Other","titleSlug":"other","translatedTitle":null,"isInMyFavorites":false,
                            "frequency":null,"acRate":40.0,"topicTags":[]}]}}}"""
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
        // 1.2.1:站内状态透传——"ac"(小写)→已解;次题 null→未知
        assertEquals(true, problem.solvedOnPlatform)
    }

    @Test
    fun `cn summaries include topic tags`() = runBlocking {
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE_CN, LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transportFor()))

        val problem = service.queryProblems(ProblemFilter(limit = 1)).single()

        assertEquals(listOf("DP"), problem.tags)
        assertEquals("https://leetcode.cn/problems/cn-0/", problem.url)
        // 1.2.1:CN 夹具无 status 字段 → null(未知,不参与合并)
        assertEquals(null, problem.solvedOnPlatform)
    }

    @Test
    fun `blank platform status maps to unknown instead of tried`() = runBlocking {
        // 1.2.1 实测:CN 站未做题下发空串——必须按未知处理,否则全库误判「已尝试」。
        // 夹具:第 0 题 "ac"→true;第 1 题 ""→null(空串锁定);第 2 题起 null→null
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transportFor()))
        val problems = service.queryProblems(ProblemFilter(limit = 3)).toList()
        assertEquals(true, problems[0].solvedOnPlatform)
        assertEquals(null, problems[1].solvedOnPlatform)
        assertEquals(null, problems[2].solvedOnPlatform)
    }

    @Test
    fun `detail falls back to translatedContent as HTML`() = runBlocking {
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE_CN, LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transportFor()))

        val statement = service.fetchProblemDetail("two-sum")

        assertEquals("two-sum", statement.remoteId)
        assertEquals("<p>中文题面</p>", statement.content)
        assertEquals(ContentFormat.HTML, statement.contentFormat)
        // 夹具 questionData 无 codeSnippets → 空表(缺省兼容)
        assertEquals(emptyList<CodeSnippet>(), statement.codeSnippets)
    }

    @Test
    fun `detail carries per-language start code snippets`() = runBlocking {
        // 1.2.4:codeSnippets 透传(langSlug 同名约定),开刷注入模版 ${challenge.code}
        val transport = transportFor()
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                """{"data":{"question":{"questionId":"1","titleSlug":"two-sum","title":"Two Sum",
                   "content":"<p>Given an array...</p>","translatedContent":null,"isPaidOnly":false,
                   "difficulty":"Easy","topicTags":[],
                   "codeSnippets":[
                     {"lang":"Java","langSlug":"java","code":"class Solution {\n    public int[] twoSum(int[] nums, int target) {}\n}"},
                     {"lang":"Python3","langSlug":"python3","code":"class Solution:\n    pass"},
                     {"lang":"C++","langSlug":"cpp","code":null}
                   ]}}}"""
            } else {
                "{}"
            }
        }
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        val statement = service.fetchProblemDetail("two-sum")

        assertEquals(2, statement.codeSnippets.size) // code=null 的条目如实丢弃
        assertEquals("java", statement.codeSnippets[0].langSlug)
        assertTrue("twoSum(int[] nums" in statement.codeSnippets[0].code)
        assertEquals("python3", statement.codeSnippets[1].langSlug)
    }

    @Test
    fun `detail prefers chinese translatedContent over english content`() = runBlocking {
        // 旧实现事实(LeetCodeOpenChallengeService:106):translatedContent 非空优先,content 兜底。
        // CN 站点两字段并存:content=英文原题,translatedContent=中文;重写版曾取反导致 CN 全英文。
        val transport = transportFor()
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                """{"data":{"question":{"questionId":"1","titleSlug":"two-sum","title":"Two Sum",
                   "content":"<p>Given an array...</p>","translatedContent":"<p>给定一个整数数组...</p>",
                   "isPaidOnly":false,"difficulty":"Easy","topicTags":[]}}}"""
            } else {
                "{}"
            }
        }
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE_CN, LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transport))

        val statement = service.fetchProblemDetail("two-sum")

        assertEquals("<p>给定一个整数数组...</p>", statement.content)
        assertEquals(ContentFormat.HTML, statement.contentFormat)
    }

    @Test
    fun `detail keeps english content when translation missing`() = runBlocking {
        val transport = transportFor()
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                """{"data":{"question":{"questionId":"1","titleSlug":"two-sum","title":"Two Sum",
                   "content":"<p>Given an array...</p>","translatedContent":null,
                   "isPaidOnly":false,"difficulty":"Easy","topicTags":[]}}}"""
            } else {
                "{}"
            }
        }
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        val statement = service.fetchProblemDetail("two-sum")

        assertEquals("<p>Given an array...</p>", statement.content)
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

    // ------------------------------------------------------------------
    // 公司题单(WP42)
    // ------------------------------------------------------------------

    @Test
    fun `company tags map to spi company list`() = runBlocking {
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transportFor()))

        val companies = service.fetchCompanies()

        assertEquals(
            listOf(
                com.codequest.spi.CompanyTag(slug = "amazon", name = "Amazon", questionCount = 38),
                com.codequest.spi.CompanyTag(slug = "google", name = "Google", questionCount = 30),
            ),
            companies,
        )
    }

    @Test
    fun `company query passes slugs period and filters, maps page to summaries`() = runBlocking {
        val transport = transportFor()
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        val page =
            service.queryCompanyProblems(
                com.codequest.spi.CompanyProblemQuery(
                    companySlugs = listOf("amazon"),
                    interviewPeriodSlug = "thirty-days",
                    difficulty = Difficulty("medium"),
                    offset = 0,
                    limit = 20,
                ),
            )

        assertEquals(2, page.total)
        val first = page.questions.first()
        assertEquals("two-sum", first.remoteId)
        assertEquals("Two Sum", first.title)
        assertEquals(Difficulty("Medium"), first.difficulty)
        assertEquals("https://leetcode.com/problems/two-sum/", first.url)
        assertEquals(listOf("Array"), first.tags)
        assertEquals(true, first.paidOnly)
        // 1.2.1(实测修正):公司题单站内状态——"NOT_AC" 与未做不可区分→null;"AC"→true
        assertEquals(null, first.solvedOnPlatform)
        assertEquals(true, page.questions[1].solvedOnPlatform)

        val variables =
            Json
                .parseToJsonElement(transport.recorded.single { it.method == "POST_GRAPHQL" }.body!!)
                .jsonObject["variables"]!!
                .jsonObject
        assertEquals("amazon-thirty-days", variables["favoriteSlug"]!!.jsonPrimitive.content)
        assertEquals(
            "[\"MEDIUM\"]",
            variables["filtersV2"]!!.jsonObject["difficultyFilter"]!!.jsonObject["difficulties"].toString(),
        )
    }

    @Test
    fun `company query with no companies returns empty page without platform request`() = runBlocking {
        val transport = transportFor()
        val service = LeetCodeProblemService(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport))

        val page = service.queryCompanyProblems(com.codequest.spi.CompanyProblemQuery(companySlugs = emptyList()))

        assertEquals(0, page.total)
        assertTrue(page.questions.isEmpty())
        assertTrue(transport.recorded.isEmpty())
    }
}
