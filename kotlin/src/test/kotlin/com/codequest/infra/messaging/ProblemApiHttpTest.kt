package com.codequest.infra.messaging

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.core.model.ProblemId
import com.codequest.core.model.RateLimitedException
import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.LeetCodeApi
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformProvider
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.PlatformInfo
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP20:题目数据端点——起真实 [ResourceHttpServer] + LeetCodePlatform
 * (FakeOJTransport 注入,不上网络)+ 真实 SQLite 仓库,覆盖列表/详情/缓存命中/错误映射。
 */
class ProblemApiHttpTest {
    private val transport = FakeOJTransport()
    private lateinit var repo: ExposedProblemRepository
    private lateinit var db: com.codequest.infra.db.CodeQuestDatabase
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** 受控抛领域异常的开关(错误映射用;默认不抛)。 */
    private var failure: suspend () -> Unit = {}

    private inner class FailingPlatform : OJPlatform, ProblemQueryCapable {
        override val id = PlatformId("failing")
        override val domain = "failing.example.com"
        override val info = PlatformInfo(displayName = "Failing", templateStyle = TemplateStyle.ACM)

        override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
            flow {
                failure()
                emit(ProblemSummary("never", "Never", com.codequest.core.model.Difficulty("easy"), "https://x/"))
            }

        override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement {
            failure()
            throw IllegalStateException("unreachable")
        }
    }

    @BeforeTest
    fun setUp() {
        scriptLeetCode()
        db = newTempDatabase().first
        repo = ExposedProblemRepository(db)
        val leetcode = LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, credentialStore = fakeCredentialStore())
        val failing = FailingPlatform()
        OJPlatformRegistryTestHelper.inject(leetcode, failing)
        server =
            ResourceHttpServer(
                problemApi =
                    ProblemApi(
                        store = { repo },
                        statuses = { com.codequest.infra.db.ExposedProblemStatusStore(db) },
                    ),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        OJPlatformRegistryTestHelper.reset()
        db.close()
    }

    // ------------------------------------------------------------------
    // /api/platforms
    // ------------------------------------------------------------------

