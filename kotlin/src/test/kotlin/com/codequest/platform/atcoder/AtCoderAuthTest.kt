package com.codequest.platform.atcoder

import com.codequest.core.model.AuthExpiredException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.spi.OJAuthPrompt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP25:AtCoderAuth(cookie 粘贴登录)——凭据恢复/登录态校验
 * (settings 页 user-nav-tabs)/持久化/退出,经 FakeOJTransport,不上网络。
 * 登录选型:提交链路有 Turnstile 保护,程序化账密登录不可行(旧实现同样
 * 只依赖浏览器 cookie),故选 cookie 粘贴(与 LeetCode/CodeForces 同形态)。
 */
class AtCoderAuthTest {
    /** settings 页响应:loggedIn 时含 user-nav-tabs + 用户名表单,未登录时只有提示。 */
    private fun transportWith(loggedIn: Boolean): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            check(request.method == "GET" && request.url.endsWith("/settings")) {
                "unexpected ${request.method} ${request.url}"
            }
            if (loggedIn) {
                """
                <div id="main-container">
                  <ul id="user-nav-tabs"><li>Account</li></ul>
                  <form><input id="ui.UserName" type="text" value="tourist"/></form>
                </div>
                """.trimIndent()
            } else {
                """<div id="main-container"><div class="alert">Please log in</div></div>"""
            }
        }
        return transport
    }

    private fun auth(
        transport: FakeOJTransport,
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        prompt: OJAuthPrompt = OJAuthPrompt { null },
    ) = AtCoderAuth(AtCoderApi(transport), transport, store, prompt)

    @Test
    fun `saved cookies are restored injected and verified without prompting`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply {
                map[ATCODER_PLATFORM_ID] = "REVEL_SESSION=atc-session; __cf_bm=cf-value"
            }
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store).ensureLoggedIn()

        assertEquals("atcoder", session.platformId.value)
        assertEquals("tourist", session.userId)
        // cookie 已注入该平台 Jar
        assertEquals("atc-session", transport.cookieJars[ATCODER_PLATFORM_ID]!!["REVEL_SESSION"])
        assertEquals("cf-value", transport.cookieJars[ATCODER_PLATFORM_ID]!!["__cf_bm"])
    }

    @Test
    fun `prompted cookies are verified then persisted raw`() = runBlocking {
        val store = InMemoryCredentialStore()
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store, prompt = OJAuthPrompt { "REVEL_SESSION=fresh" }).ensureLoggedIn()

        assertEquals("tourist", session.userId)
        assertEquals("REVEL_SESSION=fresh", store.map[ATCODER_PLATFORM_ID])
    }

    @Test
    fun `cancelled prompt throws AuthExpired`() {
        assertFailsWith<AuthExpiredException> {
            runBlocking { auth(transportWith(loggedIn = true)).ensureLoggedIn() }
        }
    }

    @Test
    fun `invalid cookie without user nav tabs throws AuthExpired and is not persisted`() {
        val store = InMemoryCredentialStore()
        val atCoderAuth = auth(transportWith(loggedIn = false), store, prompt = OJAuthPrompt { "REVEL_SESSION=bad" })
        assertFailsWith<AuthExpiredException> {
            runBlocking { atCoderAuth.ensureLoggedIn() }
        }
        assertNull(store.map[ATCODER_PLATFORM_ID])
    }

    @Test
    fun `username missing falls back to unknown`() = runBlocking {
        val transport = transportWith(loggedIn = true)
        // 登录标记在但表单输入框缺失(结构变化)→ userId 兜底 unknown,登录仍成立
        transport.handler = { _ -> """<div id="main-container"><ul id="user-nav-tabs"></ul></div>""" }

        val session = auth(transport, prompt = OJAuthPrompt { "REVEL_SESSION=x" }).ensureLoggedIn()

        assertEquals("unknown", session.userId)
    }

    @Test
    fun `logout removes secret clears jar and resets snapshot`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply { map[ATCODER_PLATFORM_ID] = "REVEL_SESSION=s" }
        val transport = transportWith(loggedIn = true)
        val atCoderAuth = auth(transport, store)
        atCoderAuth.ensureLoggedIn()

        atCoderAuth.logout()

        assertFalse(atCoderAuth.isLoggedIn())
        assertTrue(store.map.isEmpty())
        assertTrue(ATCODER_PLATFORM_ID in transport.clearedPlatforms)
    }

    @Test
    fun `cookie header parsing tolerates values with equals and junk lines`() {
        val parsed =
            AtCoderAuth.parseCookieHeader(
                "REVEL_SESSION=abc==;  __cf_bm=tok; invalid-line; =novalue",
            )
        assertEquals(mapOf("REVEL_SESSION" to "abc==", "__cf_bm" to "tok"), parsed)
    }
}
