package com.codequest.platform.codeforces

import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.spi.AuthCapable
import com.codequest.spi.ContestCapable
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.OJRuntime
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.SubmissionCapable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP24:平台注册与能力组合——provider 产出 codeforces 平台实例,
 * 四能力 + ContestCapable 全部实现;依赖经 OJRuntime 注入假实现。
 */
class CodeForcesPlatformTest {
    @AfterTest
    fun tearDown() {
        OJRuntime.resetToDefaults()
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun withFakeRuntime(): List<CodeForcesPlatform> {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = { listOf(CodeForcesProvider()) }
        return OJPlatformRegistry.all().filterIsInstance<CodeForcesPlatform>()
    }

    @Test
    fun `provider registers single codeforces platform`() {
        val platforms = withFakeRuntime()

        assertEquals(listOf("codeforces"), platforms.map { it.id.value })
        assertEquals(listOf("codeforces.com"), platforms.map { it.domain })
        assertEquals(listOf("CodeForces"), platforms.map { it.info.displayName })
    }

    @Test
    fun `platform implements all four capabilities plus contest`() {
        val platform = withFakeRuntime().single()

        assertTrue(platform is AuthCapable, "缺少 AuthCapable")
        assertTrue(platform is ProblemQueryCapable, "缺少 ProblemQueryCapable")
        assertTrue(platform is SubmissionCapable, "缺少 SubmissionCapable")
        assertTrue(platform is ContestCapable, "缺少 ContestCapable")
        assertTrue(platform.info.supportedLanguages.isNotEmpty())
    }

    @Test
    fun `registry capability filters find codeforces for contests`() {
        withFakeRuntime()

        val contestCapable = OJPlatformRegistry.allWith<ContestCapable>()

        assertEquals(setOf("codeforces"), contestCapable.map { (it as com.codequest.spi.OJPlatform).id.value }.toSet())
    }
}
