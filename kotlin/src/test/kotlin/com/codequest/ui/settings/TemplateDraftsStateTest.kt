package com.codequest.ui.settings

import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [unit] WP39:Templates 区草稿状态(纯 Kotlin,面板 isModified/reset/apply 的逻辑核)。
 *
 * 归一化语义:草稿为**空白**或与**内置默认一致** → 视为无覆盖(持久化为空);
 * 编辑过才落 map。未访问语言的编辑器文本 = 已存覆盖 ?: 内置默认。
 */
class TemplateDraftsStateTest {
    @Test
    fun `initial drafts show built-in defaults and are not modified`() {
        val state = TemplateDraftsState(initial = emptyMap())
        WorkspaceLanguage.ALL.forEach { language ->
            assertEquals(ChallengeTemplates.entryTemplate(language.id), state.editorText(language.id))
        }
        assertFalse(state.isModified())
    }

    @Test
    fun `stored override is shown as editor text and reported modified-free`() {
        val state = TemplateDraftsState(initial = mapOf("kotlin" to "// my template"))
        assertEquals("// my template", state.editorText("kotlin"))
        // 其他语言仍是内置默认
        assertEquals(ChallengeTemplates.entryTemplate("java"), state.editorText("java"))
        assertFalse(state.isModified())
    }

    @Test
    fun `editing a language marks modified until restored to default`() {
        val state = TemplateDraftsState(initial = emptyMap())
        state.onEditorText("kotlin", "// edited")
        assertTrue(state.isModified())

        state.restoreDefault("kotlin")
        assertFalse(state.isModified())
    }

    @Test
    fun `blank draft is normalized to no override`() {
        val state = TemplateDraftsState(initial = mapOf("kotlin" to "// my template"))
        state.onEditorText("kotlin", "   ")
        assertEquals("", state.normalizedOverride("kotlin"))
        assertTrue(state.isModified())
    }

    @Test
    fun `draft equal to built-in default is normalized to no override`() {
        val state = TemplateDraftsState(initial = emptyMap())
        state.onEditorText("java", ChallengeTemplates.entryTemplate("java"))
        assertEquals("", state.normalizedOverride("java"))
        assertFalse(state.isModified())
    }

    @Test
    fun `toPersisted drops no-override entries and keeps raw edited text`() {
        val state = TemplateDraftsState(initial = mapOf("kotlin" to "// old"))
        state.onEditorText("kotlin", "// new\n")
        state.onEditorText("python3", ChallengeTemplates.entryTemplate("python3"))
        assertEquals(mapOf("kotlin" to "// new\n"), state.toPersisted())
    }

    @Test
    fun `apply roundtrip makes new state unmodified`() {
        val first = TemplateDraftsState(initial = emptyMap())
        first.onEditorText("go", "// go template")
        val persisted = first.toPersisted()

        val second = TemplateDraftsState(initial = persisted)
        assertEquals("// go template", second.editorText("go"))
        assertFalse(second.isModified())
    }

    @Test
    fun `isModified considers live editor text for the selected language`() {
        val state = TemplateDraftsState(initial = emptyMap())
        // 编辑器刚改了文本但尚未 flush(面板 isModified 在 apply 前调用)
        assertTrue(state.isModified(languageId = "kotlin", editorText = "// live edit"))
        // 与默认一致则不视为修改
        assertFalse(
            state.isModified(
                languageId = "kotlin",
                editorText = ChallengeTemplates.entryTemplate("kotlin"),
            ),
        )
    }
}
