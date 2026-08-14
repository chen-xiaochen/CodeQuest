package com.codequest.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * [Instant] 的 JSON 序列化器:ISO-8601 字符串(如 "2026-08-14T12:34:56.789Z")。
 *
 * 领域模型时间字段统一用它,避免引入 kotlinx-datetime 依赖;
 * Exposed 侧(WP14)自行决定落库形态(TEXT/epoch millis)。
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("com.codequest.core.model.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }
}
