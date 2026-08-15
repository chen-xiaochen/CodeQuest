package com.codequest.platform.leetcode

import com.codequest.core.model.AuthExpiredException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP19:LeetCodeAuth(cookie 粘贴登录)——凭据恢复/校验/持久化/退出,
 * 全部经 FakeOJTransport,不上网络。
 */
class LeetCodeAuthTest {
    private fun transportWith(signedIn: Boolean, username: String? = "alice"): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                val operation = Json.parseToJsonElement(request.body!!).jsonObject["operationName"]!!.jsonPrimitive.content
                check(operation == "globalData") { "unexpected $operation" }
                """{"data":{"userStatus":{"isSignedIn":$signedIn,"username":${username?.let { "\"$it\"" } ?: "null"},"userSlug":"alice-s"}}}"""
            } else {
                "{}"
            }
        }
        return transport
    }

    private fun auth(
        transport: FakeOJTransport,
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        prompt: LeetCodeAuth.CookiePrompt = LeetCodeAuth.CookiePrompt { null },
    ) = LeetCodeAuth(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, transport), transport, store, prompt)

    @Test
    fun `saved cookies are restored injected and verified without prompting`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply {
                map[LeetCodeVariant.LEETCODE.id] = "LEETCODE_SESSION=s3cret; csrftoken=csrf-1"
            }
        val transport = transportWith(signedIn = true)
        val auth = auth(transport, store)

        val session = auth.ensureLoggedIn()

        assertEquals("leetcode", session.platformId.value)
        assertEquals("alice", session.userId)
        assertTrue(auth.isLoggedIn())
        // cookie 已注入该平台 Jar
        assertEquals("s3cret", transport.cookieJars[LeetCodeVariant.LEETCODE.id]!!["LEETCODE_SESSION"])
        assertEquals("csrf-1", transport.cookieJars[LeetCodeVariant.LEETCODE.id]!!["csrftoken"])
        // 未走交互登录(默认 prompt 返回 null,若被调用会抛异常)
    }

    @Test
    fun `prompted cookies are verified then persisted raw`() = runBlocking {
        val store = InMemoryCredentialStore()
        val transport = transportWith(signedIn = true)
        val auth = auth(transport, store, prompt = LeetCodeAuth.CookiePrompt { "LEETCODE_SESSION=fresh" })

        val session = auth.ensureLoggedIn()

        assertEquals("alice", session.userId)
        assertEquals("LEETCODE_SESSION=fresh", store.map[LeetCodeVariant.LEETCODE.id])
    }

    @Test
    fun `cancelled prompt throws AuthExpired`() {
        val auth = auth(transportWith(signedIn = true))
        assertFailsWith<AuthExpiredException> {
            runBlocking { auth.ensureLoggedIn() }
        }
    }

    @Test
    fun `invalid cookie with signedIn false throws AuthExpired and is not persisted`() {
        val store = InMemoryCredentialStore()
        val auth = auth(transportWith(signedIn = false), store, prompt = LeetCodeAuth.CookiePrompt { "LEETCODE_SESSION=bad" })
        assertFailsWith<AuthExpiredException> {
            runBlocking { auth.ensureLoggedIn() }
        }
        assertNull(store.map[LeetCodeVariant.LEETCODE.id])
    }

    @Test
    fun `logout removes secret clears jar and resets snapshot`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply { map[LeetCodeVariant.LEETCODE.id] = "LEETCODE_SESSION=s" }
        val transport = transportWith(signedIn = true)
        val auth = auth(transport, store)
        auth.ensureLoggedIn()

        auth.logout()

        assertFalse(auth.isLoggedIn())
        assertTrue(store.map.isEmpty())
        assertTrue(LeetCodeVariant.LEETCODE.id in transport.clearedPlatforms)
    }

    @Test
    fun `cookie header parsing tolerates base64 padding and whitespace`() {
        val parsed =
            LeetCodeAuth.parseCookieHeader(
                "LEETCODE_SESSION=abc==;  csrftoken=tok; invalid-line; =novalue",
            )
        assertEquals(mapOf("LEETCODE_SESSION" to "abc==", "csrftoken" to "tok"), parsed)
    }
}
