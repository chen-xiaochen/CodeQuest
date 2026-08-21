package com.codequest.ui.settings

import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.TemplateStyle
import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [unit] WP45:Templates 区草稿状态(纯 Kotlin,面板 isModified/reset/apply 的逻辑核)。
 *
 * 双键 + 显式启用语义:空白 = 未配置;非空白 = 已配置(**与内置默认一致也算已配置**,
 * WP45 废除 WP39「与内置一致 → 无覆盖」);「恢复默认」= 草稿回填内置默认文本
 * (非空白,可持久化),不再清空。
 */
class TemplateDraftsStateTest {
    @Test
    fun `initial drafts show style-specific built-in defaults and are not modified`() {
        val state = TemplateDraftsState(initial = emptyMap())
        TemplateStyle.entries.forEach { style ->
            WorkspaceLanguage.ALL.forEach { language ->
                assertEquals(
                    ChallengeTemplates.entryTemplate(style, language.id),
                    state.editorText(style, language.id),
                )
            }
        }
        assertFalse(state.isModified())
    }

    @Test
    fun `stored override is shown as editor text for its style only`() {
        val state = TemplateDraftsState(initial = mapOf("leetcode:kotlin" to "// my template"))
        assertEquals("// my template", state.editorText(TemplateStyle.LEETCODE, "kotlin"))
        // 同语言另一风格与别的语言仍是各自内置默认(风格×语言交叉隔离)
        assertEquals(
            ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "kotlin"),
            state.editorText(TemplateStyle.ACM, "kotlin"),
        )
        assertEquals(
            ChallengeTemplates.entryTemplate(TemplateStyle.LEETCODE, "java"),
            state.editorText(TemplateStyle.LEETCODE, "java"),
        )
        assertFalse(state.isModified())
    }

    @Test
    fun `blank stored entry is treated as unconfigured`() {
        val state = TemplateDraftsState(initial = mapOf("acm:java" to "   "))
        assertEquals(
            ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "java"),
            state.editorText(TemplateStyle.ACM, "java"),
        )
        assertEquals("", state.normalizedOverride(TemplateStyle.ACM, "java"))
        assertFalse(state.isModified())
    }

    @Test
    fun `draft equal to built-in default is configured and persisted`() {
        // WP45 显式启用语义:与内置默认逐字一致也算已配置(废除 WP39「一致=无覆盖」)
        val state = TemplateDraftsState(initial = emptyMap())
        val builtIn = ChallengeTemplates.entryTemplate(TemplateStyle.LEETCODE, "java")
        state.onEditorText(TemplateStyle.LEETCODE, "java", builtIn)
        assertEquals(builtIn, state.normalizedOverride(TemplateStyle.LEETCODE, "java"))
        assertTrue(state.isModified())
        assertEquals(mapOf("leetcode:java" to builtIn), state.toPersisted())
    }

    @Test
    fun `blank draft is normalized to no override but counts as modification`() {
        val state = TemplateDraftsState(initial = mapOf("leetcode:kotlin" to "// my template"))
        state.onEditorText(TemplateStyle.LEETCODE, "kotlin", "   ")
        assertEquals("", state.normalizedOverride(TemplateStyle.LEETCODE, "kotlin"))
        assertTrue(state.isModified())
        assertEquals(emptyMap<String, String>(), state.toPersisted())
    }

    @Test
    fun `restoreDefault fills draft with built-in default text and is persistable`() {
        // WP45 语义变更:恢复默认 = 回填内置默认文本(非空白=已配置),不再清空草稿
        val state = TemplateDraftsState(initial = emptyMap())
        state.onEditorText(TemplateStyle.ACM, "go", "// my custom")
        state.restoreDefault(TemplateStyle.ACM, "go")
        assertEquals(
            ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "go"),
            state.editorText(TemplateStyle.ACM, "go"),
        )
        assertTrue(state.isModified())
        assertEquals(
            mapOf("acm:go" to ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "go")),
            state.toPersisted(),
        )
    }

    @Test
    fun `restoreDefault only touches its own style and language`() {
        val state = TemplateDraftsState(initial = emptyMap())
        state.onEditorText(TemplateStyle.ACM, "go", "// keep")
        state.onEditorText(TemplateStyle.LEETCODE, "go", "// keep too")
        state.restoreDefault(TemplateStyle.ACM, "go")
        // leetcode:go 草稿不受影响;acm:go 已被回填为内置默认;其他 (风格,语言) 仍是内置默认
        assertEquals(
            mapOf(
                "acm:go" to ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "go"),
                "leetcode:go" to "// keep too",
            ),
            state.toPersisted(),
        )
    }

    @Test
    fun `editing one style-language combo is isolated from the other`() {
        val state = TemplateDraftsState(initial = emptyMap())
        state.onEditorText(TemplateStyle.ACM, "kotlin", "// acm kotlin")
        assertEquals(mapOf("acm:kotlin" to "// acm kotlin"), state.toPersisted())
        assertEquals(
            ChallengeTemplates.entryTemplate(TemplateStyle.LEETCODE, "kotlin"),
            state.editorText(TemplateStyle.LEETCODE, "kotlin"),
        )
    }

    @Test
    fun `toPersisted emits storageKey format and apply roundtrip stays unmodified`() {
        val first = TemplateDraftsState(initial = emptyMap())
        first.onEditorText(TemplateStyle.ACM, "go", "// go template")
        first.onEditorText(TemplateStyle.LEETCODE, "python3", "// py template")
        val persisted = first.toPersisted()
        assertEquals(
            mapOf(
                "acm:go" to "// go template",
                "leetcode:python3" to "// py template",
            ),
            persisted,
        )

        val second = TemplateDraftsState(initial = persisted)
        assertEquals("// go template", second.editorText(TemplateStyle.ACM, "go"))
        assertEquals("// py template", second.editorText(TemplateStyle.LEETCODE, "python3"))
        assertFalse(second.isModified())
    }

    @Test
    fun `isModified considers live editor text for the selected style and language`() {
        val state = TemplateDraftsState(initial = emptyMap())
        // 编辑器刚改了文本但尚未 flush(面板 isModified 在 apply 前调用)
        assertTrue(state.isModified(TemplateStyle.LEETCODE, "kotlin", "// live edit"))
        // 实时空白 = 未配置 = 与初始基线一致 → 不视为修改
        assertFalse(state.isModified(TemplateStyle.LEETCODE, "kotlin", "   "))
        // 风格不同的草稿不消费该实时文本
        assertTrue(state.isModified(TemplateStyle.ACM, "kotlin", "// live edit"))
    }

    @Test
    fun `switchSelection flushes old editor text and returns new selection text`() {
        // WP46 面板接线核:风格/语言切换时「先 flush 旧组合实时文本,再回填新组合文本」的时序
        val state = TemplateDraftsState(initial = mapOf("leetcode:kotlin" to "// my"))
        // 切风格:(LEETCODE,kotlin) 编辑器实时文本落草稿,返回 (ACM,kotlin) 应展示文本
        val acmKotlinText =
            state.switchSelection(
                oldStyle = TemplateStyle.LEETCODE,
                oldLanguageId = "kotlin",
                editorText = "// edited live",
                newStyle = TemplateStyle.ACM,
                newLanguageId = "kotlin",
            )
        assertEquals(ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "kotlin"), acmKotlinText)
        assertEquals("// edited live", state.editorText(TemplateStyle.LEETCODE, "kotlin"))

        // 切语言:(ACM,kotlin) 实时文本落草稿,返回 (ACM,java) 应展示文本
        val acmJavaText =
            state.switchSelection(TemplateStyle.ACM, "kotlin", "// acm live", TemplateStyle.ACM, "java")
        assertEquals(ChallengeTemplates.entryTemplate(TemplateStyle.ACM, "java"), acmJavaText)
        assertEquals("// acm live", state.editorText(TemplateStyle.ACM, "kotlin"))
        // 第一次切换落的 leetcode:kotlin 草稿不被第二次切换波及(交叉隔离)
        assertEquals("// edited live", state.editorText(TemplateStyle.LEETCODE, "kotlin"))
    }

    @Test
    fun `switchSelection to same combo updates draft and echoes text`() {
        // 面板 no-op 守卫前的底层语义:同 (风格,语言) 调用 = flush 即回填,文本原样
        val state = TemplateDraftsState(initial = emptyMap())
        val echoed =
            state.switchSelection(TemplateStyle.LEETCODE, "java", "// same sel", TemplateStyle.LEETCODE, "java")
        assertEquals("// same sel", echoed)
        assertEquals("// same sel", state.editorText(TemplateStyle.LEETCODE, "java"))
        assertTrue(state.isModified())
    }

    @Test
    fun `custom defaults function receives style and language`() {
        val state =
            TemplateDraftsState(
                initial = emptyMap(),
                defaults = { style, _ -> "default of $style" },
            )
        assertEquals("default of ACM", state.editorText(TemplateStyle.ACM, "java"))
        assertEquals("default of LEETCODE", state.editorText(TemplateStyle.LEETCODE, "java"))
        state.restoreDefault(TemplateStyle.ACM, "java")
        assertEquals("default of ACM", state.editorText(TemplateStyle.ACM, "java"))
    }
}
