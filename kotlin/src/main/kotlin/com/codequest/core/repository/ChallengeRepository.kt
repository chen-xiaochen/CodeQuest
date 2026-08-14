package com.codequest.core.repository

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.model.ChallengeLanguageId
import com.codequest.core.model.ProblemId

/**
 * 解题工作区仓库接口(WP13):challenge 主档 + 每语言配置的持久化契约。
 *
 * 实现归 infra(WP14 用 Exposed);接口不出现任何持久化框架类型。
 */
interface ChallengeRepository {
    /** 新增或更新工作区。 */
    suspend fun save(challenge: Challenge)

    suspend fun findById(id: ChallengeId): Challenge?

    /** 某题目的全部工作区。 */
    suspend fun findByProblem(problemId: ProblemId): List<Challenge>

    /** 删除工作区主档(语言配置的级联策略由实现决定)。@return 是否确实删除了一条。 */
    suspend fun delete(id: ChallengeId): Boolean

    /** 新增或更新工作区语言配置。 */
    suspend fun saveLanguage(language: ChallengeLanguage)

    /** 某工作区的全部语言配置。 */
    suspend fun findLanguages(challengeId: ChallengeId): List<ChallengeLanguage>

    /** 删除语言配置。@return 是否确实删除了一条。 */
    suspend fun deleteLanguage(id: ChallengeLanguageId): Boolean
}
