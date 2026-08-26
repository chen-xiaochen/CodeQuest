package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import com.codequest.spi.ProblemFilter
import com.codequest.spi.TagInfo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
    // 学习计划(1.6.0)
    // -------------------------------------------------------------------

    @Test
    fun `study plan detail flattens groups with bilingual titles and rich tags`() = runBlocking {
        graphqlResponses["studyPlanV2Detail"] =
            """
            {"data":{"studyPlanV2Detail":{"slug":"top-100-liked","name":"LeetCode 热题 100","premiumOnly":false,
              "planSubGroups":[
                {"name":"哈希","questionNum":1,"questions":[
                  {"title":"Two Sum","titleSlug":"two-sum","translatedTitle":"两数之和","questionFrontendId":"1",
                   "difficulty":"EASY","paidOnly":false,
                   "topicTags":[{"name":"Array","nameTranslated":"数组","slug":"array"},
                                {"name":"Hash Table","nameTranslated":"哈希表","slug":"hash-table"}]}]},
                {"name":"双指针","questionNum":1,"questions":[
                  {"title":"Container With Most Water","titleSlug":"container-with-most-water","translatedTitle":"盛最多水的容器",
                   "questionFrontendId":"11","difficulty":"MEDIUM","paidOnly":true,"topicTags":[]}]}]}}}
            """.trimIndent()

        val page = cnApi.fetchStudyPlanDetail("top-100-liked")

        assertEquals(2, page.total)
        assertEquals(listOf("two-sum", "container-with-most-water"), page.questions.map { it.titleSlug })
        // 双语:translatedTitle 非空为主标题,英文进 titleSecondary;缺失侧为 null
        assertEquals(listOf("两数之和", "盛最多水的容器"), page.questions.map { it.title })
        assertEquals(listOf("Two Sum", "Container With Most Water"), page.questions.map { it.titleSecondary })
        assertEquals(listOf(TagInfo("Array", "array", "数组"), TagInfo("Hash Table", "hash-table", "哈希表")), page.questions[0].topicTags)
        assertTrue(page.questions[1].paidOnly)

        val record = graphqlRecord("studyPlanV2Detail")
        assertEquals(LeetCodeGraphQL.STUDY_PLAN_DETAIL, envelopeOf(record)["query"]!!.jsonPrimitive.content)
        assertEquals("top-100-liked", variablesOf(record)["planSlug"]!!.jsonPrimitive.content)
    }

    @Test
    fun `study plan detail missing slug surfaces as OJException`() = runBlocking {
        // slug 在目标站点不存在时 data.studyPlanV2Detail 为 null(2026-08-26 COM 实测 CN 专属计划)
        graphqlResponses["studyPlanV2Detail"] = """{"data":{"studyPlanV2Detail":null}}"""

        assertFailsWith<OJException> { cnApi.fetchStudyPlanDetail("cn-only-plan") }
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
        assertEquals(listOf(TagInfo("Array", "array")), page.questions[0].topicTags)
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

    @Test
    fun `cn question list prefers titleCn and falls back to english title`() = runBlocking {
        // 旧实现事实(UI 层三处消费):titleCn 非空优先,title 兜底——CN 站点列表展示中文,
        // 重写版曾直取 title 导致 CN 列表全英文。
        graphqlResponses["problemsetQuestionList"] =
            """
            {"data":{"problemsetQuestionList":{"total":2,"hasMore":false,"questions":[
              {"acRate":50.0,"difficulty":"Easy","freqBar":null,"frontendQuestionId":"1","paidOnly":false,
               "solutionNum":100,"status":null,"title":"Two Sum","titleCn":"两数之和","titleSlug":"two-sum",
               "topicTags":[]},
              {"acRate":40.0,"difficulty":"Medium","freqBar":null,"frontendQuestionId":"2","paidOnly":false,
               "solutionNum":50,"status":null,"title":"Add Two Numbers","titleCn":null,"titleSlug":"add-two-numbers",
               "topicTags":[]}
            ]}}}
            """.trimIndent()

        val page = cnApi.fetchQuestionPage(ProblemFilter(), skip = 0, limit = 50)

        assertEquals(listOf("两数之和", "Add Two Numbers"), page.questions.map { it.title })
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

    @Test
    fun `solution articles orderBy defaults to HOT on both sites`() = runBlocking {
        // 旧实现默认 orderBy=HOT(WP19 沿用);WP41 起可由调用方覆盖
        graphqlResponses["questionTopicsList"] = emptyEdges("questionSolutionArticles")
        graphqlResponses["ugcArticleSolutionArticles"] = emptyEdges("ugcArticleSolutionArticles")

        cnApi.fetchSolutionArticles("two-sum")
        api.fetchSolutionArticles("two-sum")

        assertEquals("HOT", variablesOf(graphqlRecord("questionTopicsList"))["orderBy"]!!.jsonPrimitive.content)
        assertEquals("HOT", variablesOf(graphqlRecord("ugcArticleSolutionArticles"))["orderBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `solution articles newest maps to NEWEST_TO_OLDEST on CN and MOST_RECENT on COM`() = runBlocking {
        // 旧 LeetCodeQuestionSolutionArticlesOrderBy.Newest 事实:CN/COM 变量名不同
        graphqlResponses["questionTopicsList"] = emptyEdges("questionSolutionArticles")
        graphqlResponses["ugcArticleSolutionArticles"] = emptyEdges("ugcArticleSolutionArticles")

        cnApi.fetchSolutionArticles("two-sum", "newest")
        api.fetchSolutionArticles("two-sum", "newest")

        assertEquals("NEWEST_TO_OLDEST", variablesOf(graphqlRecord("questionTopicsList"))["orderBy"]!!.jsonPrimitive.content)
        assertEquals("MOST_RECENT", variablesOf(graphqlRecord("ugcArticleSolutionArticles"))["orderBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `solution articles mostUpvote maps identically on both sites`() = runBlocking {
        graphqlResponses["questionTopicsList"] = emptyEdges("questionSolutionArticles")
        graphqlResponses["ugcArticleSolutionArticles"] = emptyEdges("ugcArticleSolutionArticles")

        cnApi.fetchSolutionArticles("two-sum", "mostUpvote")
        api.fetchSolutionArticles("two-sum", "mostUpvote")

        assertEquals("MOST_UPVOTE", variablesOf(graphqlRecord("questionTopicsList"))["orderBy"]!!.jsonPrimitive.content)
        assertEquals("MOST_UPVOTE", variablesOf(graphqlRecord("ugcArticleSolutionArticles"))["orderBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `solution articles oldest maps on CN and sends json null on COM`() = runBlocking {
        // 旧事实:Oldest 仅 CN 有对应值(OLDEST_TO_NEWEST);COM 发 Json.Null
        graphqlResponses["questionTopicsList"] = emptyEdges("questionSolutionArticles")
        graphqlResponses["ugcArticleSolutionArticles"] = emptyEdges("ugcArticleSolutionArticles")

        cnApi.fetchSolutionArticles("two-sum", "oldest")
        api.fetchSolutionArticles("two-sum", "oldest")

        assertEquals("OLDEST_TO_NEWEST", variablesOf(graphqlRecord("questionTopicsList"))["orderBy"]!!.jsonPrimitive.content)
        assertTrue(variablesOf(graphqlRecord("ugcArticleSolutionArticles"))["orderBy"] is JsonNull)
    }

    @Test
    fun `solution articles unknown or blank orderBy falls back to HOT`() = runBlocking {
        graphqlResponses["questionTopicsList"] = emptyEdges("questionSolutionArticles")
        graphqlResponses["ugcArticleSolutionArticles"] = emptyEdges("ugcArticleSolutionArticles")

        cnApi.fetchSolutionArticles("two-sum", "bogus")
        api.fetchSolutionArticles("two-sum", " ")

        assertEquals("HOT", variablesOf(graphqlRecord("questionTopicsList"))["orderBy"]!!.jsonPrimitive.content)
        assertEquals("HOT", variablesOf(graphqlRecord("ugcArticleSolutionArticles"))["orderBy"]!!.jsonPrimitive.content)
    }

    private fun emptyEdges(dataField: String): String = """{"data":{"$dataField":{"totalNum":0,"edges":[]}}}"""

    @Test
    fun `solution article detail uses site-specific operation and parses markdown content`() = runBlocking {
        // 旧 getSolutionArticle 事实:CN discussTopic → data.solutionArticle、
        // COM ugcArticleSolutionArticle → data.ugcArticleSolutionArticle,变量均为 {slug}
        graphqlResponses["discussTopic"] =
            """{"data":{"solutionArticle":{"uuid":"u1","title":"[题解] two-sum","content":"## 思路\n\n哈希表 ${'$'}O(n)${'$'}","slug":"ts-sol"}}}"""
        graphqlResponses["ugcArticleSolutionArticle"] =
            """{"data":{"ugcArticleSolutionArticle":{"uuid":"u2","title":"Official","content":"one\\ntwo \\u4e2d","slug":"off"}}}"""

        val cnDetail = cnApi.fetchSolutionArticleDetail("ts-sol")
        val comDetail = api.fetchSolutionArticleDetail("off")

        assertEquals("[题解] two-sum", cnDetail.title)
        assertEquals("ts-sol", cnDetail.slug)
        assertTrue(cnDetail.content.startsWith("## 思路"), cnDetail.content)
        // COM 站点原始 content 保持二次转义形态原样透传(反转义在 LeetCodeSolutions 层);
        // 断言 raw string 中单反斜杠即字面序列:JSON 已剥一层,此处仍剩 \n、中
        assertEquals("""one\ntwo \u4e2d""", comDetail.content)

        val cnRecord = graphqlRecord("discussTopic")
        assertEquals("discussTopic", envelopeOf(cnRecord)["operationName"]!!.jsonPrimitive.content)
        assertEquals("ts-sol", variablesOf(cnRecord)["slug"]!!.jsonPrimitive.content)
        val comRecord = graphqlRecord("ugcArticleSolutionArticle")
        assertEquals("off", variablesOf(comRecord)["slug"]!!.jsonPrimitive.content)
    }

    @Test
    fun `solution article detail maps missing article or content to OJException`() {
        graphqlResponses["discussTopic"] = """{"data":{"solutionArticle":null}}"""
        graphqlResponses["ugcArticleSolutionArticle"] =
            """{"data":{"ugcArticleSolutionArticle":{"uuid":"u2","title":"Official","content":null,"slug":"off"}}}"""

        assertFailsWith<OJException> {
            runBlocking { cnApi.fetchSolutionArticleDetail("ghost") }
        }
        assertFailsWith<OJException> {
            runBlocking { api.fetchSolutionArticleDetail("off") }
        }
    }

    // -------------------------------------------------------------------
    // 公司题单(WP42):公司列表 + favoriteQuestionList
    // -------------------------------------------------------------------

    @Test
    fun `company tags parse companyTagsV2 companyTagInfoNodes on cn`() = runBlocking {
        // 旧 getQuestionCompanyTags 事实:CN 响应 data.companyTagsV2.companyTagInfoNodes,
        // questionCount 为 companyQuestionNum 的别名(由查询文本指定)
        graphqlResponses["questionCompanyTagsV2"] =
            """
            {"data":{"companyTagsV2":{"companyTagInfoNodes":[
              {"name":"亚马逊","slug":"amazon","id":"1","questionCount":40},
              {"name":"Google","slug":"google","id":"2","questionCount":35}
            ]}}}
            """.trimIndent()

        val tags = cnApi.fetchCompanyTags()

        assertEquals(
            listOf(
                LeetCodeApi.LeetCodeCompanyTag("亚马逊", "amazon", 40),
                LeetCodeApi.LeetCodeCompanyTag("Google", "google", 35),
            ),
            tags,
        )
        val record = graphqlRecord("questionCompanyTagsV2")
        assertEquals(LeetCodeGraphQL.QUESTION_COMPANY_TAGS_V2_CN, envelopeOf(record)["query"]!!.jsonPrimitive.content)
        // 变量为空对象(旧实现事实)
        assertEquals(0, variablesOf(record).size)
    }

    @Test
    fun `company tags parse companyTags on com`() = runBlocking {
        graphqlResponses["questionCompanyTagsV2"] =
            """{"data":{"companyTags":[{"name":"Amazon","slug":"amazon","id":"1","questionCount":38}]}}"""

        val tags = api.fetchCompanyTags()

        assertEquals(listOf(LeetCodeApi.LeetCodeCompanyTag("Amazon", "amazon", 38)), tags)
        assertEquals(
            LeetCodeGraphQL.QUESTION_COMPANY_TAGS_V2,
            envelopeOf(graphqlRecord("questionCompanyTagsV2"))["query"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `company question list builds favoriteQuestionList variables and parses items`() = runBlocking {
        graphqlResponses["favoriteQuestionList"] =
            """
            {"data":{"favoriteQuestionList":{"total":2,"questions":[
              {"difficulty":"Medium","id":"1","paidOnly":true,"questionFrontendId":"1","status":null,
               "title":"Two Sum","titleSlug":"two-sum","translatedTitle":"两数之和","isInMyFavorites":false,
               "frequency":0.5,"acRate":50.0,"topicTags":[{"name":"Array","nameTranslated":"数组","slug":"array"}]},
              {"difficulty":"Easy","id":"2","paidOnly":false,"questionFrontendId":"2","status":"AC",
               "title":"Reverse Integer","titleSlug":"reverse-integer","translatedTitle":null,
               "isInMyFavorites":false,"frequency":null,"acRate":40.0,"topicTags":[]}
            ]}}}
            """.trimIndent()

        val page =
            cnApi.fetchCompanyQuestionPage(
                companySlugs = listOf("amazon", "google"),
                interviewPeriodSlug = "thirty-days",
                filter = ProblemFilter(difficulty = com.codequest.core.model.Difficulty("medium"), tags = listOf("array")),
                skip = 10,
                limit = 20,
            )

        assertEquals(2, page.total)
        // translatedTitle 非空优先(旧 LeetCodeChallengeListItem.titleCn 语义),空回退 title;
        // 1.6.0:另一侧标题存 titleSecondary(中文为主英文为辅)
        assertEquals(listOf("两数之和", "Reverse Integer"), page.questions.map { it.title })
        assertEquals(listOf("Two Sum", null), page.questions.map { it.titleSecondary })
        assertEquals(listOf("two-sum", "reverse-integer"), page.questions.map { it.titleSlug })
        assertEquals("1", page.questions[0].frontendQuestionId)
        assertTrue(page.questions[0].paidOnly)
        assertEquals(listOf(TagInfo("Array", "array", "数组")), page.questions[0].topicTags)

        val record = graphqlRecord("favoriteQuestionList")
        assertEquals(LeetCodeGraphQL.FAVORITE_QUESTION_LIST_CN, envelopeOf(record)["query"]!!.jsonPrimitive.content)
        val variables = variablesOf(record)
        assertEquals(10, variables["skip"]!!.jsonPrimitive.content.toInt())
        assertEquals(20, variables["limit"]!!.jsonPrimitive.content.toInt())
        // favoriteSlug = 首公司 + 期间后缀;其余公司进 companyFilter(旧 searchCompanyChallenges 事实)
        assertEquals("amazon-thirty-days", variables["favoriteSlug"]!!.jsonPrimitive.content)
        val filters = variables["filtersV2"]!!.jsonObject
        assertEquals(
            listOf("google"),
            filters["companyFilter"]!!.jsonObject["companySlugs"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("MEDIUM"),
            filters["difficultyFilter"]!!.jsonObject["difficulties"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("array"),
            filters["topicFilter"]!!.jsonObject["topicSlugs"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("ALL", filters["filterCombineType"]!!.jsonPrimitive.content)
        // 排序兜底与空关键字为旧实现逐字事实
        assertEquals("CUSTOM", variables["sortBy"]!!.jsonObject["sortField"]!!.jsonPrimitive.content)
        assertEquals("ASCENDING", variables["sortBy"]!!.jsonObject["sortOrder"]!!.jsonPrimitive.content)
        assertEquals("", variables["searchKeyword"]!!.jsonPrimitive.content)
    }

    @Test
    fun `company question list without period sends bare favorite slug on com`() = runBlocking {
        graphqlResponses["favoriteQuestionList"] =
            """{"data":{"favoriteQuestionList":{"total":0,"questions":[]}}}"""

        api.fetchCompanyQuestionPage(listOf("amazon"), null, ProblemFilter(), skip = 0, limit = 50)

        val record = graphqlRecord("favoriteQuestionList")
        assertEquals(LeetCodeGraphQL.FAVORITE_QUESTION_LIST, envelopeOf(record)["query"]!!.jsonPrimitive.content)
        assertEquals("amazon", variablesOf(record)["favoriteSlug"]!!.jsonPrimitive.content)
        // 单公司:companyFilter 为空列表
        assertEquals(
            emptyList<String>(),
            variablesOf(record)["filtersV2"]!!.jsonObject["companyFilter"]!!.jsonObject["companySlugs"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `company question list with empty slugs makes no request`() = runBlocking {
        // 旧 Presenter 守卫:未选公司不发查询
        val page = api.fetchCompanyQuestionPage(emptyList(), "thirty-days", ProblemFilter(), skip = 0, limit = 50)

        assertEquals(0, page.total)
        assertTrue(page.questions.isEmpty())
        assertTrue(recorded.none { it.path == "/graphql/" })
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
