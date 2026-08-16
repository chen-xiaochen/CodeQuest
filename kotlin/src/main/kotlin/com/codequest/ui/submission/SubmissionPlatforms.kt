package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.OJRuntime
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * 提交链路用的平台实例解析(WP21)。
 *
 * 为什么不直接 [OJPlatformRegistry.find]:注册表经 provider `create()` 产出,无法回填
 * [com.codequest.platform.leetcode.LeetCodeAuth.CookiePrompt];而重登流程需要真实
 * 「cookie 粘贴对话框」UI。故 LeetCode 系平台在此按 variant 组装(transport/凭据存储
 * 仍经 [OJRuntime],与 provider 同源),注入 [UiCookiePrompt] 并按平台 id 缓存——
 * 登录态(session)跨提交复用;其他平台仍走注册表,未知 id 返回 null 由调用方提示。
 *
 * SPI 分层不受影响:这里只消费 OJPlatform/AuthCapable/SubmissionCapable 公开契约。
 */
object SubmissionPlatforms {
    private val cache = ConcurrentHashMap<String, OJPlatform>()

    fun resolve(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? {
        cache[platformId.value]?.let { return it }
        val created = create(project, platformId) ?: return null
        cache[platformId.value] = created
        return created
    }

    private fun create(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? =
        when (platformId.value) {
            LeetCodeVariant.LEETCODE.id.value -> leetCode(project, LeetCodeVariant.LEETCODE)
            LeetCodeVariant.LEETCODE_CN.id.value -> leetCode(project, LeetCodeVariant.LEETCODE_CN)
            else -> OJPlatformRegistry.find(platformId)
        }

    private fun leetCode(
        project: Project,
        variant: LeetCodeVariant,
    ): OJPlatform =
        LeetCodePlatform(
            variant = variant,
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
            cookiePrompt = UiCookiePrompt(project, variant.domain),
        )
}
