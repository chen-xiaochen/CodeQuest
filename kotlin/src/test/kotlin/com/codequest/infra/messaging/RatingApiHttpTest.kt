package com.codequest.infra.messaging

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.ProblemRating
import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.ExposedProblemStatusStore
import com.codequest.infra.db.ExposedRatingRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.infra.rating.RatingService
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.OJPlatform
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
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration-ish unit] 1.7.0 F1(WP60):评分三端点(status/refresh/user-contest)+
 * /api/problems 行级 rating 装饰(有分/无分/非 LC null)——真实 ResourceHttpServer +
 * 真实 SQLite 仓库 + LeetCodePlatform(FakeOJTransport,不上网络)。
 */
class RatingApiHttpTest {
    private val transport = FakeOJTransport()
    private lateinit var db: CodeQuestDatabase
    private lateinit var ratingRepo: ExposedRatingRepository
    private lateinit var problemRepo: ExposedProblemRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** refresh 数据源脚本(默认不可达——刷新用例按需覆盖)。 */
    private var remote: suspend () -> String = { error("unexpected refresh") }

    /** GraphQL operationName → 响应体(测试按需覆盖)。 */
    private val graphqlScripts = mutableMapOf<String, String>()

    @BeforeTest
    fun setUp() {
        scriptLeetCode()
        db = newTempDatabase().first
        ratingRepo = ExposedRatingRepository(db)
        problemRepo = ExposedProblemRepository(db)
        val ratingService =
            RatingService(
                ratings = { ratingRepo },
                fetchRemote = { remote() },
            )
        val leetcode = LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, credentialStore = fakeCredentialStore())
        OJPlatformRegistryTestHelper.inject(leetcode, PlainPlatform())
        server =
            ResourceHttpServer(
                problemApi =
                    ProblemApi(
                        store = { problemRepo },
                        statuses = { ExposedProblemStatusStore(db) },
                        ratings = ratingService,
                    ),
                ratingApi = RatingApi(ratings = ratingService),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        OJPlatformRegistryTestHelper.reset()
        db.close()
    }

    /** 无题号概念的普通平台(评分装饰对非 LC 平台恒 null 的对照)。 */
    private class PlainPlatform : OJPlatform, ProblemQueryCapable {
        override val id = PlatformId("plain")
        override val domain = "plain.example.com"
        override val info = PlatformInfo(displayName = "Plain", templateStyle = TemplateStyle.ACM)

        override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
            flow {
                emit(
                    ProblemSummary(
                        remoteId = "1927A",
                        title = "Plain Problem",
                        difficulty = Difficulty("easy"),
                        url = "https://x/1927A",
                    ),
                )
            }

        override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement = throw UnsupportedOperationException()
    }

    // ------------------------------------------------------------------
    // GET /api/rating/status
    // ------------------------------------------------------------------

    @Test
    fun `rating status reflects db snapshot`() {
        seedRatings("1" to 1317.5, "2" to 2200.0)

        val body = getJson("/api/rating/status")

        assertEquals("db", body["source"]!!.jsonPrimitive.content)
        assertEquals("2", body["count"]!!.jsonPrimitive.content)
        // DB 态无刷新时间(重启后内存无记录)
        assertTrue(body.isNullValue("updatedAt"))
    }

    @Test
    fun `rating status falls back to bundled source when db is empty`() {
        val body = getJson("/api/rating/status")

        assertEquals("bundled", body["source"]!!.jsonPrimitive.content)
        assertTrue(body["count"]!!.jsonPrimitive.content.toInt() > 0)
    }

    // ------------------------------------------------------------------
    // POST /api/rating/refresh
    // ------------------------------------------------------------------

    @Test
    fun `rating refresh upserts zerotrac data and reloads memory`() {
        seedRatings("1" to 1317.5)
        remote = { ZEROTRAC_SAMPLE }

        val body = postJson("/api/rating/refresh")

        assertEquals("2", body["count"]!!.jsonPrimitive.content)
        assertTrue(body["updatedAt"] != null)

        // DB 与内存快照均换新,后续 status 读到 db 态 + 刷新时间
        runBlocking {
            assertEquals(mapOf("1" to 1500.5, "2" to 2200.75), ratingRepo.loadAll())
        }
        val status = getJson("/api/rating/status")
        assertEquals("db", status["source"]!!.jsonPrimitive.content)
        assertEquals("2", status["count"]!!.jsonPrimitive.content)
        assertTrue(status["updatedAt"] != null)
    }

