package com.codequest.ui.challenge

import com.codequest.spi.TestRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP54:云端测试结果对话框的数据组装逻辑核——通过判定(compare_result 逐位
 * 优先,回退逐项答案相等)、汇总计数、CE/RE 替代表格分支(纯函数,脱离 Swing 可单测)。
 */
class TestRunResultStateTest {
    @Test
    fun `compare_result bitmap wins per position`() {
        val result =
            TestRunResult(
                codeAnswers = listOf("a", "b", "c"),
                expectedCodeAnswers = listOf("a", "x", "c"),
                compareResult = "11",
            )
        // 第 2 位答案不符但位图标 1 → 以位图为准;位图只覆盖前 2 位,第 3 位回退答案比较
        assertEquals(true, TestRunResultState.passedAt(result, 0))
        assertEquals(true, TestRunResultState.passedAt(result, 1))
        assertEquals(true, TestRunResultState.passedAt(result, 2))
    }

    @Test
    fun `falls back to per-item answer comparison without bitmap`() {
        val result =
            TestRunResult(
                codeAnswers = listOf("a", "b", "c"),
                expectedCodeAnswers = listOf("a", "x", "c"),
            )
        assertEquals(true, TestRunResultState.passedAt(result, 0))
        assertEquals(false, TestRunResultState.passedAt(result, 1))
        // 任一侧缺失 → 无法判定
        assertNull(TestRunResultState.passedAt(result, 3))
    }

    @Test
    fun `out-of-range bitmap position falls back to answers`() {
        val result =
            TestRunResult(
                codeAnswers = listOf("a"),
                expectedCodeAnswers = listOf("a"),
                compareResult = "",
            )
        assertEquals(true, TestRunResultState.passedAt(result, 0))
    }

    @Test
    fun `caseRows zips inputs expected actual and pass flag`() {
        val result =
            TestRunResult(
                codeAnswers = listOf("a", "b"),
                expectedCodeAnswers = listOf("a", "x"),
            )
        val rows = TestRunResultState.caseRows(result, listOf("in0", "in1"))
        assertEquals(2, rows.size)
        assertEquals(TestRunCaseRow("in0", "a", "a", true), rows[0])
        assertEquals(TestRunCaseRow("in1", "x", "b", false), rows[1])
    }

    @Test
    fun `caseRows length follows the longer answer list and tolerates short inputs`() {
        val result =
            TestRunResult(
                codeAnswers = listOf("a", "b", "c"),
                expectedCodeAnswers = listOf("a"),
            )
        val rows = TestRunResultState.caseRows(result, emptyList())
        assertEquals(3, rows.size)
        assertNull(rows[1].inputSummary)
        assertNull(rows[1].expected)
        assertEquals("b", rows[1].actual)
    }

    @Test
    fun `summary counts prefer platform-reported correctCount and totalCount`() {
        val result = TestRunResult(correctCount = 2, totalCount = 5)
        assertEquals(2 to 5, TestRunResultState.summaryCounts(result))
    }

    @Test
    fun `summary counts computed from rows when platform omits counters`() {
        val result =
            TestRunResult(
                codeAnswers = listOf("a", "b", "c"),
                expectedCodeAnswers = listOf("a", "x", "c"),
            )
        assertEquals(2 to 3, TestRunResultState.summaryCounts(result))
    }

    @Test
    fun `compile error text replaces the table, runtime error second`() {
        val ce = TestRunResult(compileError = "error: cannot find symbol")
        assertEquals("error: cannot find symbol", TestRunResultState.errorText(ce))
        val re = TestRunResult(runtimeError = "TypeError: x is undefined")
        assertEquals("TypeError: x is undefined", TestRunResultState.errorText(re))
        val both = TestRunResult(compileError = "ce", runtimeError = "re")
        assertEquals("ce", TestRunResultState.errorText(both))
    }

    @Test
    fun `blank or absent errors keep the table`() {
        assertNull(TestRunResultState.errorText(TestRunResult()))
        assertNull(TestRunResultState.errorText(TestRunResult(compileError = " ")))
        assertTrue(TestRunResultState.errorText(TestRunResult(compareResult = "1")) == null)
    }
}
