package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.platform.codeforces.CODEFORCES_DOMAIN
import com.codequest.platform.codeforces.CODEFORCES_PLATFORM_ID
import com.codequest.platform.codeforces.CodeForcesPlatform
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJAuthPromptProvider
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJRuntime
import com.intellij.openapi.project.Project

/**
 * [OJAuthPromptProvider] 的 CodeForces 实现(WP24):认领 codeforces 平台,
 * 登录交互为 cookie 粘贴(选型理由见 CodeForcesAuth KDoc——CF 登录表单受
 * Turnstile 保护,程序化账密登录不可行)。
 *
 * 对话框复用 [LeetCodeCookieDialog](其文案与布局按 domain 参数化,通用),
 * 凭据原文的解析/校验/持久化归平台侧 CodeForcesAuth。
 */
class CodeForcesAuthPromptProvider : OJAuthPromptProvider {
    override val platformIds: Set<PlatformId> = setOf(CODEFORCES_PLATFORM_ID)

    override fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt? =
        if (platformId == CODEFORCES_PLATFORM_ID) {
            OJAuthPrompt { LeetCodeCookieDialog(project, CODEFORCES_DOMAIN).askCookies() }
        } else {
            null
        }

    override fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? =
        if (platformId == CODEFORCES_PLATFORM_ID) {
            CodeForcesPlatform(
                transport = OJRuntime.transport(),
                credentialStore = OJRuntime.credentialStore(),
                authPrompt = OJAuthPrompt { LeetCodeCookieDialog(project, CODEFORCES_DOMAIN).askCookies() },
            )
        } else {
            null
        }
}
