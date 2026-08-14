package com.codequest.core.repository

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId

/**
 * 测试用例仓库接口(WP13)。
 *
 * 实现归 infra(WP14 用 Exposed);接口不出现任何持久化框架类型。
 */
interface TestCaseRepository {
    /** 新增或更新测试用例。 */
    suspend fun save(testCase: TestCase)

    suspend fun findById(id: TestCaseId): TestCase?

    /** 某工作区的全部测试用例。 */
    suspend fun findByChallenge(challengeId: ChallengeId): List<TestCase>

    /** 删除测试用例。@return 是否确实删除了一条。 */
    suspend fun delete(id: TestCaseId): Boolean
}
