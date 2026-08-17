package com.codequest.spi

import com.codequest.core.model.PlatformId
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * 平台交互登录 UI 钩子(WP23):通用「凭据输入」回调抽象。
 *
 * 形态平台无关(cookie 粘贴串 / 账号密码表单 JSON / 未来其他形态),凭据原文
 * 的解析与校验归平台 AuthCapable 实现;用户取消返回 null。
 */
fun interface OJAuthPrompt {
    /** 弹出登录交互并返回凭据原文;用户取消返回 null。 */
    suspend fun requestCredentials(): String?
}

/**
 * 交互登录装配 provider(WP23,EP `com.codequest.ojAuthPromptProvider`)。
 *
 * ## 解决的问题(WP22 技术债)
 *
 * 注册表经 provider `create()` 产出的平台实例无法回填交互式登录 UI(对话框需要
 * Project 与 UI 组件),此前 ui/submission/SubmissionPlatforms 用按平台 id 的 when
 * 特判组装 LeetCode——违反「不存在任何枚举 match 分发」。本 EP 把「哪些平台需要
 * 交互登录 + 如何装配带登录 UI 的实例」下沉为可插拔 provider:新增同形态平台 =
 * 新 provider + plugin.xml 一行,ui 聚合层零改动。
 *
 * 装配出的实例与注册表实例同源(transport/凭据存储仍经 [OJRuntime]),
 * 仅多注入登录钩子。
 */
interface OJAuthPromptProvider {
    /** 本 provider 认领的平台 id;不在此集合的平台不会路由到本 provider。 */
    val platformIds: Set<PlatformId>

    /** 该平台的登录交互钩子(对话框以 [project] 为模态父级);不认领的 id 返回 null。 */
    fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt?

    /**
     * 装配带回填登录钩子的平台实例;不认领的 id 返回 null
     * (调用方回退 [OJPlatformRegistry.find] 的注册表默认实例)。
     */
    fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform?
}

/** 交互登录装配入口;生产数据来自 EP,测试可注入 [providerSource]。 */
object OJAuthPrompts {
    private val EP_NAME = ExtensionPointName<OJAuthPromptProvider>("com.codequest.ojAuthPromptProvider")

    internal fun extensionPointProviders(): List<OJAuthPromptProvider> = EP_NAME.extensionList

    @Volatile
    internal var providerSource: () -> List<OJAuthPromptProvider> = { extensionPointProviders() }

    /** 认领该平台的 provider;无(平台无交互登录需求或未注册)返回 null。 */
    fun providerFor(platformId: PlatformId): OJAuthPromptProvider? = providerSource().firstOrNull { platformId in it.platformIds }

    /** 该平台的登录交互钩子;无 provider 认领返回 null。 */
    fun promptFor(
        project: Project,
        platformId: PlatformId,
    ): OJAuthPrompt? = providerFor(platformId)?.promptFor(project, platformId)

    /** 装配带登录钩子的平台实例;无 provider 认领返回 null(调用方回退注册表)。 */
    fun createPlatform(
        project: Project,
        platformId: PlatformId,
    ): OJPlatform? = providerFor(platformId)?.createPlatform(project, platformId)

    /** 测试恢复默认来源(EP 解析)。 */
    internal fun resetToDefaults() {
        providerSource = { extensionPointProviders() }
    }
}
