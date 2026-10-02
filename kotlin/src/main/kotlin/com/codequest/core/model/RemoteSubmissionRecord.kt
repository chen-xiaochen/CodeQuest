package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 站内提交历史记录(1.7.0 F2,schema v3),对应表 `remote_submission`。
 *
 * 与本地 [Submission] 表独立:站内历史无本地 challenge,经平台 API 分页拉取落库,
 * `UNIQUE(platform_id, remote_id)` 支撑增量同步「遇已存即停」判定。
 */
@Serializable
data class RemoteSubmissionRecord(
    val platformId: PlatformId,
    /** 平台侧提交 id(LC submissionId)。 */
    val remoteId: String,
    val titleSlug: String,
    val title: String,
    /** 站内判定文案,如 "AC" / "Wrong Answer";AC 判定 = "AC"。 */
    val statusDisplay: String,
    /** 提交语言(站内名称,如 "kotlin")。 */
    val lang: String,
    /** 耗时(毫秒);站方未给出为 null。 */
    val runtimeMs: Int?,
    /** 内存(KB);站方未给出为 null。 */
    val memoryKb: Int?,
    @Serializable(with = InstantSerializer::class)
    val submittedAt: Instant,
)
