package com.codequest.infra.http

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.core.model.RateLimitedException
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [unit] WP18:OJHttpClient MockEngine 单测(cookie 隔离/预置/异常映射/请求头/JSON 往返)。 */
class OJHttpClientTest {

    /** MockEngine 捕获到的请求(按到达顺序)。 */
    private data class Recorded(
        val url: String,
        val headers: Headers,
        val body: String,
        val bodyContentType: String?,
    )

    private val recorded = mutableListOf<Recorded>()
    private val engine = MockEngine { request ->
        // OutgoingContent → ByteArray(GET 为空 body,读取失败按空串兜底)
        val body = runCatching { request.body.toByteArray().decodeToString() }.getOrDefault("")
        recorded += Recorded(
            request.url.toString(),
            request.headers,
            body,
            request.body.contentType?.toString(),
        )
        handler(request.url.encodedPath, request.headers)
    }

    /** 每个测试可覆盖的响应逻辑,默认 200 JSON。 */
    private var handler: suspend MockRequestHandleScope.(path: String, headers: Headers) -> HttpResponseData =
        { _, _ -> respond("""{"ok":true}""") }

    private val client = OJHttpClient(engine)

    private val leetcode = PlatformId("leetcode")
    private val codeforces = PlatformId("codeforces")
    private val leetcodeUrl = "https://leetcode.com/graphql"

    @AfterTest
    fun tearDown() {
        client.close()
    }

    // -------------------------------------------------------------------
    // cookie 隔离与预置
    // -------------------------------------------------------------------

    @Test
    fun `cookies set via response are isolated per platform`() = runBlocking {
        // leetcode 平台请求后,服务器种下 session cookie
        handler = { path, _ ->
            if (path == "/login") {
                respond(
                    content = "logged in",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Set-Cookie", "LEETCODE_SESSION=lc-secret; Path=/"),
                )
            } else {
                respond("""{"ok":true}""")
            }
        }
        client.get(leetcode, "https://leetcode.com/login")
        client.get(leetcode, "https://leetcode.com/data")

        // codeforces 平台访问**同一 host**:必须看不到 leetcode 的 cookie(独立 CookieJar)
        client.get(codeforces, "https://leetcode.com/data")

        val cfCookie = recorded.last().headers["Cookie"]
        assertTrue(cfCookie == null || "LEETCODE_SESSION" !in cfCookie!!, "跨平台 cookie 泄漏: $cfCookie")
        // 而 leetcode 自己的第二次请求带上了 cookie
        assertTrue("LEETCODE_SESSION=lc-secret" in recorded[1].headers["Cookie"]!!)
    }

    @Test
    fun `preset cookies are sent on subsequent requests`() = runBlocking {
        client.updateCookies(
            leetcode,
            leetcodeUrl,
            mapOf("LEETCODE_SESSION" to "preset-session", "csrftoken" to "preset-csrf"),
        )
        val body = client.get(leetcode, leetcodeUrl)

        assertEquals("""{"ok":true}""", body)
        val cookie = recorded.single().headers["Cookie"]!!
        assertTrue("LEETCODE_SESSION=preset-session" in cookie, "缺少预置 cookie: $cookie")
        assertTrue("csrftoken=preset-csrf" in cookie, "缺少预置 cookie: $cookie")
    }

    @Test
    fun `preset cookies do not leak into other platform jar`() = runBlocking {
        client.updateCookies(leetcode, leetcodeUrl, mapOf("LEETCODE_SESSION" to "lc"))
        client.get(codeforces, leetcodeUrl)
        assertNull(recorded.single().headers["Cookie"])
    }

    @Test
    fun `clearCookies empties only the target platform jar`() = runBlocking {
        client.updateCookies(leetcode, leetcodeUrl, mapOf("LEETCODE_SESSION" to "lc"))
        client.updateCookies(codeforces, leetcodeUrl, mapOf("CFID" to "cf"))
        client.clearCookies(leetcode)

        client.get(leetcode, leetcodeUrl)
        client.get(codeforces, leetcodeUrl)

        assertNull(recorded[0].headers["Cookie"], "clearCookies 未生效")
        assertTrue("CFID=cf" in recorded[1].headers["Cookie"]!!, "clearCookies 误伤其他平台")
    }

    @Test
    fun `exportCookieHeader formats stored cookies`() = runBlocking {
        client.updateCookies(leetcode, leetcodeUrl, mapOf("a" to "1", "b" to "2"))
        val header = client.exportCookieHeader(leetcode, leetcodeUrl)
        assertEquals("a=1; b=2", header)
    }

    // -------------------------------------------------------------------
    // 错误 → 领域异常映射
    // -------------------------------------------------------------------

