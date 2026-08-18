package com.codequest.platform.luogu

import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.spi.AuthCapable
import com.codequest.spi.ContestCapable
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.OJRuntime
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.SolutionViewCapable
import com.codequest.spi.SubmissionCapable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP26:平台注册与能力组合——provider 产出 luogu 平台实例,
 * auth/problem/submission/solutions 四能力实现且**不实现** ContestCapable
 * (spec 能力矩阵洛谷无竞赛消费);依赖经 OJRuntime 注入假实现。
 */
class LuoguPlatformTest {
    @AfterTest
    fun tearDown() {
        OJRuntime.resetToDefaults()
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun withFakeRuntime(): List<LuoguPlatform> {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = { listOf(LuoguProvider()) }
        return OJPlatformRegistry.all().filterIsInstance<LuoguPlatform>()
    }

    @Test
    fun `provider registers single luogu platform`() {
        val platforms = withFakeRuntime()

        assertEquals(listOf("luogu"), platforms.map { it.id.value })
        assertEquals(listOf("www.luogu.com.cn"), platforms.map { it.domain })
        assertEquals(listOf("洛谷 Luogu"), platforms.map { it.info.displayName })
    }

    @Test
    fun `platform implements auth problem submission and solutions capabilities`() {
        val platform = withFakeRuntime().single()

        assertTrue(platform is AuthCapable, "缺少 AuthCapable")
        assertTrue(platform is ProblemQueryCapable, "缺少 ProblemQueryCapable")
        assertTrue(platform is SubmissionCapable, "缺少 SubmissionCapable")
        assertTrue(platform is SolutionViewCapable, "缺少 SolutionViewCapable")
        assertTrue(platform.info.supportedLanguages.isNotEmpty())
    }

    @Test
    fun `platform is not contest capable per spec matrix`() {
        withFakeRuntime()

        val contestCapable = OJPlatformRegistry.allWith<ContestCapable>()

        assertTrue(contestCapable.isEmpty(), "洛谷无 ContestCapable(spec 矩阵:无竞赛消费)")
    }
}
