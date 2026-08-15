package com.codequest.spi

import com.codequest.core.model.PlatformId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] OJPlatformRegistry 能力过滤与查找(WP17)。
 *
 * Registry 生产环境从 IntelliJ Extension Point 取 provider,测试直接注入假 provider 列表,
 * 不需要启动 IntelliJ 平台。
 */
class OJPlatformRegistryTest {

    private open class FakePlatform(
        id: String,
    ) : OJPlatform {
        override val id = PlatformId(id)
        override val domain = "$id.example.com"
        override val info = PlatformInfo(displayName = id.uppercase())
    }

    private class AuthCapablePlatform(
        id: String,
    ) : FakePlatform(id),
        AuthCapable {
        // 函数体内 `id` 解析到继承自 FakePlatform 的 PlatformId 属性(构造参数不在函数体作用域)
        override suspend fun ensureLoggedIn() = LoginSession(id, "user-${id.value}")
        override fun isLoggedIn() = true
        override suspend fun logout() = Unit
    }

    private class Provider(private val platform: OJPlatform) : OJPlatformProvider {
        override fun create() = platform
    }

    private val leetcode = FakePlatform("leetcode")
    private val leetcodecn = AuthCapablePlatform("leetcodecn")
    private val codeforces = AuthCapablePlatform("codeforces")

    @BeforeTest
    fun setUp() {
        OJPlatformRegistry.providerSource = { listOf(Provider(leetcode), Provider(leetcodecn), Provider(codeforces)) }
    }

    @AfterTest
    fun tearDown() {
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    @Test
    fun `all returns every registered platform in provider order`() {
        assertEquals(listOf(leetcode, leetcodecn, codeforces), OJPlatformRegistry.all())
    }

    @Test
    fun `find returns platform by id`() {
        assertEquals(leetcode, OJPlatformRegistry.find(PlatformId("leetcode")))
    }

    @Test
    fun `find returns null for unknown id`() {
        assertNull(OJPlatformRegistry.find(PlatformId("no-such-oj")))
    }

    @Test
    fun `allWith filters platforms by capability class`() {
        val capable = OJPlatformRegistry.allWith(AuthCapable::class)

        assertEquals(listOf(leetcodecn, codeforces), capable)
    }

    @Test
    fun `allWith reified variant matches class-based variant`() {
        assertEquals(
            OJPlatformRegistry.allWith(AuthCapable::class),
            OJPlatformRegistry.allWith<AuthCapable>(),
        )
    }

    @Test
    fun `allWith returns empty list when no platform has the capability`() {
        assertTrue(OJPlatformRegistry.allWith<SolutionViewCapable>().isEmpty())
    }
}
