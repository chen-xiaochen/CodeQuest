package com.codequest.infra.http

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.OJException
import com.codequest.core.model.RateLimitedException
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * HTTP 响应 → OJ 领域异常映射(WP18)。
 *
 * 统一在 infra.http 完成:平台适配器(WP19+)经 [OJHttpClient] 发请求,拿到的
 * 非 2xx 一律是 core 领域异常体系,适配器不再感知状态码:
 *
 * | 响应特征                                   | 领域异常                            |
 * |--------------------------------------------|-------------------------------------|
 * | 401 / 403(无反爬特征)                     | [AuthExpiredException]             |
 * | 429                                        | [RateLimitedException](含 retryAfter) |
 * | Cloudflare 挑战页特征 / cf-mitigated 头    | [AntiBotChallengeException]("cloudflare") |
 * | 其余非 2xx                                 | [OJException](状态码 + 响应体片段)  |
 */
internal object OJErrorMapping {
    /** Cloudflare 挑战页的响应体特征(小写匹配)。 */
    private val CHALLENGE_BODY_MARKERS =
        listOf(
            "just a moment",
            "challenges.cloudflare.com",
            "cf-chl",
            "turnstile",
            "challenge-platform",
            "attention required! | cloudflare",
        )

    private const val CHALLENGE_TYPE_CLOUDFLARE = "cloudflare"

    /** 响应体片段长度上限:异常 message 只携带可读片段,不塞整页 HTML。 */
    private const val BODY_SNIPPET_MAX_LENGTH = 200

    /** 2xx 成功区间边界。 */
    private const val SUCCESS_MIN = 200
    private const val SUCCESS_MAX = 299

    /** Retry-After 秒 → 毫秒换算。 */
    private const val MILLIS_PER_SECOND = 1000L

    /** 状态码 → 领域异常;2xx 返回 null(调用方正常返回响应体)。 */
    fun toDomainException(
        status: HttpStatusCode,
        body: String,
        retryAfterHeaderValue: String?,
        cfMitigatedHeaderValue: String?,
    ): OJException? {
        if (status.value in SUCCESS_MIN..SUCCESS_MAX) return null

        // 反爬判定优先于鉴权:403 既可能是登录失效也可能是 challenge 页,
        // 由响应体特征 / cf-mitigated 头区分。
        if (isCloudflareChallenge(status, body, cfMitigatedHeaderValue)) {
            return AntiBotChallengeException(
                "anti-bot challenge (cloudflare) at HTTP ${status.value}: ${snippet(body)}",
                challengeType = CHALLENGE_TYPE_CLOUDFLARE,
            )
        }
        if (status.value == HttpStatusCode.TooManyRequests.value) {
            return RateLimitedException(
                "rate limited at HTTP ${status.value}: ${snippet(body)}",
                retryAfterMs = parseRetryAfterMillis(retryAfterHeaderValue),
            )
        }
        if (status.value == HttpStatusCode.Unauthorized.value || status.value == HttpStatusCode.Forbidden.value) {
            return AuthExpiredException(
                "auth expired or missing at HTTP ${status.value}: ${snippet(body)}",
            )
        }
        return OJException("HTTP ${status.value}: ${snippet(body)}")
    }

    /** Cloudflare 挑战页识别:cf-mitigated: challenge 头,或 403/503 + 响应体特征。 */
    private fun isCloudflareChallenge(
        status: HttpStatusCode,
        body: String,
        cfMitigatedHeaderValue: String?,
    ): Boolean {
        if (cfMitigatedHeaderValue?.trim()?.equals("challenge", ignoreCase = true) == true) return true
        if (status.value != HttpStatusCode.Forbidden.value && status.value != HttpStatusCode.ServiceUnavailable.value) {
            return false
        }
        val lowered = body.lowercase()
        return CHALLENGE_BODY_MARKERS.any { lowered.contains(it) }
    }

    /**
     * Retry-After 解析(RFC 7231:秒数或 HTTP 日期)→ 毫秒。
     * 解析失败或缺失返回 null,由调用方自行退避。
     */
    internal fun parseRetryAfterMillis(value: String?): Long? {
        val raw = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        raw.toLongOrNull()?.let { seconds -> return seconds * MILLIS_PER_SECOND }
        return runCatching {
            val retryAt = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw))
            Duration.between(Instant.now(), retryAt).toMillis().coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun snippet(body: String): String =
        body
            .lineSequence()
            .joinToString(" ")
            .trim()
            .take(BODY_SNIPPET_MAX_LENGTH)
}
