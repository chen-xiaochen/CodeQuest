package com.codequest.platform.leetcode

import com.codequest.core.model.PlatformId
import com.codequest.spi.AuthCapable
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.OJRuntime
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.SolutionViewCapable
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.TestEvaluationCapable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP19:平台注册与能力组合——两个 provider 产出 leetcode/leetcodecn 两个
 * 平台实例(同码不同 id+domain),全部四能力接口可用;依赖经 OJRuntime 注入假实现。
 */
class LeetCodePlatformTest {
    @AfterTest
    fun tearDown() {
        OJRuntime.resetToDefaults()
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun withFakeRuntime(): List<LeetCodePlatform> {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = { listOf(LeetCodeProvider(), LeetCodeCnProvider()) }
        return OJPlatformRegistry.all().filterIsInstance<LeetCodePlatform>()
    }

    @Test
    fun `two providers register distinct leetcode and leetcodecn platforms`() {
        val platforms = withFakeRuntime()

        assertEquals(listOf("leetcode", "leetcodecn"), platforms.map { it.id.value })
        assertEquals(listOf("leetcode.com", "leetcode.cn"), platforms.map { it.domain })
        assertEquals(listOf("LeetCode", "力扣"), platforms.map { it.info.displayName })
    }

    @Test
    fun `platforms implement all capability interfaces`() {
        val platforms = withFakeRuntime()

        platforms.forEach { platform ->
            assertTrue(platform is AuthCapable, "${platform.id} 缺少 AuthCapable")
            assertTrue(platform is ProblemQueryCapable, "${platform.id} 缺少 ProblemQueryCapable")
            assertTrue(platform is SubmissionCapable, "${platform.id} 缺少 SubmissionCapable")
            assertTrue(platform is SolutionViewCapable, "${platform.id} 缺少 SolutionViewCapable")
            assertTrue(platform is TestEvaluationCapable, "${platform.id} 缺少 TestEvaluationCapable")
            assertTrue(platform.info.supportedLanguages.isNotEmpty())
        }
    }

    @Test
    fun `registry capability filter finds both platforms`() {
        withFakeRuntime()

        val submittable = OJPlatformRegistry.allWith<SubmissionCapable>()

        assertEquals(setOf("leetcode", "leetcodecn"), submittable.map { (it as com.codequest.spi.OJPlatform).id.value }.toSet())
        assertEquals(2, OJPlatformRegistry.allWith<AuthCapable>().size)
        assertTrue(OJPlatformRegistry.find(PlatformId("leetcodecn")) != null)
    }
}
