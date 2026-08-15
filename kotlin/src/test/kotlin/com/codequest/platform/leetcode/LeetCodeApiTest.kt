package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import com.codequest.spi.ProblemFilter
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP19:LeetCodeApi MockEngine 回放夹具——走**真实栈**
 * (MockEngine → OJHttpClient → HttpOJTransport),断言 GraphQL 请求构造
 * (query/variables/operationName/csrf 头)、REST 提交信封、响应解析与错误信封。
 */
class LeetCodeApiTest {
    private data class Recorded(
        val method: String,
        val url: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private val recorded = mutableListOf<Recorded>()

    /** GraphQL operationName → 响应 JSON(测试内按需覆盖)。 */
    private val graphqlResponses = mutableMapOf<String, String>()
    private var restHandler: suspend (Recorded) -> String = { error("unexpected ${it.method} ${it.path}") }

    private val engine =
        MockEngine { request ->
            val body = runCatching { request.body.toByteArray().decodeToString() }.getOrDefault("")
            val record =
                Recorded(
                    method = request.method.value,
                    url = request.url.toString(),
                    path = request.url.encodedPath,
                    headers = request.headers.entries().associate { it.key to it.value.first() },
                    body = body,
                )
            recorded += record
            when {
                record.method == "GET" && record.path == "/api/home/" ->
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Set-Cookie", "csrftoken=csrf-test; Path=/"),
                    )
                record.path == "/graphql/" -> {
                    val operation =
                        Json.parseToJsonElement(record.body).jsonObject["operationName"]!!.jsonPrimitive.content
                    respond(graphqlResponses[operation] ?: error("未脚本化 GraphQL 操作: $operation"))
                }
                else -> {
                    val content = restHandler(record)
                    respond(content)
                }
            }
        }

    private lateinit var client: OJHttpClient
    private lateinit var api: LeetCodeApi
    private lateinit var cnApi: LeetCodeApi

