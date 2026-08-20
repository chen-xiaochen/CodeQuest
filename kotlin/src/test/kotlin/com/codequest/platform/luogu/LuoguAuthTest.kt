package com.codequest.platform.luogu

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
 * [unit] WP26:LuoguAuth(cookie 粘贴登录)——凭据恢复/登录态校验
 * (user/setting 页 title 判定 + lentille user.uid)/持久化/退出,
 * 经 FakeOJTransport,不上网络。
 */
class LuoguAuthTest {
    private val homeHtml = """<html><head><meta name="csrf-token" content="csrf-1"></head></html>"""

    private fun settingHtml(
        loggedIn: Boolean,
        withUser: Boolean = true,
    ): String =
        if (loggedIn) {
            val user = if (withUser) ""","user":{"uid":42,"name":"kaguya"}""" else ""
            """<html><head><title>用户设置 - 洛谷</title></head><body>
               <script id="lentille-context" type="application/json">{"instance":"main"$user}</script>
               </body></html>""".trimIndent().replace("\n", "")
        } else {
            """<html><head><title>401 : 未登录</title></head><body><div id="app"><h1>401 : 请先登录</h1></div></body></html>"""
        }

    private fun transportWith(loggedIn: Boolean): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                LUOGU_USER_SETTING_URL -> settingHtml(loggedIn)
                else -> throw OJException("unexpected ${request.method} ${request.url}")
            }
        }
        return transport
    }

    private fun auth(
        transport: FakeOJTransport,
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        prompt: OJAuthPrompt = OJAuthPrompt { null },
    ) = LuoguAuth(LuoguApi(transport), transport, store, prompt)

    @Test
    fun `saved cookies are restored injected and verified without prompting`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply {
                map[LUOGU_PLATFORM_ID] = "_uid=42; _client_id=abc"
            }
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store).ensureLoggedIn()

        assertEquals("luogu", session.platformId.value)
        assertEquals("42", session.userId)
        assertEquals("42", transport.cookieJars[LUOGU_PLATFORM_ID]?.get("_uid"))
        assertEquals("abc", transport.cookieJars[LUOGU_PLATFORM_ID]?.get("_client_id"))
    }

    @Test
    fun `prompted cookies are verified then persisted raw`() = runBlocking {
        val store = InMemoryCredentialStore()
        val transport = transportWith(loggedIn = true)
        val session = auth(transport, store, prompt = OJAuthPrompt { "_uid=42" }).ensureLoggedIn()

        assertEquals("42", session.userId)
        assertEquals("_uid=42", store.map[LUOGU_PLATFORM_ID])
    }

    @Test
    fun `cancelled prompt throws AuthExpired`() {
        assertFailsWith<AuthExpiredException> {
            runBlocking { auth(transportWith(loggedIn = true)).ensureLoggedIn() }
        }
    }

    @Test
    fun `logged out page with 401 heading throws AuthExpired and is not persisted`() {
        val store = InMemoryCredentialStore()
        val luoguAuth = auth(transportWith(loggedIn = false), store, prompt = OJAuthPrompt { "_uid=bad" })
        assertFailsWith<AuthExpiredException> {
            runBlocking { luoguAuth.ensureLoggedIn() }
        }
        assertNull(store.map[LUOGU_PLATFORM_ID])
    }

    /** WP44:无已存凭据时 restoreSession 静默返回 null,绝不弹 prompt。 */
    @Test
    fun `restoreSession stays silent returning null without stored credentials`() = runBlocking {
        var prompted = false
        val luoguAuth =
            auth(
                transportWith(loggedIn = true),
                prompt =
                    OJAuthPrompt {
                        prompted = true
                        null
                    },
            )

        assertNull(luoguAuth.restoreSession())
        assertNull(luoguAuth.currentSession())
        assertFalse(prompted) // 静默恢复:不触发交互登录
    }

    /** WP44:有效已存凭据静默注入校验成功,会话与快照就位。 */
    @Test
    fun `restoreSession silently restores valid stored credentials`() = runBlocking {
        var prompted = false
        val store =
            InMemoryCredentialStore().apply {
                map[LUOGU_PLATFORM_ID] = "_uid=42"
            }
        val luoguAuth =
            auth(
                transportWith(loggedIn = true),
                store,
                prompt =
                    OJAuthPrompt {
                        prompted = true
                        null
                    },
            )

        val session = luoguAuth.restoreSession()

        assertEquals("42", session?.userId)
        assertEquals("42", luoguAuth.currentSession()?.userId)
        assertFalse(prompted)
    }

    /** WP44:已存凭据失效时静默返回 null,不落交互登录。 */
    @Test
    fun `restoreSession returns null for invalid stored credentials without prompting`() = runBlocking {
        var prompted = false
        val store =
            InMemoryCredentialStore().apply {
                map[LUOGU_PLATFORM_ID] = "_uid=stale"
            }
        val luoguAuth =
            auth(
                transportWith(loggedIn = false),
                store,
                prompt =
                    OJAuthPrompt {
                        prompted = true
                        null
                    },
            )

        assertNull(luoguAuth.restoreSession())
        assertNull(luoguAuth.currentSession())
        assertFalse(prompted)
    }

    @Test
    fun `transport auth error during check maps to logged out`() = runBlocking {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                // 未登录时洛谷返回 HTTP 401(实测 UserunloginException),transport 翻译为领域异常
                LUOGU_USER_SETTING_URL -> throw AuthExpiredException("auth expired or missing at HTTP 401")
                else -> throw OJException("unexpected ${request.url}")
            }
        }
        val luoguAuth = auth(transport, prompt = OJAuthPrompt { "_uid=x" })

        assertFailsWith<AuthExpiredException> {
            runBlocking { luoguAuth.ensureLoggedIn() }
        }
    }

    @Test
    fun `user payload missing falls back to unknown uid`() = runBlocking {
        val transport = transportWith(loggedIn = true)
        // title 在但 lentille user 缺失(结构变化)→ userId 兜底 unknown,登录仍成立
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                LUOGU_USER_SETTING_URL -> settingHtml(loggedIn = true, withUser = false)
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val session = auth(transport, prompt = OJAuthPrompt { "_uid=1" }).ensureLoggedIn()

        assertEquals("unknown", session.userId)
    }

    @Test
    fun `logout removes secret clears jar and resets snapshot`() = runBlocking {
        val store =
            InMemoryCredentialStore().apply { map[LUOGU_PLATFORM_ID] = "_uid=42" }
        val transport = transportWith(loggedIn = true)
        val luoguAuth = auth(transport, store)
        luoguAuth.ensureLoggedIn()
        assertTrue(luoguAuth.isLoggedIn())

        luoguAuth.logout()

        assertFalse(luoguAuth.isLoggedIn())
        assertTrue(store.map.isEmpty())
        assertTrue(LUOGU_PLATFORM_ID in transport.clearedPlatforms)
    }

    @Test
    fun `cookie header parsing tolerates values with equals and junk lines`() {
        val parsed =
            LuoguAuth.parseCookieHeader(
                "_uid=42;  _client_id=abc==; invalid-line; =novalue",
            )
        assertEquals(mapOf("_uid" to "42", "_client_id" to "abc=="), parsed)
    }
}
