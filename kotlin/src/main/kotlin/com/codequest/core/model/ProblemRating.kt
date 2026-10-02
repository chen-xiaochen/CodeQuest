package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 题目难度评分(zerotrac 口径),对应表 `problem_rating`(1.7.0 F1,schema v3)。
 *
 * `frontendId` 为 LC 题号(questionFrontendId 字符串形态);内置资源兜底 + 在线刷新
 * upsert 全量覆盖,DB 与资源最终都装配为内存 `Map<String, Double>`。
 */
@Serializable
data class ProblemRating(
    val frontendId: String,
    val rating: Double,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
)
