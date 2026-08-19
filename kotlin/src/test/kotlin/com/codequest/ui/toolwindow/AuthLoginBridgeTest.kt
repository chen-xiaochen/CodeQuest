package com.codequest.ui.toolwindow

import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 浏览链路登录入口(auth:login 桥命令,1.0.x 缺陷修复)单测。
 *
 * 背景:HackerRank 题面等浏览链路遇 401 时只弹原始错误,重登流程只挂在
 * 提交流程上——用户无从登录。本命令贯通「页面 → 桥 → SubmissionPlatforms
 * 组装的平台实例(带登录对话框)→ ensureLoggedIn → 结果回推页面」。
 */
@Suppress("FunctionName")
class AuthLoginBridgeTest {
    private class FakePlatform(
        platformId: PlatformId,
        private val auth: AuthCapable,
    ) : OJPlatform,
        AuthCapable {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info = PlatformInfo(displayName = platformId.value, iconPath = null, supportedLanguages = emptyList())

        override suspend fun ensureLoggedIn(): LoginSession = auth.ensureLoggedIn()

        override fun isLoggedIn(): Boolean = auth.isLoggedIn()

        override suspend fun logout() = auth.logout()
    }

    /** 无登录能力的平台(测试 non-auth-capable 分支)。 */
    private class BarePlatform(platformId: PlatformId) : OJPlatform {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info = PlatformInfo(displayName = platformId.value, iconPath = null, supportedLanguages = emptyList())
    }

    private class FakeAuth(
        private val platformId: PlatformId = PlatformId("hackerrank"),
        private val session: LoginSession? = null,
        private val error: Exception? = null,
    ) : AuthCapable {
        var ensureCalled = false

        override suspend fun ensureLoggedIn(): LoginSession {
            ensureCalled = true
            error?.let { throw it }
            return session ?: LoginSession(platformId, "user")
        }

        override fun isLoggedIn(): Boolean = session != null

        override suspend fun logout() = Unit
    }

    @Test
    fun `parses auth login payload`() {
        val request = parseAuthLoginPayload("""{"platform":"hackerrank"}""")
        assertEquals("hackerrank", request?.platform)
    }

    @Test
    fun `rejects blank or malformed payload`() {
        assertNull(parseAuthLoginPayload("""{"platform":"  "}"""))
        assertNull(parseAuthLoginPayload("""{"other":1}"""))
        assertNull(parseAuthLoginPayload("not-json"))
    }

    @Test
    fun `login succeeds for auth capable platform`() =
        runBlocking {
            val auth = FakeAuth(session = LoginSession(PlatformId("hackerrank"), "user"))
            val result =
                performLogin(PlatformId("hackerrank")) { FakePlatform(it, auth) }
            assertTrue(result.ok)
            assertEquals("hackerrank", result.platform)
            assertNull(result.error)
            assertTrue(auth.ensureCalled)
        }

    @Test
    fun `login failure carries exception message`() =
        runBlocking {
            val auth = FakeAuth(error = OJException("cookie rejected"))
            val result =
                performLogin(PlatformId("hackerrank")) { FakePlatform(it, auth) }
            assertFalse(result.ok)
            assertEquals("cookie rejected", result.error)
        }

    @Test
    fun `unknown platform reports failure`() =
        runBlocking {
            val result = performLogin(PlatformId("ghost")) { null }
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("unknown", ignoreCase = true))
        }

    @Test
    fun `non auth capable platform reports failure`() =
        runBlocking {
            val result = performLogin(PlatformId("x")) { BarePlatform(it) }
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("login", ignoreCase = true))
        }

    @Test
    fun `result json is stable`() {
        val ok = AuthLoginResult(ok = true, platform = "hackerrank")
        assertEquals("""{"ok":true,"platform":"hackerrank"}""", ok.toJson())
        val failed = AuthLoginResult(ok = false, platform = "hackerrank", error = "boom")
        assertEquals("""{"ok":false,"platform":"hackerrank","error":"boom"}""", failed.toJson())
    }
}
