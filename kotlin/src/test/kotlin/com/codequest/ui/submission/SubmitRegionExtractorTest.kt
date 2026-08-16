package com.codequest.ui.submission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [unit] WP21:提交区域标记代码提取——正常/缺失 Begin/缺失 End/多区域/前缀变体。
 */
class SubmitRegionExtractorTest {
    private val begin = "//" + com.codequest.core.workspace.ChallengeTemplates.REGION_BEGIN
    private val end = "//" + com.codequest.core.workspace.ChallengeTemplates.REGION_END

    @Test
    fun `extracts code between markers`() {
        val content =
            """
            // header comment
            $begin
            class Solution {
                fun twoSum(a: Int): Int = a
            }
            $end

            fun main() {}
            """.trimIndent()

        val result = SubmitRegionExtractor.extract(content, "kotlin")

        val extracted = assertIs<RegionExtraction.Extracted>(result)
        assertEquals(1, extracted.regions)
        assertEquals(
            "class Solution {\n    fun twoSum(a: Int): Int = a\n}\n",
            extracted.code,
        )
    }

    @Test
    fun `missing begin marker is reported`() {
        val content =
            """
            class Solution {}
            $end
            """.trimIndent()

        assertIs<RegionExtraction.MissingBeginMarker>(SubmitRegionExtractor.extract(content, "kotlin"))
    }

    @Test
    fun `missing end marker is reported`() {
        val content =
            """
            $begin
            class Solution {}
            """.trimIndent()

        assertIs<RegionExtraction.MissingEndMarker>(SubmitRegionExtractor.extract(content, "kotlin"))
    }

    @Test
    fun `multiple regions are concatenated in order`() {
        val content =
            """
            $begin
            fun a() {}
            $end
            fun ignored() {}
            $begin
            fun b() {}
            $end
            """.trimIndent()

        val result = SubmitRegionExtractor.extract(content, "kotlin")

        val extracted = assertIs<RegionExtraction.Extracted>(result)
        assertEquals(2, extracted.regions)
        assertEquals("fun a() {}\nfun b() {}\n", extracted.code)
    }

    @Test
    fun `python hash markers are recognized`() {
        val pyBegin = "#" + com.codequest.core.workspace.ChallengeTemplates.REGION_BEGIN
        val pyEnd = "#" + com.codequest.core.workspace.ChallengeTemplates.REGION_END
        val content =
            """
            # coding: utf-8
            $pyBegin
            class Solution:
                pass
            $pyEnd
            """.trimIndent()

        val result = SubmitRegionExtractor.extract(content, "python3")

        val extracted = assertIs<RegionExtraction.Extracted>(result)
        assertEquals("class Solution:\n    pass\n", extracted.code)
    }

    @Test
    fun `indented markers and whitespace after prefix are recognized`() {
        val content =
            """
            class Outer {
                $begin
                inner code
                $end
            }
            """.trimIndent()

        val result = SubmitRegionExtractor.extract(content, "kotlin")

        val extracted = assertIs<RegionExtraction.Extracted>(result)
        // 行内容原样保留(旧实现语义:java 模板缩进的区域代码带前导缩进提交)
        assertEquals("    inner code\n", extracted.code)
    }

    @Test
    fun `marker text on a line with other code is not a marker`() {
        val content =
            """
            val x = 1 $begin
            class Solution {}
            $end
            """.trimIndent()

        // 行首带代码的“标记”不算标记行 → 无 Begin
        assertIs<RegionExtraction.MissingBeginMarker>(SubmitRegionExtractor.extract(content, "kotlin"))
    }

    @Test
    fun `second begin inside region continues accumulating without reset`() {
        val content =
            """
            $begin
            fun first() {}
            $begin
            fun second() {}
            $end
            """.trimIndent()

        val result = SubmitRegionExtractor.extract(content, "kotlin")

        val extracted = assertIs<RegionExtraction.Extracted>(result)
        assertEquals(1, extracted.regions)
        assertEquals("fun first() {}\nfun second() {}\n", extracted.code)
    }

    @Test
    fun `region with only markers yields empty extracted code`() {
        val content =
            """
            $begin
            $end
            """.trimIndent()

        val result = SubmitRegionExtractor.extract(content, "kotlin")

        val extracted = assertIs<RegionExtraction.Extracted>(result)
        assertEquals(1, extracted.regions)
        assertEquals("", extracted.code)
    }
}
