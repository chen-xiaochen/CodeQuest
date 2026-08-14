package com.codequest.core.repository

import com.codequest.core.model.ProblemId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId

/**
 * 标签仓库接口(WP13)。
 *
 * 标签为独立表(tag + problem_tag 关联,见 spec §3);"按标签查题"入口在
 * [ProblemRepository.findByTag],题目的标签关联随 [ProblemRepository.save] 一并落库。
 * 实现归 infra(WP14 用 Exposed);接口不出现任何持久化框架类型。
 */
interface TagRepository {
    /** 新增或更新标签。 */
    suspend fun save(tag: Tag)

    suspend fun findById(id: TagId): Tag?

    /** 按名称精确查标签(名称唯一)。 */
    suspend fun findByName(name: String): Tag?

    suspend fun findAll(): List<Tag>

    /** 某题目当前关联的全部标签。 */
    suspend fun findByProblem(problemId: ProblemId): List<Tag>
}
