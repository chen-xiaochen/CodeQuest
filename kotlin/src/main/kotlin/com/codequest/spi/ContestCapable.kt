package com.codequest.spi

import com.codequest.core.model.ContestPhase
import com.codequest.core.model.InstantSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 竞赛能力(WP23,M4):平台适配器按需实现(CodeForces;其余平台无竞赛概念可不实现)。
 *
 * 范围界定(WP22 结论):**排名(rating/standings)不在 SPI**——CodeForces 排名 API
 * 需要按用户逐一查询且限流严苛,统一抽象收益为负;平台侧需要排名时自行在其
 * platform 包内提供专属交互,M5 再评估是否收敛。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface ContestCapable {
    /**
     * 流式拉取竞赛列表(近期竞赛,含未开始/进行中/刚结束)。
     * 冷流:每次收集独立请求;分页由实现内部消化。
     */
    fun contests(): Flow<ContestSummary>

    /** 拉取单场竞赛详情(题目引用列表 + 可参加标记)。竞赛不存在时抛 OJException 子类。 */
    suspend fun contestDetail(remoteId: String): ContestDetail
}

/**
 * 平台侧竞赛摘要(WP23):以平台 remoteId 为键,不携带本地库主档 id
 * (竞赛主档不落库,实时数据走 SPI,理由同 [ProblemSummary] 对 ProblemId 的排除)。
 */
@Serializable
data class ContestSummary(
    /** 平台侧竞赛标识,如 CF contestId。 */
    val remoteId: String,
    val title: String,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val durationMinutes: Int,
    val phase: ContestPhase,
    /** 竞赛页 URL(可直接在浏览器打开;「参加」动作的平台内交互留各平台 WP)。 */
    val url: String,
)

/**
 * 平台侧竞赛详情(WP23 最小集):摘要 + 题目引用 + 可参加标记。
 *
 * 题目只带引用(与 [ProblemSummary] 同源的 remoteId),题面拉取走
 * [ProblemQueryCapable.fetchProblemDetail];[joinable] 表示当前登录用户
 * 可参加(未登录/资格不符为 false),由实现结合登录态判定。
 */
@Serializable
data class ContestDetail(
    val summary: ContestSummary,
    val problems: List<ContestProblemRef> = emptyList(),
    val joinable: Boolean = false,
)

/** 竞赛内题目引用(WP23):[index] 为竞赛内编号(如 CF "A"/"B")。 */
@Serializable
data class ContestProblemRef(
    /** 与 [ProblemSummary.remoteId] 同源,题面经 ProblemQueryCapable 拉取。 */
    val remoteId: String,
    val index: String? = null,
    val title: String,
)
