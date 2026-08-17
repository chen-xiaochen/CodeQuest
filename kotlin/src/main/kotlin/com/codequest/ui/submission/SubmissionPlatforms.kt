package com.codequest.ui.submission

import com.codequest.core.model.PlatformId
import com.codequest.spi.OJAuthPrompts
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * 提交链路用的平台实例解析(WP21,WP23 改造)。
 *
 * 解析顺序(无任何按平台 id 的 when 分发):
 * 1. [OJAuthPrompts]——需要交互登录 UI 的平台经 `ojAuthPromptProvider` EP
 *    装配(prompt 已回填,如 LeetCode cookie 粘贴对话框);
 * 2. [OJPlatformRegistry]——其余平台走注册表默认实例;
 * 3. 都没有则返回 null(调用方提示平台不可提交)。
 *
 * 实例按平台 id 缓存——登录态(session)跨提交复用。
 * SPI 分层不受影响:这里只消费 spi 公开契约;平台专属组装知识全部在各自的
 * OJAuthPromptProvider 实现内(新增同形态平台零改动本类)。
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
    ): OJPlatform? = OJAuthPrompts.createPlatform(project, platformId) ?: OJPlatformRegistry.find(platformId)
}
