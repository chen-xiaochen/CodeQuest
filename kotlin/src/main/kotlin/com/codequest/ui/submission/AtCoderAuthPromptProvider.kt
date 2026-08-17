package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.platform.atcoder.ATCODER_DOMAIN
import com.codequest.platform.atcoder.ATCODER_PLATFORM_ID
import com.codequest.platform.atcoder.AtCoderPlatform
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJAuthPromptProvider
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJRuntime
import com.intellij.openapi.project.Project

/**
 * [OJAuthPromptProvider] 的 AtCoder 实现(WP25):认领 atcoder 平台,
 * 登录交互为 cookie 粘贴(选型理由见 AtCoderAuth KDoc——提交链路由
 * Cloudflare Turnstile 保护,程序化账密登录不可行,全程依赖浏览器会话 cookie)。
 *
 * 对话框复用 [LeetCodeCookieDialog](其文案与布局按 domain 参数化,通用),
 * 凭据原文的解析/校验/持久化归平台侧 AtCoderAuth。
 */
class AtCoderAuthPromptProvider : OJAuthPromptProvider {
    override val platformIds: Set<PlatformId> = setOf(ATCODER_PLATFORM_ID)

    override fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt? =
        if (platformId == ATCODER_PLATFORM_ID) {
            OJAuthPrompt { LeetCodeCookieDialog(project, ATCODER_DOMAIN).askCookies() }
        } else {
            null
        }

    override fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? =
        if (platformId == ATCODER_PLATFORM_ID) {
            AtCoderPlatform(
                transport = OJRuntime.transport(),
                credentialStore = OJRuntime.credentialStore(),
                authPrompt = OJAuthPrompt { LeetCodeCookieDialog(project, ATCODER_DOMAIN).askCookies() },
            )
        } else {
            null
        }
}
