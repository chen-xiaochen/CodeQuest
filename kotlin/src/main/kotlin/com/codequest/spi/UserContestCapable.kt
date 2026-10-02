package com.codequest.spi

import kotlinx.serialization.Serializable

/**
 * 用户竞赛分查询能力(1.7.0 F1):难度评分「分差着色」需要当前登录用户的竞赛分。
 *
 * 仅 OJ 站内有竞赛评分体系的平台实现(LeetCode 两变体);未登录/未参赛返回 null。
 * REST 消费方(`/api/rating/user-contest`)对查询失败同样降级双 null,不抛错——
 * 着色退化绝对分档,属附属能力。
 */
interface UserContestCapable {
    /**
     * 当前登录用户的竞赛分快照。
     *
     * @return 未登录或从未参赛时为 null;站点查询异常照实抛领域异常(消费方降级)。
     */
    suspend fun fetchUserContestRanking(): UserContestRanking?
}

/** 竞赛分快照(着色基准;字段最小集)。 */
@Serializable
data class UserContestRanking(
    /** 竞赛评分(如 1875.3)。 */
    val rating: Double,
    /** 已参加场次数。 */
    val attendedContestsCount: Int,
)
