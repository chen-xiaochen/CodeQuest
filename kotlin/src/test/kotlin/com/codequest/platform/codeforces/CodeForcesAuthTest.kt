package com.codequest.platform.codeforces

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
 * [unit] WP24:CodeForcesAuth(cookie 粘贴登录)——凭据恢复/登录态校验
 * (settings 页 userbox)/持久化/退出,经 FakeOJTransport,不上网络。
 * 登录选型:CF 登录表单有 Turnstile 保护,程序化账密登录不可行(旧实现同样
 * 只依赖浏览器 cookie),故选 cookie 粘贴(LeetCode 同形态)。
 */
class CodeForcesAuthTest {
    /** settings 页响应:loggedIn 时含 userbox + 侧栏,未登录时只有登录表单。 */
    private fun transportWith(
        loggedIn: Boolean,
        handle: String? = "tourist",
    ): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            check(request.method == "GET" && request.url.endsWith("/settings/general")) {
                "unexpected ${request.method} ${request.url}"
            }
            if (loggedIn) {
                """
                <div class="personal-sidebar"><div class="avatar">
                  <a href="/profile/first"><img src="/a.png"/></a> <a href="/profile/${handle}">${handle}</a>
                </div></div>
                <div class="userbox"><div class="info">${handle}</div></div>
                """.trimIndent()
            } else {
                """<form class="enterForm"><input name="handle"/></form>"""
            }
        }
        return transport
    }

    private fun auth(
        transport: FakeOJTransport,
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        prompt: OJAuthPrompt = OJAuthPrompt { null },
    ) = CodeForcesAuth(CodeForcesApi(transport), transport, store, prompt)

    @Test
    fun `saved cookies are restored injected and verified without prompting`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply {
                map[CODEFORCES_PLATFORM_ID] = "JSESSIONID=cf-session; 39ce7=cookie-value"
            }
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store).ensureLoggedIn()

        assertEquals("codeforces", session.platformId.value)
        assertEquals("tourist", session.userId)
        // cookie 已注入该平台 Jar
        assertEquals("cf-session", transport.cookieJars[CODEFORCES_PLATFORM_ID]!!["JSESSIONID"])
        assertEquals("cookie-value", transport.cookieJars[CODEFORCES_PLATFORM_ID]!!["39ce7"])
    }

    @Test
    fun `prompted cookies are verified then persisted raw`() = runBlocking {
        val store = InMemoryCredentialStore()
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store, prompt = OJAuthPrompt { "JSESSIONID=fresh" }).ensureLoggedIn()

        assertEquals("tourist", session.userId)
        assertEquals("JSESSIONID=fresh", store.map[CODEFORCES_PLATFORM_ID])
    }

    @Test
    fun `cancelled prompt throws AuthExpired`() {
        assertFailsWith<AuthExpiredException> {
            runBlocking { auth(transportWith(loggedIn = true)).ensureLoggedIn() }
        }
    }

    @Test
    fun `invalid cookie with no userbox throws AuthExpired and is not persisted`() {
        val store = InMemoryCredentialStore()
        val cfAuth = auth(transportWith(loggedIn = false), store, prompt = OJAuthPrompt { "JSESSIONID=bad" })
        assertFailsWith<AuthExpiredException> {
            runBlocking { cfAuth.ensureLoggedIn() }
        }
        assertNull(store.map[CODEFORCES_PLATFORM_ID])
    }

    /** WP44:无已存凭据时 restoreSession 静默返回 null,绝不弹 prompt。 */
    @Test
    fun `restoreSession stays silent returning null without stored credentials`() = runBlocking {
        var prompted = false
        val cfAuth =
            auth(
                transportWith(loggedIn = true),
                prompt =
                    OJAuthPrompt {
                        prompted = true
                        null
                    },
            )

        assertNull(cfAuth.restoreSession())
        assertNull(cfAuth.currentSession())
        assertFalse(prompted) // 静默恢复:不触发交互登录
    }

    /** WP44:有效已存凭据静默注入校验成功,会话与快照就位。 */
    @Test
    fun `restoreSession silently restores valid stored credentials`() = runBlocking {
        var prompted = false
        val store =
            InMemoryCredentialStore().apply {
                map[CODEFORCES_PLATFORM_ID] = "JSESSIONID=cf-session"
            }
        val cfAuth =
            auth(
                transportWith(loggedIn = true),
                store,
                prompt =
                    OJAuthPrompt {
                        prompted = true
                        null
                    },
            )

        val session = cfAuth.restoreSession()

        assertEquals("tourist", session?.userId)
        assertEquals("tourist", cfAuth.currentSession()?.userId)
        assertFalse(prompted)
    }

    /** WP44:已存凭据失效时静默返回 null,不落交互登录。 */
    @Test
    fun `restoreSession returns null for invalid stored credentials without prompting`() = runBlocking {
        var prompted = false
        val store =
            InMemoryCredentialStore().apply {
                map[CODEFORCES_PLATFORM_ID] = "JSESSIONID=stale"
            }
        val cfAuth =
            auth(
                transportWith(loggedIn = false),
                store,
                prompt =
                    OJAuthPrompt {
                        prompted = true
                        null
                    },
            )

        assertNull(cfAuth.restoreSession())
        assertNull(cfAuth.currentSession())
        assertFalse(prompted)
    }

    @Test
    fun `sidebar username missing falls back to unknown`() = runBlocking {
        val transport = transportWith(loggedIn = true, handle = "tourist")
        // settings 页无 personal-sidebar(结构变化)→ userId 兜底 unknown,登录仍成立
        transport.handler = { _ -> """<div class="userbox"><div class="info">x</div></div>""" }

        val session = auth(transport, prompt = OJAuthPrompt { "JSESSIONID=x" }).ensureLoggedIn()

        assertEquals("unknown", session.userId)
    }

    @Test
    fun `logout removes secret clears jar and resets snapshot`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply { map[CODEFORCES_PLATFORM_ID] = "JSESSIONID=s" }
        val transport = transportWith(loggedIn = true)
        val cfAuth = auth(transport, store)
        cfAuth.ensureLoggedIn()

        cfAuth.logout()

        assertFalse(cfAuth.isLoggedIn())
        assertTrue(store.map.isEmpty())
        assertTrue(CODEFORCES_PLATFORM_ID in transport.clearedPlatforms)
    }

    @Test
    fun `cookie header parsing tolerates values with equals and junk lines`() {
        val parsed =
            CodeForcesAuth.parseCookieHeader(
                "JSESSIONID=abc==;  39ce7=tok; invalid-line; =novalue",
            )
        assertEquals(mapOf("JSESSIONID" to "abc==", "39ce7" to "tok"), parsed)
    }
}
