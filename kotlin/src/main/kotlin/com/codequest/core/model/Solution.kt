package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** 多解法记录,对应表 `solution`(challenge_id, name, note_md);note_md 参与 FTS5 全文检索。 */
@Serializable
data class Solution(
    val id: SolutionId,
    val challengeId: ChallengeId,
    val name: String,
    /** 解法笔记(Markdown)。 */
    val noteMd: String,
    /** 解法代码文件相对工作区根目录的路径。 */
    val filePath: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
)
