package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** 解题工作区,对应表 `challenge`(problem_id → 多个 challenge)。 */
@Serializable
data class Challenge(
    val id: ChallengeId,
    val problemId: ProblemId,
    val name: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
)

/** 工作区语言配置,对应表 `challenge_language`(语言 + 模板 + 入口文件)。 */
@Serializable
data class ChallengeLanguage(
    val id: ChallengeLanguageId,
    val challengeId: ChallengeId,
    /** 语言标识,如 "kotlin" / "cpp" / "python"。 */
    val language: String,
    /** 语言版本,如 "2.4" / "C++20";平台未区分时可为空串。 */
    val languageVersion: String,
    /** 该语言的工作区代码模板。 */
    val template: String,
    /** 入口文件相对工作区根目录的路径。 */
    val entryFilePath: String,
)
