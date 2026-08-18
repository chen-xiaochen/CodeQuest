package com.codequest.infra.messaging

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.PlatformId
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
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
 * [integration-ish unit] WP26:题解引用端点——起真实 [ResourceHttpServer] + 假
 * SolutionViewCapable 平台(lambda 注入,不上网络),覆盖 `/api/problem/{id}/solutions`
 * 路由、与 `/api/problem/{id}` 详情路由的共存、错误映射(401/未知平台/缺参数)
 * 与 api 未配置时的 503。
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
}
