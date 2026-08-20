package com.codequest.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP44:entryTemplate 双风格默认——LEETCODE=函数版(与旧单参入口一致,
 * 零破坏锁定)、ACM=stdin/stdout 竞赛骨架;两风格 region 标记与
 * `${challenge.code}` 占位齐备(提交提取链路依赖)。
 */
class ChallengeTemplatesTest {
    @Test
    fun `both styles embed region markers and code placeholder for java and kotlin`() {
        for (style in TemplateStyle.entries) {
            for (languageId in listOf("java", "kotlin")) {
                val template = ChallengeTemplates.entryTemplate(style, languageId)
                assertTrue(template.contains("\${beginRegion}"), "$style/$languageId 缺 beginRegion:\n$template")
                assertTrue(template.contains("\${endRegion}"), "$style/$languageId 缺 endRegion:\n$template")
                assertTrue(template.contains("\${challenge.code}"), "$style/$languageId 缺 challenge.code:\n$template")
            }
        }
    }

    @Test
    fun `acm java template is a buffered stdin skeleton`() {
        val template = ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "java")
        assertTrue(template.contains("BufferedReader"), template)
        assertTrue(template.contains("System.in"), template)
    }

    @Test
    fun `acm kotlin template reads input via readLine`() {
        val template = ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "kotlin")
        assertTrue(template.contains("readLine()"), template)
    }

    @Test
    fun `acm style covers python cpp and go stdin skeletons`() {
        assertTrue(ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "python3").contains("input()"))
        assertTrue(ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "cpp").contains("cin"))
        assertTrue(ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "go").contains("bufio"))
    }

    @Test
    fun `leetcode java renders solution class with debug main`() {
        val engine = TemplateEngine { "2026-08-21 10:00:00" }
        val rendered =
            engine.render(
                ChallengeTemplates.entryTemplate(TemplateStyle.LEETCODE, "java"),
                templateContext(
                    "challenge" to
                        mapOf(
                            "name" to "两数之和",
                            "frontendQuestionId" to "1",
                            "difficulty" to "easy",
                            "codeDojo" to "leetcode",
                            "slug" to "two-sum",
                            "url" to "https://leetcode.cn/problems/two-sum/",
                            "code" to "class Solution {}",
                        ),
                    "beginRegion" to "//${ChallengeTemplates.REGION_BEGIN}",
                    "endRegion" to "//${ChallengeTemplates.REGION_END}",
                ),
            )
        assertTrue(rendered.contains("class Solution"), rendered)
        assertTrue(rendered.contains("class TwoSum {"), rendered)
        assertTrue(rendered.contains(ChallengeTemplates.REGION_BEGIN), rendered)
    }

    /** 旧单参入口委托 LEETCODE 风格(TemplateDraftsState/工作区服务既有调用方零破坏)。 */
    @Test
    fun `legacy single-arg entryTemplate equals leetcode style for every language`() {
        for (language in WorkspaceLanguage.ALL) {
            assertEquals(
                ChallengeTemplates.entryTemplate(TemplateStyle.LEETCODE, language.id),
                ChallengeTemplates.entryTemplate(language.id),
                language.id,
            )
        }
    }

    @Test
    fun `both styles reject unknown language`() {
        assertFailsWith<IllegalArgumentException> { ChallengeTemplates.entryTemplate(TemplateStyle.LEETCODE, "cobol") }
        assertFailsWith<IllegalArgumentException> { ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "cobol") }
    }

    @Test
    fun `storage key joins lowercase style and language`() {
        assertEquals("acm:java", TemplateStyle.ACM.storageKey("java"))
        assertEquals("leetcode:kotlin", TemplateStyle.LEETCODE.storageKey("kotlin"))
    }
}
