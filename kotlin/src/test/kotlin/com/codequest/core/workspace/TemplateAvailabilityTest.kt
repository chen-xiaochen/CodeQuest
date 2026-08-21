package com.codequest.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] WP45:模版可用性纯函数——usableLanguages 按风格过滤持久化表(空白/缺失
 * = 不可用),defaultLanguage Java 优先否则第一个;风格之间互不影响。
 */
class TemplateAvailabilityTest {
    @Test
    fun `empty persisted map yields no usable language and null default`() {
        assertEquals(emptyList(), TemplateAvailability.usableLanguages(TemplateStyle.ACM, emptyMap()))
        assertNull(TemplateAvailability.defaultLanguage(emptyList()))
    }

    @Test
    fun `blank or missing entries are not usable`() {
        val persisted =
            mapOf(
                "acm:java" to "   ",
                "leetcode:java" to "",
                "acm:kotlin" to "// ok",
            )
        assertEquals(
            listOf(WorkspaceLanguage.KOTLIN),
            TemplateAvailability.usableLanguages(TemplateStyle.ACM, persisted),
        )
        assertEquals(emptyList(), TemplateAvailability.usableLanguages(TemplateStyle.LEETCODE, persisted))
    }

    @Test
    fun `java wins over earlier declared languages as default`() {
        // ALL 声明序 KOTLIN, JAVA, PYTHON, CPP, GO——kotlin 声明在前,Java 仍优先
        val available = listOf(WorkspaceLanguage.KOTLIN, WorkspaceLanguage.JAVA)
        assertEquals(WorkspaceLanguage.JAVA, TemplateAvailability.defaultLanguage(available))
    }

    @Test
    fun `first available is default when java is missing`() {
        // java 未配置,kotlin+cpp 可用 → 取声明序第一个(kotlin)
        val persisted =
            mapOf(
                "leetcode:kotlin" to "// k",
                "leetcode:cpp" to "// c",
            )
        val usable = TemplateAvailability.usableLanguages(TemplateStyle.LEETCODE, persisted)
        assertEquals(listOf(WorkspaceLanguage.KOTLIN, WorkspaceLanguage.CPP), usable)
        assertEquals(WorkspaceLanguage.KOTLIN, TemplateAvailability.defaultLanguage(usable))
    }

    @Test
    fun `usable languages keep declaration order`() {
        val persisted =
            mapOf(
                "acm:go" to "// g",
                "acm:python3" to "// p",
                "acm:java" to "// j",
            )
        assertEquals(
            listOf(WorkspaceLanguage.JAVA, WorkspaceLanguage.PYTHON, WorkspaceLanguage.GO),
            TemplateAvailability.usableLanguages(TemplateStyle.ACM, persisted),
        )
    }

    @Test
    fun `styles are isolated - acm config does not affect leetcode usable`() {
        val persisted =
            mapOf(
                "acm:java" to "// j",
                "acm:kotlin" to "// k",
            )
        assertEquals(
            listOf(WorkspaceLanguage.KOTLIN, WorkspaceLanguage.JAVA),
            TemplateAvailability.usableLanguages(TemplateStyle.ACM, persisted),
        )
        val leetcodeUsable = TemplateAvailability.usableLanguages(TemplateStyle.LEETCODE, persisted)
        assertEquals(emptyList(), leetcodeUsable)
        assertNull(TemplateAvailability.defaultLanguage(leetcodeUsable))
    }
}
