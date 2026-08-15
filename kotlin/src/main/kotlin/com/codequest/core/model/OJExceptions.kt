package com.codequest.core.model

/*
 * OJ 领域异常体系(WP17)。
 *
 * 分层判断——放 core 而非 spi:
 * 1) 语义平台无关:「登录失效 / 反爬 / 限流 / 提交超时」是刷题领域词汇,与 Problem/Contest 同层,
 *    不随 SPI 接口形态演进;
 * 2) 分层依赖自洽:checkLayerDependencies 禁止 core → spi 反向依赖,而 core 用例服务
 *    (见 CoreLayer KDoc「只依赖仓库接口与 SPI」)需要在编排时 catch/翻译这些异常,
 *    若放 spi 则 core 永远无法引用;spi → core 方向合法,SPI 接口 KDoc 可正常引用;
 * 3) UI 层统一做「异常 → 通知」映射,只依赖 core 即可完成映射,无需引 spi。
 *
 * 约定:SPI 所有能力接口的实现(平台适配器)只抛本体系异常;HTTP 层错误码 → 领域异常的
 * 映射归 infra/http(WP18);平台适配器不直接弹通知。
 */

/** OJ 领域异常基类:所有 SPI 能力接口实现只抛本体系异常(见文件级说明)。 */
open class OJException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 登录态失效或未登录。
 *
 * 上层约定:捕获后触发重登流程(cookie 粘贴/账号登录对话框),成功后可自动重试一次原操作。
 */
class AuthExpiredException(
    message: String,
    cause: Throwable? = null,
) : OJException(message, cause)

/**
 * 命中反爬挑战(如 Cloudflare Turnstile、图形验证码)。
 *
 * [challengeType] 为挑战类型标记(如 "cloudflare-turnstile"),供 infra/anti-bot
 * 公共组件(M4)选择对应的人机交互策略。
 */
class AntiBotChallengeException(
    message: String,
    val challengeType: String? = null,
    cause: Throwable? = null,
) : OJException(message, cause)

/**
 * 被平台限流。
 *
 * [retryAfterMs] 为平台给出的建议重试等待(毫秒);平台未给出时为 null,由调用方自行退避。
 */
class RateLimitedException(
    message: String,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : OJException(message, cause)

/**
 * 提交轮询在最大尝试次数内未到达终态。
 *
 * 由 spi 的 BaseSubmissionFlow 状态机在 maxAttempts 耗尽时抛出;快照仍可落库,
 * 用户可手动刷新或稍后回看。
 */
class SubmissionTimeoutException(
    message: String,
    cause: Throwable? = null,
) : OJException(message, cause)
