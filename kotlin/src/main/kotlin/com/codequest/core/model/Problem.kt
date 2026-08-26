package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 题目难度。
 *
 * 各平台粒度不一(LC 三档、CF 数字 rating、洛谷中文档位),故保留原始字符串
 * (如 "easy" / "1900" / "普及+"),展示侧自行映射文案与颜色。
 */
@JvmInline
@Serializable
value class Difficulty(
    val value: String,
)

/** 题目标签,独立表 + problem_tag 关联(见 spec §3)。 */
@Serializable
data class Tag(
    val id: TagId,
    val name: String,
    /** 1.6.0:标签 slug(LC 平台过滤用,如 "binary-search");平台无此概念为 null。 */
    val slug: String? = null,
    /** 1.6.0:中文标签名(如 "二分查找");平台未提供为 null,展示侧回退 [name]。 */
    val nameCn: String? = null,
)

/** 题目主档,对应表 `problem`(id, platform_id, dojo_problem_id, title, difficulty, url, fetched_at)。 */
@Serializable
data class Problem(
    val id: ProblemId,
    val platformId: PlatformId,
    /** 平台侧题目标识,如 CF "1927A"、LC 标题 slug、洛谷 pid。 */
    val remoteId: String,
    val title: String,
    /**
     * 1.6.0:副标题(标题另一语言形态——CN 站主标题为中文时存英文,反之存中文);
     * 平台无双语概念或未拉到为 null,展示侧隐藏。列 `secondary_title`。
     */
    val titleSecondary: String? = null,
    val difficulty: Difficulty,
    val url: String,
    val tags: List<Tag>,
    @Serializable(with = InstantSerializer::class)
    val fetchedAt: Instant,
)

/** 题面内容的渲染格式标记。 */
@Serializable
enum class ContentFormat {
    MARKDOWN,
    HTML,
}

/** 题面缓存,对应表 `problem_content`(raw_md/html + 渲染格式标记),支持离线回看。 */
@Serializable
data class ProblemDetail(
    val problemId: ProblemId,
    /** 题面原文;内容形态由 [contentFormat] 决定(Markdown 或 HTML)。 */
    val contentMd: String,
    val contentFormat: ContentFormat,
)
