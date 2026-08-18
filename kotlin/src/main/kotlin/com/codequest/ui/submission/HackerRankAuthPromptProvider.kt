package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.platform.hackerrank.HACKERRANK_DOMAIN
import com.codequest.platform.hackerrank.HACKERRANK_PLATFORM_ID
import com.codequest.platform.hackerrank.HackerRankPlatform
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJAuthPromptProvider
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJRuntime
import com.intellij.openapi.project.Project

/**
 * [OJAuthPromptProvider] 的 HackerRank 实现(WP27):认领 hackerrank 平台,
 * 登录交互为 cookie 粘贴(选型理由见 HackerRankAuth KDoc——旧实现从未做过
 * 程序化登录,全程依赖浏览器会话 cookie)。
 *
 * 对话框复用 [LeetCodeCookieDialog](其文案与布局按 domain 参数化,通用),
 * 凭据原文的解析/校验/持久化归平台侧 HackerRankAuth。
 */
class HackerRankAuthPromptProvider : OJAuthPromptProvider {
    override val platformIds: Set<PlatformId> = setOf(HACKERRANK_PLATFORM_ID)

    override fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt? =
        if (platformId == HACKERRANK_PLATFORM_ID) {
            OJAuthPrompt { LeetCodeCookieDialog(project, HACKERRANK_DOMAIN).askCookies() }
        } else {
            null
        }

    override fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? =
        if (platformId == HACKERRANK_PLATFORM_ID) {
            HackerRankPlatform(
                transport = OJRuntime.transport(),
                credentialStore = OJRuntime.credentialStore(),
                authPrompt = OJAuthPrompt { LeetCodeCookieDialog(project, HACKERRANK_DOMAIN).askCookies() },
            )
        } else {
            null
        }
}
