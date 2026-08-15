package com.codequest.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [unit] 测试用例文本解析(WP16):input/expected 多行与特殊字符。 */
class TestCaseParserTest {
    @Test
    fun `single case with input and output`() {
        val text =
            """
            Input:
            3
            1 2 3
            Output:
            6
            """.trimIndent()
        val cases = TestCaseParser.parse(text)
        assertEquals(1, cases.size)
        assertEquals("3\n1 2 3", cases[0].input)
        assertEquals("6", cases[0].expected)
    }

    @Test
    fun `multiple cases separated by dashes`() {
        val text =
            """
            Input:
            1
            Output:
            2
            ---
            Input:
            3
            Output:
            4
            ---
            Input:
            5
            Output:
            6
            """.trimIndent()
        val cases = TestCaseParser.parse(text)
        assertEquals(3, cases.size)
        assertEquals(listOf("2", "4", "6"), cases.map { it.expected })
    }

    @Test
    fun `multiline content with interior blank line preserved`() {
        val text =
            """
            Input:
            2
            blank line follows

            tail
            Output:
            ok
            """.trimIndent()
        val cases = TestCaseParser.parse(text)
        assertEquals("2\nblank line follows\n\ntail", cases[0].input)
    }

    @Test
    fun `special characters are preserved verbatim`() {
        val special = "a < b && c > \"d\" \\\$" + "e`{f}`"
        val expectedOutput = "<>&\"'\\\\"
        val text = "Input:\n$special\nOutput:\n$expectedOutput\n"
        val cases = TestCaseParser.parse(text)
        assertEquals(special, cases[0].input)
        assertEquals(expectedOutput, cases[0].expected)
    }

    @Test
    fun `indentation inside content is preserved`() {
        val text =
            """
            Input:
              indented line
            Output:
                also indented
            """.trimIndent()
        val cases = TestCaseParser.parse(text)
        assertEquals("  indented line", cases[0].input)
        assertEquals("    also indented", cases[0].expected)
    }

    @Test
    fun `case insensitive headers with surrounding whitespace`() {
        val text = "input:  \n1\nOUTPUT:\n2\n"
        val cases = TestCaseParser.parse(text)
        assertEquals("1", cases[0].input)
        assertEquals("2", cases[0].expected)
    }

    @Test
    fun `trailing blank lines of sections are dropped`() {
        val text = "Input:\n1\n\n\nOutput:\n2\n\n\n"
        val cases = TestCaseParser.parse(text)
        assertEquals("1", cases[0].input)
        assertEquals("2", cases[0].expected)
    }

    @Test
    fun `missing output section fails`() {
        val text = "Input:\n1\n"
        assertFailsWith<IllegalArgumentException> { TestCaseParser.parse(text) }
    }

    @Test
    fun `text without headers fails`() {
        assertFailsWith<IllegalArgumentException> { TestCaseParser.parse("just some text\nmore text") }
    }

    @Test
    fun `empty case between separators is skipped`() {
        val text = "Input:\n1\nOutput:\n2\n---\n---\nInput:\n3\nOutput:\n4\n"
        val cases = TestCaseParser.parse(text)
        assertEquals(2, cases.size)
        assertTrue(cases.all { it.input.isNotEmpty() })
    }
}