    @Test
    fun `platforms endpoint lists registry ids and display names`() {
        val body = getJson("/api/platforms")

        val platforms = body.getAsJsonArray("platforms")
        val ids = platforms.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("leetcode" in ids)
        assertTrue("failing" in ids)
        val leetcodeEntry = platforms.first { it.jsonObject["id"]!!.jsonPrimitive.content == "leetcode" }.jsonObject
        assertEquals("LeetCode", leetcodeEntry["displayName"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // /api/problems
    // ------------------------------------------------------------------

    @Test
    fun `problems endpoint returns items and persists them to local store`() {
        val body = getJson("/api/problems?platform=leetcode&limit=3")

        assertEquals("leetcode", body["platform"]!!.jsonPrimitive.content)
        val items = body.getAsJsonArray("items")
        assertEquals(3, items.size)
        val first = items[0].jsonObject
        assertEquals("problem-0", first["remoteId"]!!.jsonPrimitive.content)
        assertEquals("Problem 0", first["title"]!!.jsonPrimitive.content)
        assertEquals("Easy", first["difficulty"]!!.jsonPrimitive.content)
        assertEquals("https://leetcode.com/problems/problem-0/", first["url"]!!.jsonPrimitive.content)
        assertEquals(true, body["hasMore"]!!.jsonPrimitive.content.toBoolean())

        // 1.2.1 修复反馈:平台侧已解状态合并——无本地提交时 ac→AC / notac·null→UNSOLVED
        // (CN 实测未做/未过不可区分,「已尝试」只来自本地提交记录)
        assertEquals("AC", items[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("UNSOLVED", items[1].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("UNSOLVED", items[2].jsonObject["status"]!!.jsonPrimitive.content)

        // 端点侧落库:主档 + 标签关联
        val saved = runBlocking { repo.findByRemoteId(PlatformId("leetcode"), "problem-0") }
        assertNotNull(saved)
        assertEquals("Problem 0", saved.title)
        assertEquals("leetcode:problem-0", saved.id.value)
    }

    @Test
    fun `problems endpoint forwards keyword and difficulty filters to platform`() {
        getJson("/api/problems?platform=leetcode&keyword=two&difficulty=medium&limit=2")

        val listRequest =
            transport.recorded
                .first { it.method == "POST_GRAPHQL" && it.body!!.contains("problemsetQuestionListV2") }
        val variables = Json.parseToJsonElement(listRequest.body!!).jsonObject["variables"]!!.jsonObject
        assertEquals("two", variables["searchKeyword"]!!.jsonPrimitive.content)
        val difficulties =
            variables["filters"]!!
                .jsonObject["difficultyFilter"]!!
                .jsonObject["difficulties"]!!
        assertEquals("[\"MEDIUM\"]", difficulties.toString())
    }

    @Test
    fun `problems endpoint reports hasMore false when page is short`() {
        transport.handler = { _ -> smallPageResponse() }
        val body = getJson("/api/problems?platform=leetcode&limit=5")

        assertEquals(2, body.getAsJsonArray("items").size)
        assertEquals(false, body["hasMore"]!!.jsonPrimitive.content.toBoolean())
    }

    // ------------------------------------------------------------------
    // /api/companies 与公司题单(WP42)
    // ------------------------------------------------------------------

    @Test
    fun `companies endpoint returns company tags for company-capable platform`() {
        val body = getJson("/api/companies?platform=leetcode")

        assertEquals("leetcode", body["platform"]!!.jsonPrimitive.content)
        val items = body.getAsJsonArray("items")
        assertEquals(2, items.size)
        val amazon = items[0].jsonObject
        assertEquals("amazon", amazon["slug"]!!.jsonPrimitive.content)
        assertEquals("Amazon", amazon["name"]!!.jsonPrimitive.content)
        assertEquals("38", amazon["questionCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `companies endpoint maps non-company platform to 404`() {
        val (status, body) = get("/api/companies?platform=failing")

        assertEquals(404, status)
        assertEquals("unknown-platform", body.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `problems endpoint with company param queries company list and persists items`() {
        val body = getJson("/api/problems?platform=leetcode&company=amazon&period=thirty-days&difficulty=medium&limit=2")

        assertEquals("leetcode", body["platform"]!!.jsonPrimitive.content)
        val items = body.getAsJsonArray("items")
        assertEquals(2, items.size)
        assertEquals("company-0", items[0].jsonObject["remoteId"]!!.jsonPrimitive.content)
        // WP42:SPI 透传 paidOnly(首题会员题,状态列 🔒 数据源)
        assertEquals(true, items[0].jsonObject["paidOnly"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, items[1].jsonObject["paidOnly"]!!.jsonPrimitive.content.toBoolean())

        // favoriteQuestionList 变量:favoriteSlug = 公司 + 期间后缀,难度透传
        val request =
            transport.recorded.first { it.method == "POST_GRAPHQL" && it.body!!.contains("favoriteQuestionList") }
        val variables = Json.parseToJsonElement(request.body!!).jsonObject["variables"]!!.jsonObject
        assertEquals("amazon-thirty-days", variables["favoriteSlug"]!!.jsonPrimitive.content)
        assertEquals(
            "[\"MEDIUM\"]",
            variables["filtersV2"]!!.jsonObject["difficultyFilter"]!!.jsonObject["difficulties"].toString(),
        )

        // 公司题单同路径落库(状态列/状态筛选兼容)
        val saved = runBlocking { repo.findByRemoteId(PlatformId("leetcode"), "company-0") }
        assertNotNull(saved)
        assertEquals("leetcode:company-0", saved.id.value)
    }

    @Test
    fun `problems endpoint ignores keyword in company mode`() {
        getJson("/api/problems?platform=leetcode&company=amazon&keyword=two&limit=1")

        val request =
            transport.recorded.first { it.method == "POST_GRAPHQL" && it.body!!.contains("favoriteQuestionList") }
        val variables = Json.parseToJsonElement(request.body!!).jsonObject["variables"]!!.jsonObject
        // 旧实现事实:公司查询恒发空 searchKeyword(公司面板无关键字入口)
        assertEquals("\"\"", variables["searchKeyword"].toString())
    }

    @Test
    fun `problems endpoint maps non-company platform with company param to 404`() {
        val (status, body) = get("/api/problems?platform=failing&company=amazon")

        assertEquals(404, status)
        assertEquals("unknown-platform", body.jsonObject["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // /api/problem/{remoteId}
    // ------------------------------------------------------------------

    @Test
    fun `problem detail fetches remotely then serves from cache on second call`() {
        val first = getJson("/api/problem/two-sum?platform=leetcode")
        assertEquals("remote", first["source"]!!.jsonPrimitive.content)
        assertEquals("HTML", first["contentFormat"]!!.jsonPrimitive.content)
        assertEquals("<p>Given an array...</p>", first["content"]!!.jsonPrimitive.content)
        // 未经列表页直达:主档为桩记录,标题回退为 remoteId(列表浏览后会 upsert 补全)
        assertEquals("two-sum", first["title"]!!.jsonPrimitive.content)
        val callsAfterFirst = transport.recorded.size

        // 题面已落 problem_content
        val savedDetail = runBlocking { repo.findDetail(ProblemId("leetcode:two-sum")) }
        assertNotNull(savedDetail)
        assertEquals("<p>Given an array...</p>", savedDetail.contentMd)

        val second = getJson("/api/problem/two-sum?platform=leetcode")
        assertEquals("cache", second["source"]!!.jsonPrimitive.content)
        assertEquals(callsAfterFirst, transport.recorded.size)
    }

    @Test
    fun `problem detail after list query uses the real title from saved record`() {
        getJson("/api/problems?platform=leetcode&limit=1")

        val detail = getJson("/api/problem/problem-0?platform=leetcode")
        assertEquals("remote", detail["source"]!!.jsonPrimitive.content)
        assertEquals("Problem 0", detail["title"]!!.jsonPrimitive.content)
        assertEquals("Easy", detail["difficulty"]!!.jsonPrimitive.content)
        assertEquals("https://leetcode.com/problems/problem-0/", detail["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `problem detail persists statement and main record for uncached problem`() {
        getJson("/api/problem/two-sum?platform=leetcode")

        val saved = runBlocking { repo.findByRemoteId(PlatformId("leetcode"), "two-sum") }
        assertNotNull(saved)
        assertEquals("two-sum", saved.title)
    }

    // ------------------------------------------------------------------
    // 错误映射
    // ------------------------------------------------------------------

    @Test
    fun `unknown platform maps to 404`() {
        val (status, body) = get("/api/problems?platform=nope")
        assertEquals(404, status)
        assertEquals("unknown-platform", body.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing platform param maps to 400`() {
        val (status, body) = get("/api/problems")
        assertEquals(400, status)
        assertEquals("bad-request", body.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auth expired maps to 401`() {
        failure = { throw AuthExpiredException("login required") }
        val (status, body) = get("/api/problem/x?platform=failing")
        assertEquals(401, status)
        assertEquals("auth-expired", body.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rate limited maps to 429 with retry-after header`() {
        failure = { throw RateLimitedException("slow down", retryAfterMs = 1500) }
        val response = getRaw("/api/problem/x?platform=failing")
        assertEquals(429, response.statusCode())
        assertEquals("rate-limited", Json.parseToJsonElement(response.body()).jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("2", response.headers().firstValue("Retry-After").orElseThrow())
    }

    @Test
    fun `generic oj exception maps to 502`() {
        failure = { throw OJException("site broke") }
        val (status, body) = get("/api/problem/x?platform=failing")
        assertEquals(502, status)
        assertEquals("oj-error", body.jsonObject["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun scriptLeetCode() {
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                when (Json.parseToJsonElement(request.body!!).jsonObject["operationName"]!!.jsonPrimitive.content) {
                    "problemsetQuestionListV2" -> {
                        val variables = Json.parseToJsonElement(request.body!!).jsonObject["variables"]!!.jsonObject
                        val skip = variables["skip"]!!.jsonPrimitive.content.toInt()
                        val limit = variables["limit"]!!.jsonPrimitive.content.toInt()
                        val total = 250
                        val items =
                            (skip until minOf(skip + limit, total)).joinToString(",") { i ->
                                // 1.2.1:首题 ac→已解;次题 notac 与其余 null 均→未解
                                // (实测 CN 站未做/未过不可区分,「已尝试」只来自本地提交)
                                val status =
                                    when (i) {
                                        0 -> "\"ac\""
                                        1 -> "\"notac\""
                                        else -> "null"
                                    }
                                """{"acRate":50.0,"difficulty":"Easy","questionFrontendId":"$i",
                                   "titleSlug":"problem-$i","title":"Problem $i","paidOnly":false,"status":$status,"frequency":null}"""
                            }
                        """{"data":{"problemsetQuestionListV2":{"totalLength":$total,"questions":[$items]}}}"""
                    }
                    "questionData" ->
                        """{"data":{"question":{"questionId":"1","titleSlug":"two-sum","title":"Two Sum",
                           "content":"<p>Given an array...</p>","translatedContent":null,"isPaidOnly":false,
                           "difficulty":"Easy","topicTags":[]}}}"""
                    // WP42:公司列表(COM 走 data.companyTags)
                    "questionCompanyTagsV2" ->
                        """{"data":{"companyTags":[
                           {"name":"Amazon","slug":"amazon","id":"1","questionCount":38},
                           {"name":"Google","slug":"google","id":"2","questionCount":30}]}}"""
                    // WP42:公司题单(favoriteSlug 分页游走;首题会员题)
                    "favoriteQuestionList" -> {
                        val variables = Json.parseToJsonElement(request.body!!).jsonObject["variables"]!!.jsonObject
                        val skip = variables["skip"]!!.jsonPrimitive.content.toInt()
                        val limit = variables["limit"]!!.jsonPrimitive.content.toInt()
                        val total = 40
                        val items =
                            (skip until minOf(skip + limit, total)).joinToString(",") { i ->
                                """{"difficulty":"Medium","id":"$i","paidOnly":${i == 0},"questionFrontendId":"$i","status":null,
                                   "title":"Company $i","titleSlug":"company-$i","translatedTitle":null,"isInMyFavorites":false,
                                   "frequency":null,"acRate":50.0,"topicTags":[]}"""
                            }
                        """{"data":{"favoriteQuestionList":{"total":$total,"questions":[$items]}}}"""
                    }
                    else -> "{}"
                }
            } else {
                "{}"
            }
        }
    }

    private fun smallPageResponse(): String {
        val items =
            (0 until 2).joinToString(",") { i ->
                """{"acRate":50.0,"difficulty":"Easy","questionFrontendId":"$i",
                   "titleSlug":"problem-$i","title":"Problem $i","paidOnly":false,"status":null,"frequency":null}"""
            }
        return """{"data":{"problemsetQuestionListV2":{"totalLength":2,"questions":[$items]}}}"""
    }

    private fun getRaw(path: String): HttpResponse<String> =
        client
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

    private fun get(path: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response = getRaw(path)
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun getJson(path: String): kotlinx.serialization.json.JsonObject = get(path).second

    private fun kotlinx.serialization.json.JsonObject.getAsJsonArray(name: String): List<kotlinx.serialization.json.JsonElement> =
        this[name]!!.jsonArray.toList()

    private fun fakeCredentialStore(): com.codequest.spi.OJCredentialStore =
        object : com.codequest.spi.OJCredentialStore {
            override fun save(
                platformId: PlatformId,
                secret: String,
            ) = Unit

            override fun load(platformId: PlatformId): String? = null

            override fun remove(platformId: PlatformId) = Unit
        }
}

/** 注册表注入/恢复助手(与 spi.OJPlatformRegistryTest 同法,跨包复用)。 */
object OJPlatformRegistryTestHelper {
    private class SingletonProvider(private val platform: OJPlatform) : OJPlatformProvider {
        override fun create() = platform
    }

    fun inject(vararg platforms: OJPlatform) {
        val providers = platforms.map(::SingletonProvider)
        OJPlatformRegistry.providerSource = { providers }
    }

    fun reset() {
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }
}

private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
    get() = this as kotlinx.serialization.json.JsonArray
