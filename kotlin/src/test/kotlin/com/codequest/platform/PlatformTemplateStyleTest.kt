package com.codequest.platform

import com.codequest.core.workspace.TemplateStyle
import com.codequest.platform.atcoder.AtCoderPlatform
import com.codequest.platform.codeforces.CodeForcesPlatform
import com.codequest.platform.hackerrank.HackerRankPlatform
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.platform.luogu.LuoguPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [unit] WP44:六平台 PlatformInfo.templateStyle 声明——力扣 CN/COM 为 LEETCODE
 * 函数版,CodeForces/AtCoder/洛谷/HackerRank 为 ACM 竞赛版;直构平台实例
 * (FakeOJTransport + 内存凭据库,不走 EP 注册表、不上网络,参考
 * LeetCodeLanguagesTest 的直构模式)。
 */
class PlatformTemplateStyleTest {
    @Test
    fun `leetcode cn and com variants declare LEETCODE style`() {
        for (variant in LeetCodeVariant.entries) {
            val platform = LeetCodePlatform(variant, FakeOJTransport(), InMemoryCredentialStore())
            assertEquals(TemplateStyle.LEETCODE, platform.info.templateStyle, variant.name)
        }
    }

    @Test
    fun `contest platforms declare ACM style`() {
        assertEquals(
            TemplateStyle.ACM,
            CodeForcesPlatform(FakeOJTransport(), InMemoryCredentialStore()).info.templateStyle,
        )
        assertEquals(
            TemplateStyle.ACM,
            AtCoderPlatform(FakeOJTransport(), InMemoryCredentialStore()).info.templateStyle,
        )
        assertEquals(
            TemplateStyle.ACM,
            LuoguPlatform(FakeOJTransport(), InMemoryCredentialStore()).info.templateStyle,
        )
        assertEquals(
            TemplateStyle.ACM,
            HackerRankPlatform(FakeOJTransport(), InMemoryCredentialStore()).info.templateStyle,
        )
    }
}
