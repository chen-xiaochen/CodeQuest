package com.codequest.core.repository

import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.core.model.TagId

/**
 * 题目仓库接口(WP13):题目主档 + 题面缓存的持久化契约。
 *
 * 实现归 infra(WP14 用 Exposed);接口不出现任何持久化框架类型。
 * [save] 连同 [com.codequest.core.model.Problem.tags] 一并落 problem_tag 关联。
 */
interface ProblemRepository {
    /** 新增或更新题目主档(含标签关联)。 */
    suspend fun save(problem: Problem)

    suspend fun findById(id: ProblemId): Problem?

    /** 按平台 + 关键字检索(标题/remoteId 模糊匹配,前缀匹配规则由实现决定)。 */
    suspend fun search(
        platformId: PlatformId,
        keyword: String,
    ): List<Problem>

    /** 查询带某标签的全部题目(problem_tag 关联)。 */
    suspend fun findByTag(tagId: TagId): List<Problem>

    /** 新增或更新题面缓存。 */
    suspend fun saveDetail(detail: ProblemDetail)

    suspend fun findDetail(problemId: ProblemId): ProblemDetail?
}
