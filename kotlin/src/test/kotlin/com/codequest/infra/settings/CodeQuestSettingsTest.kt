package com.codequest.infra.settings

import com.codequest.infra.db.DatabaseFolders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] 设置状态持久化 roundtrip 与默认值语义(WP15)。
 *
 * [CodeQuestSettings] 构造不依赖 Project(服务注入只影响 getInstance 获取方式),
 * 因此 getState/loadState 可直接单测;XML 序列化由 IntelliJ PersistentStateComponent
 * 机制保证(字段为公开 var String,无自定义转换器)。
 */
class CodeQuestSettingsTest {
    @Test
    fun `state defaults to empty database folder`() {
        val settings = CodeQuestSettings()
        assertEquals("", settings.state.databaseFolder)
    }

    @Test
    fun `loadState replaces state and getState returns it back`() {
        val settings = CodeQuestSettings()
        val saved = CodeQuestSettings.State(databaseFolder = "E:/data/codequest")
        settings.loadState(saved)
        assertEquals("E:/data/codequest", settings.state.databaseFolder)
        assertEquals(saved, settings.getState())
    }

    @Test
    fun `empty folder resolves to default location`() {
        val settings = CodeQuestSettings()
        assertEquals(DatabaseFolders.defaultFolder(), settings.effectiveDatabaseFolder())
    }

    @Test
    fun `configured folder resolves to itself`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(databaseFolder = "E:/data/codequest"))
        assertEquals(
            DatabaseFolders.databaseFile("E:/data/codequest"),
            DatabaseFolders.databaseFile(settings.state.databaseFolder),
        )
    }

    @Test
    fun `updateDatabaseFolder sets state and returns effective folder`() {
        val settings = CodeQuestSettings()
        val effective = settings.updateDatabaseFolder("E:/data/codequest")
        assertEquals("E:/data/codequest", settings.state.databaseFolder)
        assertEquals(DatabaseFolders.databaseFile("E:/data/codequest"), effective)
    }

    // ------------------------------------------------------------------
    // WP39:语言模板覆盖 + 题面自定义 CSS
    // ------------------------------------------------------------------

    @Test
    fun `state defaults to empty templates and custom css`() {
        val settings = CodeQuestSettings()
        assertEquals(emptyMap<String, String>(), settings.state.templates)
        assertEquals("", settings.state.customCss)
    }

    @Test
    fun `templates map and custom css roundtrip via loadState`() {
        val settings = CodeQuestSettings()
        val templates = linkedMapOf("kotlin" to "// my template", "java" to "// java template")
        settings.loadState(CodeQuestSettings.State(templates = templates, customCss = "body{color:red}"))
        assertEquals(templates, settings.state.templates)
        assertEquals("body{color:red}", settings.state.customCss)
    }

    @Test
    fun `entryTemplateOverride returns null for missing or blank entry`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(templates = linkedMapOf("kotlin" to "", "java" to "   ")))
        assertNull(settings.entryTemplateOverride("kotlin"))
        assertNull(settings.entryTemplateOverride("java"))
        assertNull(settings.entryTemplateOverride("python3"))
    }

    @Test
    fun `entryTemplateOverride returns stored template when set`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(templates = linkedMapOf("kotlin" to "// my template")))
        assertEquals("// my template", settings.entryTemplateOverride("kotlin"))
    }

    @Test
    fun `updateTemplatesAndCustomCss persists both into state`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(databaseFolder = "E:/data"))
        settings.updateTemplatesAndCustomCss(
            templates = mapOf("kotlin" to "// my template"),
            customCss = "p { margin: 0 }",
        )
        assertEquals(mapOf("kotlin" to "// my template"), settings.state.templates)
        assertEquals("p { margin: 0 }", settings.state.customCss)
        // 既有字段不受影响
        assertEquals("E:/data", settings.state.databaseFolder)
    }
}
