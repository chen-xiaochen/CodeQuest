package com.codequest.infra.messaging

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.ContentFormat
import com.codequest.core.model.PlatformId
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import com.codequest.spi.SolutionArticleContent
import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP26 列表 / WP31 正文:题解端点——起真实
 * [ResourceHttpServer] + 假 SolutionViewCapable 平台(lambda 注入,不上网络),
 * 覆盖 `/api/problem/{id}/solutions` 与 `/api/problem/{id}/solutions/{sid}`
 * 路由、三路由共存、错误映射(401/未知平台/缺参数/SPI 默认未实现)与
 * api 未配置时的 503。
 */
class SolutionsApiTest {
    private var failure: suspend () -> Unit = {}

    private inner class FakeLuoguPlatform : OJPlatform, SolutionViewCapable {
        override val id = PlatformId("luogu")
        override val domain = "www.luogu.com.cn"
        override val info = PlatformInfo(displayName = "洛谷 Luogu")

        override suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef> {
            failure()
            return listOf(
                SolutionArticleRef(id = "77", title = "一份题解", url = "https://www.luogu.com.cn/problem/solution/$remoteId#solution-77"),
            )
        }

        override suspend fun fetchSolutionContent(
            remoteId: String,
            solutionId: String,
        ): SolutionArticleContent {
            failure()
            return SolutionArticleContent(
                id = solutionId,
                title = "一份题解",
                content = "## 思路\n\n$remoteId 动态规划",
                contentFormat = ContentFormat.MARKDOWN,
            )
        }
    }

    /** 只实现列表(吃 SPI 默认正文实现):默认抛 OJException → 端点 502。 */
    private class ListOnlyPlatform : OJPlatform, SolutionViewCapable {
        override val id = PlatformId("listonly")
        override val domain = "listonly.example.com"
        override val info = PlatformInfo(displayName = "List Only")

        override suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef> = emptyList()
    }

    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        server = ResourceHttpServer(solutionsApi = SolutionsApi(platforms = { listOf(FakeLuoguPlatform()) }))
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `solutions route returns refs for solution capable platform`() {
        val response = get("/api/problem/P1001/solutions?platform=luogu")

        assertEquals(200, response.statusCode())
        assertEquals(
            """{"platform":"luogu","remoteId":"P1001","items":[""" +
                """{"id":"77","title":"一份题解","url":"https://www.luogu.com.cn/problem/solution/P1001#solution-77"}]}""",
            response.body(),
        )
    }

    @Test
    fun `solutions route coexists with problem detail route`() {
        // problemApi 未配置 → 详情路由 503,但不影响题解路由(路由分流正确)
        assertEquals(503, get("/api/problem/P1001").statusCode())
        assertEquals(200, get("/api/problem/P1001/solutions?platform=luogu").statusCode())
        assertEquals(200, get("/api/problem/P1002/solutions?platform=luogu").statusCode())
    }

    // -------------------------------------------------------------------
    // WP31:题解正文端点
    // -------------------------------------------------------------------

    @Test
    fun `solution content route returns article json`() {
        val response = get("/api/problem/P1001/solutions/77?platform=luogu")

        assertEquals(200, response.statusCode())
        assertEquals(
            """{"platform":"luogu","remoteId":"P1001","id":"77","title":"一份题解",""" +
                """"content":"## 思路\n\nP1001 动态规划","contentFormat":"MARKDOWN"}""",
            response.body(),
        )
    }

    @Test
    fun `solution content route coexists with list and detail routes`() {
        assertEquals(200, get("/api/problem/P1001/solutions/77?platform=luogu").statusCode())
        assertEquals(200, get("/api/problem/P1001/solutions?platform=luogu").statusCode())
        // 编码后的 remoteId(LC slug 形态)同样解析
        assertEquals(200, get("/api/problem/two-sum/solutions/wu-1?platform=luogu").statusCode())
        assertEquals(503, get("/api/problem/P1001").statusCode())
    }

    @Test
    fun `solution content auth expired maps to 401`() {
        failure = { throw AuthExpiredException("请先登录") }

        val response = get("/api/problem/P1001/solutions/77?platform=luogu")

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("auth-expired"), response.body())
    }

    @Test
    fun `solution content unknown platform 404 missing params 400 and default impl 502`() {
        assertEquals(404, get("/api/problem/P1001/solutions/77?platform=atcoder").statusCode())
        assertEquals(400, get("/api/problem/P1001/solutions/77").statusCode())
        // sid 缺段(路由标记存在但为空)→ 400 而非误入详情路由
        assertEquals(400, get("/api/problem/P1001/solutions/?platform=luogu").statusCode())
    }

    @Test
    fun `spi default fetchSolutionContent maps to 502 oj-error`() {
        server.stop()
        server = ResourceHttpServer(solutionsApi = SolutionsApi(platforms = { listOf(ListOnlyPlatform()) }))
        server.start()

        val response = get("/api/problem/P1/solutions/9?platform=listonly")

        assertEquals(502, response.statusCode())
        assertTrue(response.body().contains("oj-error"), response.body())
        assertTrue(response.body().contains("未实现题解正文拉取"), response.body())
    }

    @Test
    fun `solution content api not configured maps to 503`() {
        server.stop()
        server = ResourceHttpServer()
        server.start()

        assertEquals(503, get("/api/problem/P1001/solutions/77?platform=luogu").statusCode())
    }

    @Test
    fun `auth expired maps to 401 with error code`() {
        failure = { throw AuthExpiredException("请先登录") }

        val response = get("/api/problem/P1001/solutions?platform=luogu")

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("auth-expired"), response.body())
    }

    @Test
    fun `unknown platform maps to 404 and missing platform param to 400`() {
        assertEquals(404, get("/api/problem/P1001/solutions?platform=atcoder").statusCode())
        assertEquals(400, get("/api/problem/P1001/solutions").statusCode())
    }

    @Test
    fun `missing remoteId segment maps to 400`() {
        assertEquals(400, get("/api/problem//solutions?platform=luogu").statusCode())
    }

    @Test
    fun `solutions api not configured maps to 503`() {
        server.stop()
        server = ResourceHttpServer()
        server.start()

        assertEquals(503, get("/api/problem/P1001/solutions?platform=luogu").statusCode())
    }

    @Test
    fun `listSolutions logic is pure kotlin testable without http`() = runBlocking {
        val api = SolutionsApi(platforms = { listOf(FakeLuoguPlatform()) })

        val response = api.listSolutions("P1001", mapOf("platform" to "luogu"))

        assertEquals(200, response.status)
        assertTrue(response.json.contains("\"remoteId\":\"P1001\""))
    }

    @Test
    fun `solutionContent logic is pure kotlin testable without http`() = runBlocking {
        val api = SolutionsApi(platforms = { listOf(FakeLuoguPlatform()) })

        val response = api.solutionContent("P1001", "77", mapOf("platform" to "luogu"))

        assertEquals(200, response.status)
        assertTrue(response.json.contains("\"contentFormat\":\"MARKDOWN\""), response.json)
        assertTrue(response.json.contains("\"id\":\"77\""), response.json)
    }
}
