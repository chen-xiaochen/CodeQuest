package com.codequest.platform.hackerrank

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.OJException
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
 * [unit] WP27:HackerRankAuth(cookie 粘贴登录)——凭据恢复/登录态校验
 * (banner-dashboard 2xx 判定 + dashboard profile.username)/持久化/退出,
 * 经 FakeOJTransport,不上网络。
 */
class HackerRankAuthTest {
    private val dashboardHtml =
        """<html><body><script id="__NEXT_DATA__" type="application/json">
           {"props":{"pageProps":{"prefetchedData":{"profile":{"username":"kaguya"}}}}}
           </script></body></html>""".trimIndent().replace("\n", "")

    private fun transportWith(loggedIn: Boolean): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                HACKERRANK_LOGIN_CHECK_URL ->
                    if (loggedIn) {
                        """{"slots":[]}"""
                    } else {
                        // 未登录时该端点返回 401/403,transport 已翻译为领域异常
                        throw AuthExpiredException("auth expired or missing at HTTP 401")
                    }
                HACKERRANK_DASHBOARD_URL -> dashboardHtml
                else -> throw OJException("unexpected ${request.method} ${request.url}")
            }
        }
        return transport
    }

    private fun auth(
        transport: FakeOJTransport,
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        prompt: OJAuthPrompt = OJAuthPrompt { null },
    ) = HackerRankAuth(HackerRankApi(transport), transport, store, prompt)

    @Test
    fun `saved cookies are restored injected and verified without prompting`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply {
                map[HACKERRANK_PLATFORM_ID] = "_hr=1; remember_me=token123"
            }
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store).ensureLoggedIn()

        assertEquals("hackerrank", session.platformId.value)
        assertEquals("kaguya", session.userId)
        assertEquals("1", transport.cookieJars[HACKERRANK_PLATFORM_ID]?.get("_hr"))
        assertEquals("token123", transport.cookieJars[HACKERRANK_PLATFORM_ID]?.get("remember_me"))
    }

    @Test
    fun `prompted cookies are verified then persisted raw`() = runBlocking {
        val store = InMemoryCredentialStore()
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store, prompt = OJAuthPrompt { "_hr=1" }).ensureLoggedIn()

        assertEquals("kaguya", session.userId)
        assertEquals("_hr=1", store.map[HACKERRANK_PLATFORM_ID])
    }

    @Test
    fun `cancelled prompt throws AuthExpired`() {
        assertFailsWith<AuthExpiredException> {
            runBlocking { auth(transportWith(loggedIn = true)).ensureLoggedIn() }
        }
    }

    @Test
    fun `failed login check throws AuthExpired and is not persisted`() {
        val store = InMemoryCredentialStore()
        val hackerRankAuth = auth(transportWith(loggedIn = false), store, prompt = OJAuthPrompt { "_hr=bad" })
        assertFailsWith<AuthExpiredException> {
            runBlocking { hackerRankAuth.ensureLoggedIn() }
        }
        assertNull(store.map[HACKERRANK_PLATFORM_ID])
    }

    @Test
    fun `user payload missing falls back to unknown username`() = runBlocking {
        val transport = transportWith(loggedIn = true)
        // banner-dashboard 200 但 dashboard 无 profile(结构变化)→ userId 兜底 unknown
        transport.handler = { request ->
            when (request.url) {
                HACKERRANK_LOGIN_CHECK_URL -> """{"slots":[]}"""
                HACKERRANK_DASHBOARD_URL -> """<html><body><script id="__NEXT_DATA__" type="application/json">{}</script></body></html>"""
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val session = auth(transport, prompt = OJAuthPrompt { "_hr=1" }).ensureLoggedIn()

        assertEquals("unknown", session.userId)
    }

    @Test
    fun `logout removes secret clears jar and resets snapshot`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply { map[HACKERRANK_PLATFORM_ID] = "_hr=1" }
        val transport = transportWith(loggedIn = true)
        val hackerRankAuth = auth(transport, store)
        hackerRankAuth.ensureLoggedIn()
        assertTrue(hackerRankAuth.isLoggedIn())

        hackerRankAuth.logout()

        assertFalse(hackerRankAuth.isLoggedIn())
        assertTrue(store.map.isEmpty())
        assertTrue(HACKERRANK_PLATFORM_ID in transport.clearedPlatforms)
    }

    @Test
    fun `cookie header parsing tolerates values with equals and junk lines`() {
        val parsed =
            HackerRankAuth.parseCookieHeader(
                "_hr=1;  remember_me=abc==; invalid-line; =novalue",
            )
        assertEquals(mapOf("_hr" to "1", "remember_me" to "abc=="), parsed)
    }
}
