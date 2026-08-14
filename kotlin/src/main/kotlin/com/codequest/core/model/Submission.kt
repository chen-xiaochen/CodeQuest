package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 提交记录,对应表 `submission`(id, challenge_id, platform_id, remote_submission_id,
 * language, code_snapshot, verdict, time_ms, memory_kb, submitted_at)。
 *
 * 保存代码快照,回看不依赖工作区文件是否还在。
 */
@Serializable
data class Submission(
    val id: SubmissionId,
    val challengeId: ChallengeId,
    val platformId: PlatformId,
    /** 平台侧提交 id,如 CF submissionId。 */
    val remoteSubmissionId: String,
    /** 提交语言(平台侧名称),如 "Kotlin 2.1" / "GNU G++20"。 */
    val language: String,
    /** 提交代码快照。 */
    val codeSnapshot: String,
    val verdict: SubmissionResult,
    /** 耗时(毫秒);判定未出或平台未给出时为 null。 */
    val timeMs: Int?,
    /** 内存(KB);判定未出或平台未给出时为 null。 */
    val memoryKb: Int?,
    @Serializable(with = InstantSerializer::class)
    val submittedAt: Instant,
)
