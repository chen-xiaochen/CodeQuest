package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJAuthPromptProvider
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJRuntime
import com.intellij.openapi.project.Project

/**
 * [OJAuthPromptProvider] 的 LeetCode 实现(WP23,EP 第一个注册):认领 leetcode /
 * leetcodecn 两站点,装配注入 [UiCookiePrompt](cookie 粘贴对话框)的平台实例。
 *
 * 组装逻辑自 SubmissionPlatforms 的 when 分支迁入(transport/凭据存储仍经
 * [OJRuntime],与注册表 provider 同源)——此后新增同形态平台 = 新 provider
 * + plugin.xml 一行,ui 聚合层零改动。
 */
class LeetCodeAuthPromptProvider : OJAuthPromptProvider {
    override val platformIds: Set<PlatformId> =
        setOf(LeetCodeVariant.LEETCODE.id, LeetCodeVariant.LEETCODE_CN.id)

    override fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt? =
        variantFor(platformId)?.let { variant ->
            OJAuthPrompt { UiCookiePrompt(project, variant.domain).requestCookies() }
        }

    override fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? =
        variantFor(platformId)?.let { variant ->
            LeetCodePlatform(
                variant = variant,
                transport = OJRuntime.transport(),
                credentialStore = OJRuntime.credentialStore(),
                cookiePrompt = UiCookiePrompt(project, variant.domain),
            )
        }

    private fun variantFor(platformId: PlatformId): LeetCodeVariant? = LeetCodeVariant.entries.firstOrNull { it.id == platformId }
}
