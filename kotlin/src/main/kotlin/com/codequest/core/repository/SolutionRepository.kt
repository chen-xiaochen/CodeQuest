package com.codequest.core.repository

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Solution
import com.codequest.core.model.SolutionId

/**
 * 多解法仓库接口(WP13)。
 *
 * 实现归 infra(WP14 用 Exposed,note_md 参与 FTS5);接口不出现任何持久化框架类型。
 */
interface SolutionRepository {
    /** 新增或更新解法。 */
    suspend fun save(solution: Solution)

    suspend fun findById(id: SolutionId): Solution?

    /** 某工作区的全部解法。 */
    suspend fun findByChallenge(challengeId: ChallengeId): List<Solution>

    /** 删除解法。@return 是否确实删除了一条。 */
    suspend fun delete(id: SolutionId): Boolean
}
