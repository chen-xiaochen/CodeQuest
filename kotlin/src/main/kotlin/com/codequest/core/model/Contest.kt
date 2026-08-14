package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** 竞赛阶段(CodeForces 语义)。 */
@Serializable
enum class ContestPhase {
    /** 未开始。 */
    BEFORE,

    /** 进行中。 */
    CODING,

    /** 等待系统测试。 */
    PENDING_SYSTEM_TEST,

    /** 系统测试中。 */
    SYSTEM_TEST,

    /** 已结束。 */
    FINISHED,
}

/** 竞赛(实时数据来自 SPI,本地仅提醒落库)。 */
@Serializable
data class Contest(
    val id: ContestId,
    val platformId: PlatformId,
    /** 平台侧竞赛 id,如 CF contestId。 */
    val remoteId: String,
    val title: String,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val durationMinutes: Int,
    val phase: ContestPhase,
)

/** 竞赛提醒,对应表 `contest_reminder`(contest_id, remind_at, notified);每场竞赛一条。 */
@Serializable
data class ContestReminder(
    val contestId: ContestId,
    @Serializable(with = InstantSerializer::class)
    val remindAt: Instant,
    val notified: Boolean,
)
