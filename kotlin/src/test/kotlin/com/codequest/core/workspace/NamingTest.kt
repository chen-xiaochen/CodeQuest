package com.codequest.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

/** [unit] 命名工具(沿用旧 VelocityTool 语义,WP16)。 */
class NamingTest {
    @Test
    fun `camelCaseName splits on space hyphen underscore`() {
        assertEquals("TwoSum", Naming.camelCaseName("two-sum"))
        assertEquals("TwoSumX", Naming.camelCaseName("two sum_x"))
        assertEquals("ABC", Naming.camelCaseName("a-b-c"))
    }

    @Test
    fun `camelCaseName handles empty and single word`() {
        assertEquals("", Naming.camelCaseName(""))
        assertEquals("Sum", Naming.camelCaseName("sum"))
    }

    @Test
    fun `smallCamelCaseName lowercases first letter`() {
        assertEquals("twoSum", Naming.smallCamelCaseName("two-sum"))
        assertEquals("a", Naming.smallCamelCaseName("A"))
        assertEquals("", Naming.smallCamelCaseName(""))
    }

    @Test
    fun `snakeCaseName splits camelCase and separators`() {
        assertEquals("two_sum", Naming.snakeCaseName("two-sum"))
        assertEquals("two_sum", Naming.snakeCaseName("TwoSum"))
        assertEquals("max_sub_array", Naming.snakeCaseName("MaxSubArray"))
    }

    @Test
    fun `slugify lowercases and joins with hyphen`() {
        assertEquals("two-sum", Naming.slugify("Two Sum"))
        assertEquals("twosum", Naming.slugify("TwoSum"))
    }
}
