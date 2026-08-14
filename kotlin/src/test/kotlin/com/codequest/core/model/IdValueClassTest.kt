package com.codequest.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * value class id 相等性与序列化形态单测(WP13)。
 *
 * 契约:所有领域 id 都是 String 包装的 value class,JSON 中以纯字符串出现
 * (WP14 据此映射 SQLite TEXT 主键,WP16 据此做跨层传参)。
 */
class IdValueClassTest {
    @Test
    fun `ids with same value are equal`() {
        assertEquals(ProblemId("abc"), ProblemId("abc"))
        assertEquals(PlatformId("leetcode"), PlatformId("leetcode"))
        assertEquals(ChallengeId("1"), ChallengeId("1"))
        assertEquals(SolutionId("1"), SolutionId("1"))
        assertEquals(TestCaseId("1"), TestCaseId("1"))
        assertEquals(SubmissionId("1"), SubmissionId("1"))
        assertEquals(ContestId("1"), ContestId("1"))
        assertEquals(TagId("1"), TagId("1"))
        assertEquals(ChallengeLanguageId("1"), ChallengeLanguageId("1"))
    }

    @Test
    fun `ids with different value are not equal`() {
        assertNotEquals(ProblemId("abc"), ProblemId("abd"))
        assertFalse(ProblemId("abc").equals(ProblemId("xyz")))
        assertFalse(TagId("t1") == TagId("t2"))
    }

    @Test
    fun `id equality follows value semantics in collections`() {
        val set = setOf(ProblemId("p1"), ProblemId("p1"), ProblemId("p2"))
        assertEquals(setOf(ProblemId("p1"), ProblemId("p2")), set)
    }

    @Test
    fun `ids serialize as plain strings`() {
        val json = Json
        assertEquals("abc", json.encodeToString(ProblemId.serializer(), ProblemId("abc")).let {
            json.parseToJsonElement(it).jsonPrimitive.content
        })
        assertEquals("codeforces", json.encodeToString(PlatformId.serializer(), PlatformId("codeforces")).let {
            json.parseToJsonElement(it).jsonPrimitive.content
        })
    }
}
