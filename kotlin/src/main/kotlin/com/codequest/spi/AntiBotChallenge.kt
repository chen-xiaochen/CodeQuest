package com.codequest.spi

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.PlatformId

/**
 * Cloudflare Turnstile 反爬挑战描述(WP23)。
 *
 * 移植自旧 CloudflareTurnstileDialog.TurnstileConfig(M3 前实现事实):
 * JCEF 弹窗加载 [pageUrl] → 移除 [challengeSelector] 命中的挑战节点 → 注入
 * Turnstile 挂件([sitekey])→ 用户完成人机验证后求值 [resultExpression] 回传
 * 原始结果串(如 `csrf + "," + turnstile`),由平台侧自行解析回填。
 *
 * 配置错误(空 sitekey / 相对 pageUrl / domain 带协议)在构造点即失败,
 * 平台适配器(WP24 CodeForces / WP25 AtCoder)写常量时立刻暴露。
 */
data class TurnstileConfig(
    /** 挑战所属平台(用于 cookie 注入与异常文案)。 */
    val platformId: PlatformId,
    /** 平台主域名(不带协议与路径,如 "atcoder.jp"),JCEF cookie 注入目标。 */
    val domain: String,
    /** 弹窗加载的目标页绝对 URL(http/https)。 */
    val pageUrl: String,
    /** Cloudflare Turnstile sitekey。 */
    val sitekey: String,
    /** 待移除的挑战元素 CSS 选择器(如 "div.cf-challenge")。 */
    val challengeSelector: String,
    /** 验证完成后求值的 JS 表达式,产出回传结果串(如 `csrf + "," + turnstile`)。 */
    val resultExpression: String,
    /** 挂件渲染前执行的额外 JS(如 CF 定义 ftaa/bfaa);AtCoder 为空。 */
    val extraJs: String = "",
) {
    init {
        require(domain.isNotBlank()) { "domain 不得为空" }
        require(!domain.contains("://") && !domain.contains('/')) {
            "domain 必须是纯主机名(不带协议/路径): $domain"
        }
        require(pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) {
            "pageUrl 必须是绝对 http(s) URL: $pageUrl"
        }
        require(sitekey.isNotBlank()) { "sitekey 不得为空" }
        require(challengeSelector.isNotBlank()) { "challengeSelector 不得为空" }
        require(resultExpression.isNotBlank()) { "resultExpression 不得为空" }
    }
}

/**
 * 反爬挑战人机交互处理器(WP23):平台适配器经 [OJRuntime.antiBotHandler] 取得,
 * 弹出 UI 引导用户完成验证,**平台包零 UI 依赖**(接口落位 spi,同 [OJTransport]
 * 模式;生产实现是 ui/antibot 的 JCEF 弹窗,经 EP 注入)。
 *
 * 返回值语义:成功 = [TurnstileConfig.resultExpression] 求值结果原文(平台自行
 * split/解析);用户取消或环境不可用返回 null,平台应将 null 视为「挑战未完成」,
 * 抛 [AntiBotChallengeException] 走统一错误通知。
 */
interface AntiBotChallengeHandler {
    suspend fun solve(challenge: TurnstileConfig): String?
}

/** 处理器实现来源(EP `com.codequest.antiBotHandlerProvider`),形态对齐 [OJPlatformProvider]。 */
interface AntiBotChallengeHandlerProvider {
    fun create(): AntiBotChallengeHandler
}

/**
 * 缺省处理器:无 UI 扩展注册时(纯后台/测试环境)兜底——把「需要人机验证」
 * 以领域异常上抛,链路照常走到统一错误通知,而非 NPE/error 崩溃。
 */
private class UnavailableAntiBotHandler : AntiBotChallengeHandler {
    override suspend fun solve(challenge: TurnstileConfig): String? =
        throw AntiBotChallengeException(
            "平台 ${challenge.platformId.value} 需要人机验证,但未注册反爬处理器扩展" +
                "(com.codequest.antiBotHandlerProvider)",
            challengeType = "cloudflare-turnstile",
        )
}

/** [OJRuntime.antiBotHandler] 的无注册兜底实例(spi 包内可见)。 */
internal fun unavailableAntiBotHandler(): AntiBotChallengeHandler = UnavailableAntiBotHandler()