    @Test
    fun `rating refresh failure maps to 502 and keeps db intact`() {
        seedRatings("1" to 1317.5)
        remote = { throw IllegalStateException("network down") }

        val (statusCode, body) = post("/api/rating/refresh")

        assertEquals(502, statusCode)
        assertEquals("refresh-failed", body["error"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("network down"))
        runBlocking {
            assertEquals(mapOf("1" to 1317.5), ratingRepo.loadAll()) // DB 未被破坏
        }
    }

    // ------------------------------------------------------------------
    // GET /api/rating/user-contest
    // ------------------------------------------------------------------

    @Test
    fun `user contest returns ranking for signed-in user`() {
        val body = getJson("/api/rating/user-contest?platform=leetcode")

        assertEquals("1875.3", body["rating"]!!.jsonPrimitive.content)
        assertEquals("42", body["attendedContestsCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user contest degrades to double null when not signed in`() {
        graphqlScripts["globalData"] =
            """{"data":{"userStatus":{"isSignedIn":false,"username":null,"userSlug":null}}}"""

        val body = getJson("/api/rating/user-contest?platform=leetcode")

        assertTrue(body.isNullValue("rating"))
        assertTrue(body.isNullValue("attendedContestsCount"))
    }

    @Test
    fun `user contest degrades to double null on query failure`() {
        // 未登录走 errors 信封(OJException)——端点吞掉,双 null 不抛错
        graphqlScripts["userContestRankingInfo"] = """{"errors":[{"message":"not signed in"}],"data":null}"""

        val body = getJson("/api/rating/user-contest?platform=leetcode")

        assertTrue(body.isNullValue("rating"))
        assertTrue(body.isNullValue("attendedContestsCount"))
    }

    @Test
    fun `user contest requires capable platform`() {
        assertEquals(400, get("/api/rating/user-contest").first)
        assertEquals(404, get("/api/rating/user-contest?platform=nope").first)
        assertEquals("unknown-platform", get("/api/rating/user-contest?platform=plain").second["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // /api/problems 行级 rating 装饰(三模式共用收尾)
    // ------------------------------------------------------------------

    @Test
    fun `problems rows carry rating by frontend question id`() {
        seedRatings("0" to 1333.5, "1" to 2500.25)

        val items = getJson("/api/problems?platform=leetcode&limit=3").getAsJsonArray("items")

        // 有分两题按题号命中;无分题(id 2 不在评分表)为 null
        assertEquals("1333.5", items[0].jsonObject["rating"]!!.jsonPrimitive.content)
        assertEquals("2500.25", items[1].jsonObject["rating"]!!.jsonPrimitive.content)
        assertEquals("null", items[2].jsonObject["rating"]!!.jsonPrimitive.content)
    }

    @Test
    fun `favorite mode rows share the same rating decoration`() {
        seedRatings("0" to 1333.5)

        val items = getJson("/api/problems?platform=leetcode&favorite=my-favorites&limit=2").getAsJsonArray("items")

        assertEquals("1333.5", items[0].jsonObject["rating"]!!.jsonPrimitive.content)
        assertEquals("null", items[1].jsonObject["rating"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non leetcode platform rows keep rating null`() {
        seedRatings("1927A" to 999.0) // 即便误存,无 frontendQuestionId 也查不到

        val items = getJson("/api/problems?platform=plain&limit=1").getAsJsonArray("items")

        assertEquals("null", items[0].jsonObject["rating"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun seedRatings(vararg entries: Pair<String, Double>) {
        runBlocking {
            ratingRepo.upsertAll(entries.map { ProblemRating(it.first, it.second, Instant.EPOCH) })
        }
    }

    private fun scriptLeetCode() {
        graphqlScripts["globalData"] =
            """{"data":{"userStatus":{"isSignedIn":true,"isPremium":false,"username":"alice","realName":null,"avatar":null,"userSlug":"alice-s"}}}"""
        graphqlScripts["userContestRankingInfo"] =
            """{"data":{"userContestRanking":{"attendedContestsCount":42,"rating":1875.3,"globalRanking":12034,"topPercentage":7.25}}}"""
        transport.handler = { request ->
            if (request.method != "POST_GRAPHQL") {
                "{}"
            } else {
                val operation = Json.parseToJsonElement(request.body!!).jsonObject["operationName"]!!.jsonPrimitive.content
                when (operation) {
                "problemsetQuestionListV2" -> {
                    val variables = Json.parseToJsonElement(request.body!!).jsonObject["variables"]!!.jsonObject
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
                "favoriteQuestionList" ->
                    """{"data":{"favoriteQuestionList":{"total":2,"questions":[
                       {"difficulty":"Medium","id":"0","paidOnly":false,"questionFrontendId":"0","status":null,
                        "title":"Fav 0","titleSlug":"fav-0","translatedTitle":null,"isInMyFavorites":false,
                        "frequency":null,"acRate":50.0,"topicTags":[]},
                       {"difficulty":"Hard","id":"1","paidOnly":false,"questionFrontendId":"1","status":null,
                        "title":"Fav 1","titleSlug":"fav-1","translatedTitle":null,"isInMyFavorites":false,
                        "frequency":null,"acRate":50.0,"topicTags":[]}]}}}"""
                    else -> graphqlScripts[operation] ?: "{}"
                }
            }
        }
    }

    private fun getRaw(
        path: String,
        method: String = "GET",
    ): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response = getRaw(path)
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun post(path: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response = getRaw(path, "POST")
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun getJson(path: String): kotlinx.serialization.json.JsonObject = get(path).second

    private fun postJson(path: String): kotlinx.serialization.json.JsonObject = post(path).second

    private fun kotlinx.serialization.json.JsonObject.getAsJsonArray(name: String): List<kotlinx.serialization.json.JsonElement> =
        this[name]!!.jsonArray.toList()

    private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
        get() = this as kotlinx.serialization.json.JsonArray

    /** 字段缺省或 JSON null 均视为 null(explicitNulls 下 null 显式序列化为 JsonNull)。 */
    private fun kotlinx.serialization.json.JsonObject.isNullValue(name: String): Boolean =
        this[name] == null || this[name] is kotlinx.serialization.json.JsonNull

    private fun fakeCredentialStore(): com.codequest.spi.OJCredentialStore =
        object : com.codequest.spi.OJCredentialStore {
            override fun save(
                platformId: PlatformId,
                secret: String,
            ) = Unit

            override fun load(platformId: PlatformId): String? = null

            override fun remove(platformId: PlatformId) = Unit
        }

    private companion object {
        private const val ZEROTRAC_SAMPLE =
            """[{"Rating": 1500.5, "ID": 1, "Title": "Two Sum", "TitleSlug": "two-sum"},
               {"Rating": 2200.75, "ID": 2, "Title": "Add Two Numbers", "TitleSlug": "add-two-numbers"}]"""
    }
}
