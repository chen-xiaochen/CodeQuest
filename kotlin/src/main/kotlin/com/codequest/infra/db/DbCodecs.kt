package com.codequest.infra.db

import com.codequest.core.model.SubmissionResult
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 列编解码器(WP14)。
 *
 * - Instant ↔ TEXT:固定宽度 ISO-8601(9 位小数秒)。不用 `Instant.toString()`
 *   的原因:它按需省略小数位,混合精度下字典序与时间序不一致("56Z" > "56.5Z"),
 *   会破坏 ORDER BY submitted_at DESC / remind_at <= now 的正确性;固定 9 位后
 *   字典序即时序,且纳秒精度无损往返([Instant.parse] 兼容 9 位小数)。
 * - SubmissionResult ↔ TEXT:kotlinx.serialization 多态 JSON(discriminator = `type`,
 *   与 core 层 [SubmissionResult] 的 SerialName 契约一致)。
 */
internal object DbCodecs {
    private val instantFormatter: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSX")
            .withZone(ZoneOffset.UTC)

    private val verdictJson: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun formatInstant(instant: Instant): String = instantFormatter.format(instant)

    fun parseInstant(text: String): Instant = Instant.parse(text)

    fun encodeVerdict(verdict: SubmissionResult): String = verdictJson.encodeToString(SubmissionResult.serializer(), verdict)

    fun decodeVerdict(text: String): SubmissionResult = verdictJson.decodeFromString(SubmissionResult.serializer(), text)
}
