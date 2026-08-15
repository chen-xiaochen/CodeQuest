package com.codequest.infra.db

import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId

/**
 * 端点用题目存取端口(WP20):core [com.codequest.core.repository.ProblemRepository]
 * 之上补一个「按 (platform, remoteId) 精确查主档」的查询,供 REST 端点做缓存命中判断。
 *
 * 放 infra.db 而非 core:core 契约(WP13)不含按 remoteId 查询,WP20 约束不改既有
 * core 文件;该查询是端点组合层需求,由 [ExposedProblemRepository] 直接实现本接口。
 */
interface ProblemStore {
    /** 新增或更新题目主档(含标签关联),语义同 ProblemRepository.save。 */
    suspend fun save(problem: Problem)

    /** 按平台 + remoteId 精确匹配题目主档;不存在返回 null。 */
    suspend fun findByRemoteId(
        platformId: PlatformId,
        remoteId: String,
    ): Problem?

    suspend fun findDetail(problemId: ProblemId): ProblemDetail?

    suspend fun saveDetail(detail: ProblemDetail)
}
