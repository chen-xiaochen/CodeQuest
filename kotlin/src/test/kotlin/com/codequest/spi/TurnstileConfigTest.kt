package com.codequest.spi

import com.codequest.core.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] TurnstileConfig(WP23):平台侧(WP24 CodeForces / WP25 AtCoder)构造
 * 反爬挑战描述时的输入校验——空 sitekey / 非完整 pageUrl / domain 带协议等
 * 配置错误在构造点即失败,而非弹窗运行时才暴露。
 */
class TurnstileConfigTest {
    private fun validConfig() =
        TurnstileConfig(
            platformId = PlatformId("atcoder"),
            domain = "atcoder.jp",
            pageUrl = "https://atcoder.jp/contests/abc999/submit",
            sitekey = "0x4AAA...",
            challengeSelector = "div.cf-challenge",
            resultExpression = "csrf + \",\" + turnstile",
        )

    @Test
    fun `valid config constructs with defaults`() {
        val config = validConfig()

        assertEquals(PlatformId("atcoder"), config.platformId)
        assertEquals("atcoder.jp", config.domain)
        assertEquals("", config.extraJs)
    }

    @Test
    fun `blank sitekey is rejected`() {
        assertFailsWith<IllegalArgumentException> { validConfig().copy(sitekey = " ") }
    }

    @Test
    fun `pageUrl must be absolute http(s)`() {
        assertFailsWith<IllegalArgumentException> { validConfig().copy(pageUrl = "atcoder.jp/submit") }
        assertFailsWith<IllegalArgumentException> { validConfig().copy(pageUrl = "ftp://atcoder.jp/submit") }
    }

    @Test
    fun `domain must be host only without protocol or path`() {
        assertFailsWith<IllegalArgumentException> { validConfig().copy(domain = "https://atcoder.jp") }
        assertFailsWith<IllegalArgumentException> { validConfig().copy(domain = "atcoder.jp/") }
        assertFailsWith<IllegalArgumentException> { validConfig().copy(domain = "") }
    }

    @Test
    fun `selector and result expression must be present`() {
        assertFailsWith<IllegalArgumentException> { validConfig().copy(challengeSelector = "") }
        assertFailsWith<IllegalArgumentException> { validConfig().copy(resultExpression = "") }
    }

    @Test
    fun `copy with only valid fields still passes`() {
        val extended = validConfig().copy(extraJs = "const ftaa = window._ftaa;")
        assertTrue(extended.extraJs.isNotBlank())
    }
}
