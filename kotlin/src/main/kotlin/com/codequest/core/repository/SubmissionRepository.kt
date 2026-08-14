package com.codequest.core.repository

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId

/**
 * 提交记录仓库接口(WP13)。
 *
 * 实现归 infra(WP14 用 Exposed);接口不出现任何持久化框架类型。
 */
interface SubmissionRepository {
    /** 新增或更新提交记录(含代码快照与判定)。 */
    suspend fun save(submission: Submission)

    suspend fun findById(id: SubmissionId): Submission?

    /** 某工作区的提交历史,按提交时间倒序(最近在前)。 */
    suspend fun findByChallenge(challengeId: ChallengeId): List<Submission>
}
