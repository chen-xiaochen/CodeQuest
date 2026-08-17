package com.codequest.platform.atcoder

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
 * [unit] WP25:平台注册与能力组合——provider 产出 atcoder 平台实例,
 * auth/problem/submission 三能力实现且**不实现** ContestCapable(spec 能力矩阵
 * AtCoder 无竞赛消费);依赖经 OJRuntime 注入假实现。
 */
class AtCoderPlatformTest {
    @AfterTest
    fun tearDown() {
        OJRuntime.resetToDefaults()
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun withFakeRuntime(): List<AtCoderPlatform> {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = { listOf(AtCoderProvider()) }
        return OJPlatformRegistry.all().filterIsInstance<AtCoderPlatform>()
    }

    @Test
    fun `provider registers single atcoder platform`() {
        val platforms = withFakeRuntime()

        assertEquals(listOf("atcoder"), platforms.map { it.id.value })
        assertEquals(listOf("atcoder.jp"), platforms.map { it.domain })
        assertEquals(listOf("AtCoder"), platforms.map { it.info.displayName })
    }

    @Test
    fun `platform implements auth problem and submission capabilities`() {
        val platform = withFakeRuntime().single()

        assertTrue(platform is AuthCapable, "缺少 AuthCapable")
        assertTrue(platform is ProblemQueryCapable, "缺少 ProblemQueryCapable")
        assertTrue(platform is SubmissionCapable, "缺少 SubmissionCapable")
        assertTrue(platform.info.supportedLanguages.isNotEmpty())
    }

    @Test
    fun `platform is not contest capable per spec matrix`() {
        withFakeRuntime()

        val contestCapable = OJPlatformRegistry.allWith<ContestCapable>()

        assertTrue(contestCapable.isEmpty(), "AtCoder 无 ContestCapable(spec 矩阵:无竞赛消费)")
    }
}
