package com.codequest.ui.challenge

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId
import com.codequest.core.model.TestCaseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP54:测试用例编辑对话框的逻辑核——列表↔表格行转换与增/删/改 diff(纯函数,
 * 脱离 Swing 可单测;对话框壳只做接线)。
 */
class TestCasesEditStateTest {
    private val challengeId = ChallengeId("c1")

    private fun case(
        id: String,
        input: String,
        expected: String,
    ): TestCase = TestCase(TestCaseId(id), challengeId, input, expected, TestCaseSource.USER)

    @Test
    fun `toRows keeps order and carries existing ids`() {
        val cases = listOf(case("a", "1", "2"), case("b", "3", "4"))
        val rows = TestCasesEditState.toRows(cases)
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows.mapNotNull { it.id?.value })
        assertEquals(listOf("1" to "2", "3" to "4"), rows.map { it.input to it.expected })
    }

    @Test
    fun `unchanged rows produce empty diff`() {
        val cases = listOf(case("a", "1", "2"))
        val rows = listOf(TestCaseRow(TestCaseId("a"), "1", "2"))
        val diff = TestCasesEditState.diff(cases, rows)
        assertTrue(diff.upserts.isEmpty())
        assertTrue(diff.deletes.isEmpty())
    }

    @Test
    fun `changed row is upserted with its existing id`() {
        val cases = listOf(case("a", "1", "2"), case("b", "3", "4"))
        val rows = listOf(TestCaseRow(TestCaseId("a"), "1", "2"), TestCaseRow(TestCaseId("b"), "3", "44"))
        val diff = TestCasesEditState.diff(cases, rows)
        assertEquals(listOf<TestCaseId?>(TestCaseId("b")), diff.upserts.map { it.id })
        assertEquals("44", diff.upserts.single().expected)
        assertTrue(diff.deletes.isEmpty())
    }

    @Test
    fun `row without id is insert`() {
        val diff = TestCasesEditState.diff(emptyList(), listOf(TestCaseRow(null, "9", "8")))
        assertEquals(1, diff.upserts.size)
        assertEquals(null, diff.upserts.single().id)
        assertTrue(diff.deletes.isEmpty())
    }

    @Test
    fun `absent original rows are deleted`() {
        val cases = listOf(case("a", "1", "2"), case("b", "3", "4"))
        val rows = listOf(TestCaseRow(TestCaseId("a"), "1", "2"))
        val diff = TestCasesEditState.diff(cases, rows)
        assertTrue(diff.upserts.isEmpty())
        assertEquals(listOf("b"), diff.deletes.map { it.value })
    }

    @Test
    fun `fully blank new rows are dropped but blank existing rows are kept`() {
        val cases = listOf(case("a", "1", "2"))
        val rows =
            listOf(
                TestCaseRow(null, "  ", ""),
                TestCaseRow(TestCaseId("a"), "", ""),
                TestCaseRow(null, "5", "6"),
            )
        val diff = TestCasesEditState.diff(cases, rows)
        // 空白新增行视为噪音丢弃;已有行清空内容 = 显式编辑,照常 upsert
        assertEquals(2, diff.upserts.size)
        assertEquals(listOf<TestCaseId?>(TestCaseId("a"), null), diff.upserts.map { it.id })
        assertTrue(diff.deletes.isEmpty())
    }

    @Test
    fun `clearing all rows deletes everything`() {
        val cases = listOf(case("a", "1", "2"), case("b", "3", "4"))
        val diff = TestCasesEditState.diff(cases, emptyList())
        assertTrue(diff.upserts.isEmpty())
        assertEquals(listOf("a", "b"), diff.deletes.map { it.value })
    }

    @Test
    fun `reorder alone produces no writes`() {
        val cases = listOf(case("a", "1", "2"), case("b", "3", "4"))
        val rows = listOf(TestCaseRow(TestCaseId("b"), "3", "4"), TestCaseRow(TestCaseId("a"), "1", "2"))
        val diff = TestCasesEditState.diff(cases, rows)
        assertTrue(diff.upserts.isEmpty())
        assertTrue(diff.deletes.isEmpty())
    }
}
