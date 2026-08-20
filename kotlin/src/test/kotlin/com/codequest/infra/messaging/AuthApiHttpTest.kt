package com.codequest.infra.messaging

import com.codequest.core.model.PlatformId
import com.codequest.core.workspace.TemplateStyle
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
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
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP38:账号状态端点——起真实 [ResourceHttpServer] + [AuthApi]
 * (假平台快照注入),覆盖全平台列表回显 / loggedIn 快照 / userId 契约 / 未装配 503。
 */
class AuthApiHttpTest {
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** 可控登录快照的假平台(loggedIn 即 AuthCapable.isLoggedIn 返回值)。 */
    private class FakeAuthPlatform(
        private val platformId: PlatformId,
        private val loggedIn: Boolean,
    ) : OJPlatform,
        AuthCapable {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info = PlatformInfo(displayName = platformId.value, templateStyle = TemplateStyle.ACM)

        override suspend fun ensureLoggedIn(): LoginSession = LoginSession(platformId, "user")

        override fun isLoggedIn(): Boolean = loggedIn

        override suspend fun logout() = Unit
    }

    /** 无登录能力的平台(loggedIn 如实回 false,不排除出列表)。 */
    private class BarePlatform(platformId: PlatformId) : OJPlatform {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info = PlatformInfo(displayName = platformId.value, templateStyle = TemplateStyle.ACM)
    }

    @BeforeTest
    fun setUp() {
        server =
            ResourceHttpServer(
                authApi =
                    AuthApi(
                        platforms = {
                            listOf(
                                FakeAuthPlatform(PlatformId("leetcode"), loggedIn = true),
                                FakeAuthPlatform(PlatformId("codeforces"), loggedIn = false),
                                BarePlatform(PlatformId("atcoder")),
                            )
                        },
                    ),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `auth status lists all platforms with login snapshots`() {
        val body = getJson("/api/auth/status")

        val items = body.getAsJsonArray("items")
        assertEquals(3, items.size)
        val byPlatform: Map<String, kotlinx.serialization.json.JsonObject> =
            items.map { it.jsonObject }.associateBy { it["platform"]!!.jsonPrimitive.content }
        assertEquals(true, byPlatform.getValue("leetcode")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, byPlatform.getValue("codeforces")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        // 非 AuthCapable 平台不排除,快照如实为未登录
        assertEquals(false, byPlatform.getValue("atcoder")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `auth status reports userId null by contract`() {
        val items = getJson("/api/auth/status").getAsJsonArray("items")

        items.forEach { item ->
            // SPI 无「只读当前 session」契约,端点不触发登录,userId 恒 null(契约保留字段)
            assertEquals("null", item.jsonObject["userId"].toString())
        }
    }

    @Test
    fun `auth status without configured api returns 503`() {
        server.stop()
        server = ResourceHttpServer()
        server.start()

        val (status, body) = get("/api/auth/status")
        assertEquals(503, status)
        assertEquals("api-not-configured", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auth status rejects non GET with 404`() {
        val response = client.send(
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}/api/auth/status"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, response.statusCode())
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

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
}

private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
    get() = this as kotlinx.serialization.json.JsonArray