    @Test
    fun `401 maps to AuthExpiredException`() {
        handler = { _, _ -> respond("unauthorized", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<AuthExpiredException> {
            runBlocking { client.get(leetcode, leetcodeUrl) }
        }
        assertTrue("401" in e.message!!, e.message)
    }

    @Test
    fun `403 without challenge markers maps to AuthExpiredException`() {
        handler = { _, _ -> respond("forbidden", HttpStatusCode.Forbidden) }
        assertFailsWith<AuthExpiredException> {
            runBlocking { client.postJson(leetcode, leetcodeUrl, """{"q":"x"}""") }
        }
    }

    @Test
    fun `429 maps to RateLimitedException with retryAfterMs from Retry-After header`() {
        handler = { _, _ ->
            respond(
                content = "too many requests",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Retry-After", "30"),
            )
        }
        val e = assertFailsWith<RateLimitedException> {
            runBlocking { client.get(leetcode, leetcodeUrl) }
        }
        assertEquals(30_000L, e.retryAfterMs)
    }

    @Test
    fun `429 without Retry-After header keeps retryAfterMs null`() {
        handler = { _, _ -> respond("slow down", HttpStatusCode.TooManyRequests) }
        val e = assertFailsWith<RateLimitedException> {
            runBlocking { client.get(leetcode, leetcodeUrl) }
        }
        assertNull(e.retryAfterMs)
    }

    @Test
    fun `cloudflare challenge page maps to AntiBotChallengeException`() {
        handler = { _, _ ->
            respond(
                content =
                    "<html><title>Just a moment...</title>" +
                        "<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script></html>",
                status = HttpStatusCode.Forbidden,
            )
        }
        val e = assertFailsWith<AntiBotChallengeException> {
            runBlocking { client.get(leetcode, leetcodeUrl) }
        }
        assertEquals("cloudflare", e.challengeType)
    }

    @Test
    fun `cf-mitigated header maps to AntiBotChallengeException even with 503`() {
        handler = { _, _ ->
            respond(
                content = "service unavailable",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf("cf-mitigated", "challenge"),
            )
        }
        assertFailsWith<AntiBotChallengeException> {
            runBlocking { client.get(leetcode, leetcodeUrl) }
        }
    }

    @Test
    fun `other non-2xx maps to generic OJException with status and body snippet`() {
        handler = { _, _ -> respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }
        val e = assertFailsWith<OJException> {
            runBlocking { client.get(leetcode, leetcodeUrl) }
        }
        // 必须是基类而非某个领域子类
        assertEquals(OJException::class, e::class)
        assertTrue("500" in e.message!!, e.message)
        assertTrue("boom" in e.message!!, e.message)
    }

    // -------------------------------------------------------------------
    // 请求构造与 JSON 往返
    // -------------------------------------------------------------------

    @Test
    fun `get sends platform user agent and base headers`() = runBlocking {
        client.get(leetcode, leetcodeUrl)
        val headers = recorded.single().headers
        assertEquals(OJHttpClient.DEFAULT_USER_AGENT, headers["User-Agent"])
        assertTrue(headers["Accept"]!!.isNotBlank())
        assertTrue(headers["Accept-Language"]!!.isNotBlank())
    }

    @Test
    fun `postJson sends json content type and echoes body roundtrip`() = runBlocking {
        val payload = """{"operationName":"x","query":"query { user }"}"""
        val body = client.postJson(leetcode, leetcodeUrl, payload)

        assertEquals("""{"ok":true}""", body)
        val request = recorded.single()
        // ktor 3 的 Content-Type 挂在 body content 上而非 headers map
        assertTrue(
            request.bodyContentType?.startsWith("application/json") == true,
            "Content-Type 应为 application/json,实际 ${request.bodyContentType}",
        )
        assertEquals(payload, request.body)
    }

    @Test
    fun `postGraphQL wraps query into envelope and returns response body`() = runBlocking {
        handler = { _, _ -> respond("""{"data":{"currentUser":{"username":"alice"}}}""") }
        val raw = client.postGraphQL(leetcode, leetcodeUrl, query = "query { currentUser { username } }")

        val parsed = Json.parseToJsonElement(raw).jsonObject
        assertEquals(
            "alice",
            parsed["data"]!!.jsonObject["currentUser"]!!.jsonObject["username"]!!.jsonPrimitive.content,
        )
        // 请求体是合法 JSON 信封,包含 query 字段
        val sentEnvelope = Json.parseToJsonElement(recorded.single().body).jsonObject
        assertEquals("query { currentUser { username } }", sentEnvelope["query"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------
    // postForm(WP25 提升:CF / AtCoder 提交端点共用)
    // -------------------------------------------------------------------

    @Test
    fun `postForm sends urlencoded content type with encoded body`() = runBlocking {
        val body =
            client.postForm(
                leetcode,
                leetcodeUrl,
                form =
                    linkedMapOf(
                        "csrf_token" to "a=b&c",
                        "sourceCode" to "fun main() {}",
                    ),
            )

        assertEquals("""{"ok":true}""", body)
        val request = recorded.single()
        assertTrue(
            request.bodyContentType?.startsWith("application/x-www-form-urlencoded") == true,
            "Content-Type 应为 application/x-www-form-urlencoded,实际 ${request.bodyContentType}",
        )
        // 值中的保留字符被转义(& = 空格),空格走表单编码的 +
        assertEquals("csrf_token=a%3Db%26c&sourceCode=fun+main%28%29+%7B%7D", request.body)
    }

    @Test
    fun `postForm applies extra headers and maps non-2xx to domain exception`() = runBlocking {
        client.postForm(
            leetcode,
            leetcodeUrl,
            form = mapOf("k" to "v"),
            headers = mapOf("x-csrf-token" to "tok", "Referer" to "https://leetcode.com/my"),
        )

        val headers = recorded.single().headers
        assertEquals("tok", headers["x-csrf-token"])
        assertEquals("https://leetcode.com/my", headers["Referer"])
        assertEquals(OJHttpClient.DEFAULT_USER_AGENT, headers["User-Agent"])

        handler = { _, _ -> respond("unauthorized", HttpStatusCode.Unauthorized) }
        assertFailsWith<AuthExpiredException> {
            runBlocking { client.postForm(leetcode, leetcodeUrl, form = mapOf("k" to "v")) }
        }
    }
}
