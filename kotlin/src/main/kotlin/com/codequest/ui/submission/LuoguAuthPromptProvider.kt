package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.platform.luogu.LUOGU_DOMAIN
import com.codequest.platform.luogu.LUOGU_PLATFORM_ID
import com.codequest.platform.luogu.LuoguPlatform
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJAuthPromptProvider
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJRuntime
import com.intellij.openapi.project.Project

/**
 * [OJAuthPromptProvider] 的洛谷实现(WP26):认领 luogu 平台,登录交互为
 * cookie 粘贴(选型理由见 LuoguAuth KDoc——提交/题解链路要求登录态 + csrf,
 * 且提交可能触发易盾验证码,程序化账密登录不可行,全程依赖浏览器会话 cookie)。
 *
 * 对话框复用 [LeetCodeCookieDialog](其文案与布局按 domain 参数化,通用),
 * 凭据原文的解析/校验/持久化归平台侧 LuoguAuth。
 */
class LuoguAuthPromptProvider : OJAuthPromptProvider {
    override val platformIds: Set<PlatformId> = setOf(LUOGU_PLATFORM_ID)

    override fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt? =
        if (platformId == LUOGU_PLATFORM_ID) {
            OJAuthPrompt { LeetCodeCookieDialog(project, LUOGU_DOMAIN).askCookies() }
        } else {
            null
        }

    override fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? =
        if (platformId == LUOGU_PLATFORM_ID) {
            LuoguPlatform(
                transport = OJRuntime.transport(),
                credentialStore = OJRuntime.credentialStore(),
                authPrompt = OJAuthPrompt { LeetCodeCookieDialog(project, LUOGU_DOMAIN).askCookies() },
            )
        } else {
            null
        }
}
