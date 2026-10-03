package com.codequest.infra.messaging

import com.codequest.platform.codeforces.CodeForcesPlatform
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.platform.leetcode.LeetCodeCnPlatform
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.FavoriteWriteCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.ProblemHintsCapable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [integration] 1.9.0 F1/F2(WP72):/api/favorite/toggle(成功 / 未登录 401 /
 * 站方拒绝 502 / 无能力平台 404 / 参数 400)+ /api/problem/{remoteId}/hints
 * (200 / 降级空表 / 404 / 400)+ 平台能力归属(LC 两变体 is 双能力、CF is
 * not)——真实 ResourceHttpServer + 真实平台(FakeOJTransport,不上网络)。
 */
class FavoriteWriteApiHttpTest {
    private val transport = FakeOJTransport()
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                when (operationOf(request.body!!)) {
                    "favoritesLists" -> FAVORITES_SAMPLE
                    "addQuestionToFavorite" -> """{"data":{"addQuestionToFavorite":{"ok":true,"error":null}}}"""
                    "removeQuestionFromFavorite" -> """{"data":{"removeQuestionFromFavorite":{"ok":true,"error":null}}}"""
                    "questionHints" -> HINTS_SAMPLE
                    else -> "{}"
                }
            } else {
                "{}"
            }
        }
        OJPlatformRegistryTestHelper.inject(
            LeetCodeCnPlatform(transport, InMemoryCredentialStore()),
            LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, InMemoryCredentialStore()),
            CodeForcesPlatform(transport, InMemoryCredentialStore()),
        )
        server =
            ResourceHttpServer(
                favoriteWriteApi = FavoriteWriteApi(),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        OJPlatformRegistryTestHelper.reset()
    }

    // ------------------------------------------------------------------
    // POST /api/favorite/toggle
    // ------------------------------------------------------------------

    @Test
    fun `toggle add returns ok true`() {
        val (status, body) = post("/api/favorite/toggle", """{"platformId":"leetcodecn","questionId":"1","add":true}""")

        assertEquals(200, status)
        assertEquals("true", body["ok"]!!.jsonPrimitive.content)
        // mutation 走通:先 favoritesLists 定位默认夹,再 addQuestionToFavorite
        assertTrue(transport.recorded.any { it.method == "POST_GRAPHQL" && operationOf(it.body!!) == "addQuestionToFavorite" })
    }

    @Test
    fun `toggle remove mirrors add`() {
        val (status, body) = post("/api/favorite/toggle", """{"platformId":"leetcode","questionId":"42","add":false}""")

        assertEquals(200, status)
        assertEquals("true", body["ok"]!!.jsonPrimitive.content)
        assertTrue(transport.recorded.any { it.method == "POST_GRAPHQL" && operationOf(it.body!!) == "removeQuestionFromFavorite" })
    }

    @Test
    fun `toggle unauthenticated maps to 401 auth-expired`() {
        // 匿名/未登录形态:favoritesLists 返回空数组(2026-10-03 双站匿名实测)→ AuthExpired
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL" && operationOf(request.body!!) == "favoritesLists") {
                """{"data":{"favoritesLists":{"allFavorites":[]}}}"""
            } else {
                "{}"
            }
        }

        val (status, body) = post("/api/favorite/toggle", """{"platformId":"leetcodecn","questionId":"1","add":true}""")

        assertEquals(401, status)
        assertEquals("auth-expired", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toggle site rejection maps to 502`() {
        // 站方拒绝形态:mutation ok=false(前端乐观切换回滚的 502 分支)
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                when (operationOf(request.body!!)) {
                    "favoritesLists" -> FAVORITES_SAMPLE
                    "addQuestionToFavorite" -> """{"data":{"addQuestionToFavorite":{"ok":false,"error":"收藏失败"}}}"""
                    else -> "{}"
                }
            } else {
                "{}"
            }
        }

        val (status, body) = post("/api/favorite/toggle", """{"platformId":"leetcodecn","questionId":"1","add":true}""")

        assertEquals(502, status)
        assertEquals("oj-error", body["error"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.contains("收藏失败"))
    }

    @Test
    fun `toggle requires capable platform and valid body`() {
        assertEquals(404, post("/api/favorite/toggle", """{"platformId":"codeforces","questionId":"1","add":true}""").first)
        assertEquals(404, post("/api/favorite/toggle", """{"platformId":"nope","questionId":"1","add":true}""").first)
        assertEquals(400, post("/api/favorite/toggle", """{"platformId":"","questionId":"1","add":true}""").first)
        assertEquals(400, post("/api/favorite/toggle", "not-json").first)
    }

    // ------------------------------------------------------------------
    // GET /api/problem/{remoteId}/hints
    // ------------------------------------------------------------------

    @Test
    fun `hints returns verbatim list for leetcode platform`() {
        val (status, body) = get("/api/problem/two-sum/hints?platform=leetcode")

        assertEquals(200, status)
        val hints = body["hints"]!!.jsonArray
        assertEquals(3, hints.size)
        // HTML 实体原样透出(前端按文本渲染)
        assertTrue(hints[1].jsonPrimitive.content.contains("<code>x</code>"))
    }

    @Test
    fun `hints degrades to empty list on site failure`() {
        transport.handler = { _ -> "{}" } // question/hints 缺失 → SPI 空表不抛

        val (status, body) = get("/api/problem/two-sum/hints?platform=leetcodecn")

        assertEquals(200, status)
        assertEquals(0, body["hints"]!!.jsonArray.size)
    }

    @Test
    fun `hints requires capable platform and platform param`() {
        assertEquals(404, get("/api/problem/1927A/hints?platform=codeforces").first)
        assertEquals(404, get("/api/problem/two-sum/hints?platform=nope").first)
        assertEquals(400, get("/api/problem/two-sum/hints").first)
    }

    // ------------------------------------------------------------------
    // 平台能力归属(LC 两变体 is 双能力,CF is not)
    // ------------------------------------------------------------------

    @Test
    fun `favorite write and hints capabilities belong to leetcode variants only`() {
        // 声明为 OJPlatform:能力探测与 REST 端点同法(运行时按实例类型判)
        val cn: OJPlatform = LeetCodeCnPlatform(transport, InMemoryCredentialStore())
        val com: OJPlatform = LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, InMemoryCredentialStore())
        val cf: OJPlatform = CodeForcesPlatform(transport, InMemoryCredentialStore())

        assertTrue(cn is FavoriteWriteCapable)
        assertTrue(cn is ProblemHintsCapable)
        assertTrue(com is FavoriteWriteCapable)
        assertTrue(com is ProblemHintsCapable)
        assertFalse(cf is FavoriteWriteCapable)
        assertFalse(cf is ProblemHintsCapable)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun operationOf(body: String): String =
        Json.parseToJsonElement(body).jsonObject["operationName"]!!.jsonPrimitive.content

    private fun get(path: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun post(
        path: String,
        body: String,
    ): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private companion object {
        /** favoritesLists idHash 变体样例(2026-10-03 双站匿名 schema 实测字段形态;数据为登录态虚构)。 */
        private const val FAVORITES_SAMPLE =
            """{"data":{"favoritesLists":{"allFavorites":[
               {"idHash":"hash-algo","name":"Algorithms"},
               {"idHash":"hash-default","name":"Favorite"}]}}}"""

        /** questionHints 样例(2026-10-03 two-sum 匿名实测形态,截取 3 条)。 */
        private const val HINTS_SAMPLE =
            """{"data":{"question":{"hints":[
               "A really brute force way would be to search for all possible pairs of numbers but that would be too slow.",
               "So, if we fix one of the numbers, say <code>x</code>, we have to scan the entire array.",
               "The second train of thought is, without changing the array, can we use additional space somehow?"]}}}"""
    }
}
