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
 * (假平台快照注入),覆盖全平台列表回显 / loggedIn 快照 / 未装配 503;
 * WP48:userId 只读快照透传 + `POST /api/auth/refresh` 静默恢复(restoreSession
 * 成功/返回 null/抛异常三态)与响应同构。
 */
class AuthApiHttpTest {
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /**
     * 可控登录快照与静默恢复的假平台(WP48):[loggedIn] 即 isLoggedIn 快照、
     * [session] 即 currentSession 只读快照、[restore]/[restoreError] 驱动
     * restoreSession 三态(成功返回会话 / 返回 null / 抛异常)。
     */
    private class FakeAuthPlatform(
        private val platformId: PlatformId,
        private val loggedIn: Boolean,
        private val session: LoginSession? = null,
        private val restore: LoginSession? = null,
        private val restoreError: Exception? = null,
    ) : OJPlatform,
        AuthCapable {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info = PlatformInfo(displayName = platformId.value, templateStyle = TemplateStyle.ACM)

        override suspend fun ensureLoggedIn(): LoginSession = LoginSession(platformId, "user")

        override fun isLoggedIn(): Boolean = loggedIn

        override fun currentSession(): LoginSession? = session

        override suspend fun restoreSession(): LoginSession? {
            restoreError?.let { throw it }
            return restore
        }

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
                                // WP48 三态:恢复成功(会话就位)/ 恢复返回 null / 恢复抛异常
                                FakeAuthPlatform(
                                    PlatformId("leetcode"),
                                    loggedIn = true,
                                    session = LoginSession(PlatformId("leetcode"), "lc-user"),
                                    restore = LoginSession(PlatformId("leetcode"), "lc-user"),
                                ),
                                FakeAuthPlatform(PlatformId("codeforces"), loggedIn = false),
                                FakeAuthPlatform(PlatformId("hackerrank"), loggedIn = false, restoreError = RuntimeException("network down")),
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
        assertEquals(4, items.size)
        val byPlatform: Map<String, kotlinx.serialization.json.JsonObject> =
            items.map { it.jsonObject }.associateBy { it["platform"]!!.jsonPrimitive.content }
        assertEquals(true, byPlatform.getValue("leetcode")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, byPlatform.getValue("codeforces")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        // 非 AuthCapable 平台不排除,快照如实为未登录
        assertEquals(false, byPlatform.getValue("atcoder")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
    }

    /**
     * WP48:userId 透传——status 不触发登录,只透传 [AuthCapable.currentSession]
     * 只读快照;已登录平台如实带回 userId,未登录/非 AuthCapable 为 null。
     */
    @Test
    fun `auth status passes through current session userId`() {
        val items = getJson("/api/auth/status").getAsJsonArray("items")

        val byPlatform =
            items.map { it.jsonObject }.associateBy { it["platform"]!!.jsonPrimitive.content }
        // 已登录 + currentSession 有会话 → userId 如实透传
        assertEquals("lc-user", byPlatform.getValue("leetcode")["userId"]!!.jsonPrimitive.content)
        // 未登录(currentSession 为 null)→ userId 如实 null
        assertEquals("null", byPlatform.getValue("codeforces")["userId"].toString())
        // 非 AuthCapable → loggedIn false + userId null
        assertEquals(false, byPlatform.getValue("atcoder")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("null", byPlatform.getValue("atcoder")["userId"].toString())
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
    // WP48:POST /api/auth/refresh(静默恢复)
    // ------------------------------------------------------------------

    /** 三态恢复(成功/返回 null/抛异常)+ 非 AuthCapable → items 如实反映。 */
    @Test
    fun `auth refresh reflects restored sessions across three outcomes`() {
        val items = postJson("/api/auth/refresh").getAsJsonArray("items")

        assertEquals(4, items.size)
        val byPlatform =
            items.map { it.jsonObject }.associateBy { it["platform"]!!.jsonPrimitive.content }
        // 恢复成功 → loggedIn true + userId 取恢复出的会话
        assertEquals(true, byPlatform.getValue("leetcode")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("lc-user", byPlatform.getValue("leetcode")["userId"]!!.jsonPrimitive.content)
        // 恢复返回 null(无已存凭据)→ 如实未登录
        assertEquals(false, byPlatform.getValue("codeforces")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("null", byPlatform.getValue("codeforces")["userId"].toString())
        // 恢复抛异常 → 吞掉按未登录处理,不拖垮整批
        assertEquals(false, byPlatform.getValue("hackerrank")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("null", byPlatform.getValue("hackerrank")["userId"].toString())
        // 非 AuthCapable 平台不排除
        assertEquals(false, byPlatform.getValue("atcoder")["loggedIn"]!!.jsonPrimitive.content.toBoolean())
    }

    /** refresh 响应与 status 同构:每项字段名/顺序一致(platform/loggedIn/userId)。 */
    @Test
    fun `auth refresh response shape matches status`() {
        val statusKeys = getJson("/api/auth/status").getAsJsonArray("items").first().jsonObject.keys
        val refreshKeys = postJson("/api/auth/refresh").getAsJsonArray("items").first().jsonObject.keys

        assertEquals(statusKeys.toList(), refreshKeys.toList())
    }

    @Test
    fun `auth refresh without configured api returns 503`() {
        server.stop()
        server = ResourceHttpServer()
        server.start()

        val (status, body) = post("/api/auth/refresh")
        assertEquals(503, status)
        assertEquals("api-not-configured", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auth refresh rejects non POST with 404`() {
        val response = getRaw("/api/auth/refresh")
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

    /** WP48:POST 空体请求(refresh 无参端点);返回 (状态码, 响应 JSON)。 */
    private fun post(path: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun getJson(path: String): kotlinx.serialization.json.JsonObject = get(path).second

    private fun postJson(path: String): kotlinx.serialization.json.JsonObject = post(path).second

    private fun kotlinx.serialization.json.JsonObject.getAsJsonArray(name: String): List<kotlinx.serialization.json.JsonElement> =
        this[name]!!.jsonArray.toList()
}

private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
    get() = this as kotlinx.serialization.json.JsonArray
