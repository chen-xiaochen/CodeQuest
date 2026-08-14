package com.codequest.core.model

import kotlinx.serialization.Serializable

/** 测试用例来源。 */
@Serializable
enum class TestCaseSource {
    /** 用户手工创建。 */
    USER,

    /** 从平台题面样例导入。 */
    PLATFORM,
}

/** 测试用例,对应表 `test_case`(challenge_id, input, expected, 来源:用户/平台)。 */
@Serializable
data class TestCase(
    val id: TestCaseId,
    val challengeId: ChallengeId,
    val input: String,
    val expected: String,
    val source: TestCaseSource,
)