    @BeforeTest
    fun setUp() {
        client = OJHttpClient(engine)
        api = LeetCodeApi(LeetCodeVariant.LEETCODE, HttpOJTransport(client))
        cnApi = LeetCodeApi(LeetCodeVariant.LEETCODE_CN, HttpOJTransport(client))
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private fun envelopeOf(record: Recorded) = Json.parseToJsonElement(record.body).jsonObject

    private fun variablesOf(record: Recorded) = envelopeOf(record)["variables"]!!.jsonObject

    private fun graphqlRecord(operationName: String): Recorded =
        recorded.first { it.path == "/graphql/" && envelopeOf(it)["operationName"]!!.jsonPrimitive.content == operationName }

    // -------------------------------------------------------------------
    // 登录态查询
    // -------------------------------------------------------------------

    @Test
    fun `userStatus query is built per old protocol and parsed`() = runBlocking {
        graphqlResponses["globalData"] =
            """
            {"data":{"userStatus":{"isSignedIn":true,"isPremium":false,"username":"alice","realName":null,"avatar":null,"userSlug":"alice-s"}}}
            """.trimIndent()

        val status = api.fetchUserStatus()

        assertEquals(true, status.isSignedIn)
        assertEquals("alice", status.username)
        assertEquals("alice-s", status.userSlug)

        val record = graphqlRecord("globalData")
        assertEquals(LeetCodeGraphQL.GLOBAL_DATA, envelopeOf(record)["query"]!!.jsonPrimitive.content)
        assertEquals("globalData", envelopeOf(record)["operationName"]!!.jsonPrimitive.content)
        // csrf:先 GET /api/home/ 种 cookie,再读出作 x-csrftoken 头(旧实现 commonHeaders)
        assertEquals("csrf-test", record.headers["x-csrftoken"])
        assertEquals("https://leetcode.com/", record.headers["Referer"])
    }

    // -------------------------------------------------------------------
    // 题目列表:CN 站点
    // -------------------------------------------------------------------

    @Test
    fun `cn question list builds problemsetQuestionList variables and parses items`() = runBlocking {
        graphqlResponses["problemsetQuestionList"] =
            """
            {"data":{"problemsetQuestionList":{"total":2,"hasMore":false,"questions":[
              {"acRate":50.0,"difficulty":"Easy","freqBar":null,"frontendQuestionId":"1","paidOnly":false,
               "solutionNum":100,"status":null,"title":"Two Sum","titleCn":null,"titleSlug":"two-sum",
               "topicTags":[{"name":"Array","nameTranslated":null,"id":"1","slug":"array"}]},
              {"acRate":40.0,"difficulty":"Medium","freqBar":null,"frontendQuestionId":"2","paidOnly":false,
               "solutionNum":50,"status":"AC","title":"Add Two Numbers","titleCn":null,"titleSlug":"add-two-numbers",
               "topicTags":[]}
            ]}}}
            """.trimIndent()

        val page =
            cnApi.fetchQuestionPage(
                ProblemFilter(keyword = "sum", difficulty = com.codequest.core.model.Difficulty("EASY"), tags = listOf("array")),
                skip = 10,
                limit = 20,
            )

        assertEquals(2, page.total)
        assertEquals(listOf("two-sum", "add-two-numbers"), page.questions.map { it.titleSlug })
        assertEquals(listOf("Array"), page.questions[0].topicTags)
        assertEquals("1", page.questions[0].frontendQuestionId)

        val variables = variablesOf(graphqlRecord("problemsetQuestionList"))
        assertEquals(10, variables["skip"]!!.jsonPrimitive.content.toInt())
        assertEquals(20, variables["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("all-code-essentials", variables["categorySlug"]!!.jsonPrimitive.content)
        val filters = variables["filters"]!!.jsonObject
        assertEquals("sum", filters["searchKeywords"]!!.jsonPrimitive.content)
        assertEquals("EASY", filters["difficulty"]!!.jsonPrimitive.content)
        assertEquals(listOf("array"), filters["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    // -------------------------------------------------------------------
    // 题目列表:COM 站点(V2 结构)
    // -------------------------------------------------------------------

    @Test
    fun `com question list builds problemsetQuestionListV2 variables and parses items`() = runBlocking {
        graphqlResponses["problemsetQuestionListV2"] =
            """
            {"data":{"problemsetQuestionListV2":{"totalLength":1,"questions":[
              {"acRate":60.0,"difficulty":"Hard","questionFrontendId":"10","titleSlug":"regular-expression-matching",
               "title":"Regular Expression Matching","paidOnly":true,"status":null,"frequency":0.5}
            ]}}}
            """.trimIndent()

        val page = api.fetchQuestionPage(ProblemFilter(keyword = "regex"), skip = 0, limit = 50)

        assertEquals(1, page.total)
        assertEquals("regular-expression-matching", page.questions.single().titleSlug)
        assertTrue(page.questions.single().paidOnly)

        val variables = variablesOf(graphqlRecord("problemsetQuestionListV2"))
        assertEquals("regex", variables["searchKeyword"]!!.jsonPrimitive.content)
        val filters = variables["filters"]!!.jsonObject
        // 空骨架子过滤为旧实现逐字事实
        assertEquals("ALL", filters["filterCombineType"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), filters["companyFilter"]!!.jsonObject["companySlugs"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 难度/标签过滤进入 V2 结构的 difficultyFilter/topicFilter
        api.fetchQuestionPage(
            ProblemFilter(difficulty = com.codequest.core.model.Difficulty("medium"), tags = listOf("dp")),
            skip = 0,
            limit = 50,
        )
        val lastV2 = recorded.last { it.path == "/graphql/" && envelopeOf(it)["operationName"]!!.jsonPrimitive.content == "problemsetQuestionListV2" }
        val v2 = variablesOf(lastV2)["filters"]!!.jsonObject
        assertEquals(listOf("MEDIUM"), v2["difficultyFilter"]!!.jsonObject["difficulties"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("dp"), v2["topicFilter"]!!.jsonObject["topicSlugs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    // -------------------------------------------------------------------
    // 题目详情
    // -------------------------------------------------------------------

    @Test
    fun `questionData sends titleSlug variable and parses HTML content`() = runBlocking {
        graphqlResponses["questionData"] =
            """
            {"data":{"question":{"questionId":"1","frontendQuestionId":"1","title":"Two Sum","titleSlug":"two-sum",
              "content":"<p>Given an array...</p>","translatedTitle":null,"translatedContent":null,
              "isPaidOnly":false,"difficulty":"Easy",
              "topicTags":[{"name":"Array","slug":"array","translatedName":null}],"status":null}}}
            """.trimIndent()

        val question = api.fetchQuestionDetail("two-sum")

        assertEquals("1", question.questionId)
        assertEquals("<p>Given an array...</p>", question.content)
        assertEquals(listOf("Array"), question.topicTags)
        assertEquals(variablesOf(graphqlRecord("questionData"))["titleSlug"]!!.jsonPrimitive.content, "two-sum")
    }

    // -------------------------------------------------------------------
    // 题解列表(CN / COM 双查询)
    // -------------------------------------------------------------------

    @Test
    fun `solution articles use site-specific operation and parse edge nodes`() = runBlocking {
        graphqlResponses["questionTopicsList"] =
            """{"data":{"questionSolutionArticles":{"totalNum":1,"edges":[{"node":{"uuid":"u1","title":"[题解] two-sum","slug":"ts-sol"}}]}}}"""
        graphqlResponses["ugcArticleSolutionArticles"] =
            """{"data":{"ugcArticleSolutionArticles":{"totalNum":2,"edges":[
                {"node":{"uuid":"u2","title":"Official","slug":"off"}},
                {"node":{"uuid":"u3","title":"Community","slug":"comm"}}]}}}"""

        val cn = cnApi.fetchSolutionArticles("two-sum")
        val com = api.fetchSolutionArticles("two-sum")

        assertEquals(listOf("ts-sol"), cn.map { it.slug })
        assertEquals(listOf("off", "comm"), com.map { it.slug })
        assertTrue(
            recorded
                .first { it.path == "/graphql/" && envelopeOf(it)["operationName"]!!.jsonPrimitive.content == "questionTopicsList" }
                .url.startsWith("https://leetcode.cn/"),
        )
    }

    // -------------------------------------------------------------------
    // 提交(REST)与轮询(REST)
    // -------------------------------------------------------------------

    @Test
    fun `submit posts snake_case envelope with csrf headers and returns submission id`() = runBlocking {
        graphqlResponses["questionData"] =
            """{"data":{"question":{"questionId":"1234","titleSlug":"two-sum"}}}"""
        restHandler = { """{"submission_id": 42}""" }

        val submissionId = api.submitCode("1234", "two-sum", "kotlin", "fun solve() {}")

        assertEquals("42", submissionId)
        val record = recorded.last { it.method == "POST" && it.path == "/problems/two-sum/submit/" }
        val body = Json.parseToJsonElement(record.body).jsonObject
        assertEquals("kotlin", body["lang"]!!.jsonPrimitive.content)
        assertEquals("1234", body["question_id"]!!.jsonPrimitive.content)
        assertEquals("fun solve() {}", body["typed_code"]!!.jsonPrimitive.content)
        assertEquals("csrf-test", record.headers["x-csrftoken"])
        assertEquals("https://leetcode.com/", record.headers["Referer"])
    }

    @Test
    fun `submission check parses state status_msg memory runtime`() = runBlocking {
        restHandler =
            { """{"state":"SUCCESS","status_msg":"Accepted","memory":16900,"status_runtime":"4 ms","run_success":true}""" }

        val check = api.fetchSubmissionCheck("42")

        assertEquals("SUCCESS", check.state)
        assertEquals("Accepted", check.statusMsg)
        assertEquals(16900, check.memory)
        assertEquals("4 ms", check.statusRuntime)
        assertTrue(recorded.any { it.method == "GET" && it.path == "/submissions/detail/42/check/" })
    }

    // -------------------------------------------------------------------
    // 错误信封
    // -------------------------------------------------------------------

    @Test
    fun `graphql errors envelope maps to OJException`() {
        graphqlResponses["globalData"] = """{"errors":[{"message":"boom"}],"data":null}"""
        assertFailsWith<OJException> {
            runBlocking { api.fetchUserStatus() }
        }
    }

    @Test
    fun `missing data field maps to OJException`() {
        graphqlResponses["globalData"] = """{"data":null}"""
        assertFailsWith<OJException> {
            runBlocking { api.fetchUserStatus() }
        }
    }
}
