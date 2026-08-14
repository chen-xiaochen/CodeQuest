package com.codequest.core.model

import kotlinx.serialization.Serializable

/*
 * 领域 id 类型(WP13)。
 *
 * 全部为 String 包装的 value class,零运行时开销;JSON 序列化形态为纯字符串,
 * WP14 数据层据此映射 SQLite TEXT 主键(或 UUID 字符串),跨层传参时类型不混用。
 */

/** 在线判定平台标识,如 "leetcode" / "leetcodecn" / "codeforces" / "atcoder" / "luogu" / "hackerrank"。 */
@JvmInline
@Serializable
value class PlatformId(
    val value: String,
)

/** 题目主档 id。 */
@JvmInline
@Serializable
value class ProblemId(
    val value: String,
)

/** 解题工作区(challenge)id。 */
@JvmInline
@Serializable
value class ChallengeId(
    val value: String,
)

/** challenge 语言配置 id。 */
@JvmInline
@Serializable
value class ChallengeLanguageId(
    val value: String,
)

/** 解法(solution)id。 */
@JvmInline
@Serializable
value class SolutionId(
    val value: String,
)

/** 测试用例 id。 */
@JvmInline
@Serializable
value class TestCaseId(
    val value: String,
)

/** 提交记录 id。 */
@JvmInline
@Serializable
value class SubmissionId(
    val value: String,
)

/** 竞赛 id。 */
@JvmInline
@Serializable
value class ContestId(
    val value: String,
)

/** 标签 id。 */
@JvmInline
@Serializable
value class TagId(
    val value: String,
)
