package com.codequest.core.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

/** [unit] 模板引擎(变量注入 / 工具方法 / 未知变量保持原样,WP16)。 */
class TemplateEngineTest {
    private fun context(): TemplateContext =
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
            "beginRegion" to "//IMPORTANT!! Submit Code Region Begin(Do not remove this line)",
            "endRegion" to "//IMPORTANT!! Submit Code Region End(Do not remove this line)",
        )

    @Test
    fun `braced variable injection`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        assertEquals("两数之和", engine.render("\${challenge.name}", context()))
        assertEquals("easy", engine.render("\${challenge.difficulty}", context()))
    }

    @Test
    fun `bare dollar variable injection`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        assertEquals("two-sum", engine.render("\$challenge.slug", context()))
    }

    @Test
    fun `tool method with bare dollar argument`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        assertEquals("TwoSum", engine.render("\${velocity.camelCaseName(\$challenge.slug)}", context()))
        assertEquals("two_sum", engine.render("\${velocity.snakeCaseName(\$challenge.slug)}", context()))
    }

    @Test
    fun `tool method with nested braced argument`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        // spec 2026-08-03 的文件名规则形态:外层 ${} 内嵌 ${}
        assertEquals("TwoSum", engine.render("\${velocity.camelCaseName(\${challenge.slug})}", context()))
    }

    @Test
    fun `dateTime tool uses injectable clock`() {
        val engine = TemplateEngine { "2026-01-02 03:04:05" }
        assertEquals("2026-01-02 03:04:05", engine.render("\${velocity.dateTime()}", context()))
    }

    @Test
    fun `unknown variables are kept verbatim`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        assertEquals(
            "\${challenge.unknown} stays",
            engine.render("\${challenge.unknown} stays", context()),
        )
        assertEquals("\$nope", engine.render("\$nope", context()))
        assertEquals("a \$ b", engine.render("a \$ b", context()))
    }

    @Test
    fun `renders full java entry template`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        val rendered = engine.render(ChallengeTemplates.entryTemplate("java"), context())
        // 类名来自 slug 工具、提交区域标记与用户代码都在
        kotlin.test.assertTrue(rendered.contains("class TwoSum {"), rendered)
        kotlin.test.assertTrue(
            rendered.contains("//IMPORTANT!! Submit Code Region Begin(Do not remove this line)"),
            rendered,
        )
        kotlin.test.assertTrue(rendered.contains("class Solution {}"), rendered)
        kotlin.test.assertTrue(rendered.contains("两数之和"), rendered)
        kotlin.test.assertTrue(rendered.contains("2026-08-15 10:00:00"), rendered)
    }

    @Test
    fun `renders kotlin entry template`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        val rendered = engine.render(ChallengeTemplates.entryTemplate("kotlin"), context())
        kotlin.test.assertTrue(rendered.contains("fun main()"), rendered)
        kotlin.test.assertTrue(rendered.contains("class Solution {}"), rendered)
    }

    @Test
    fun `file name rule renders with class name and extension`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        val rule = ChallengeTemplates.fileNameRule("java")
        kotlin.test.assertTrue(rule.contains("\${velocity.camelCaseName(\${challenge.slug})}"), rule)
        assertEquals("TwoSum.java", engine.renderFileName(rule, context(), "java"))
        // 规则漏写扩展名时自动补齐
        assertEquals("TwoSum.java", engine.renderFileName("\${velocity.camelCaseName(\${challenge.slug})}", context(), "java"))
        assertEquals("two_sum.kt", engine.renderFileName("\${velocity.snakeCaseName(\${challenge.slug})}", context(), "kt"))
    }

    @Test
    fun `leading digit slug stays detectable`() {
        val engine = TemplateEngine { "2026-08-15 10:00:00" }
        val ctx =
            templateContext("challenge" to mapOf("slug" to "3sum"))
        // 数字开头 slug 原样保留(旧实现语义一致,由用户手动改类名)
        assertEquals("3sum.java", engine.renderFileName("\${velocity.camelCaseName(\${challenge.slug})}", ctx, "java"))
    }
}
