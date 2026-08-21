package com.codequest.infra.settings

import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.DatabaseFolders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    // WP39:语言模板覆盖 + 题面自定义 CSS(WP45 起模板键 = "style:languageId")
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
        val templates = linkedMapOf("leetcode:kotlin" to "// my template", "acm:java" to "// java template")
        settings.loadState(CodeQuestSettings.State(templates = templates, customCss = "body{color:red}"))
        assertEquals(templates, settings.state.templates)
        assertEquals("body{color:red}", settings.state.customCss)
    }

    @Test
    fun `entryTemplateOverride returns null for missing or blank entry`() {
        val settings = CodeQuestSettings()
        settings.loadState(
            CodeQuestSettings.State(templates = linkedMapOf("leetcode:kotlin" to "", "acm:java" to "   ")),
        )
        assertNull(settings.entryTemplateOverride(TemplateStyle.LEETCODE, "kotlin"))
        assertNull(settings.entryTemplateOverride(TemplateStyle.ACM, "java"))
        assertNull(settings.entryTemplateOverride(TemplateStyle.LEETCODE, "python3"))
    }

    @Test
    fun `entryTemplateOverride returns stored template when set`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(templates = linkedMapOf("leetcode:kotlin" to "// my template")))
        assertEquals("// my template", settings.entryTemplateOverride(TemplateStyle.LEETCODE, "kotlin"))
    }

    @Test
    fun `updateTemplatesAndCustomCss persists both into state`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(databaseFolder = "E:/data"))
        settings.updateTemplatesAndCustomCss(
            templates = mapOf("leetcode:kotlin" to "// my template"),
            customCss = "p { margin: 0 }",
        )
        assertEquals(mapOf("leetcode:kotlin" to "// my template"), settings.state.templates)
        assertEquals("p { margin: 0 }", settings.state.customCss)
        // 既有字段不受影响
        assertEquals("E:/data", settings.state.databaseFolder)
    }

    // ------------------------------------------------------------------
    // WP45:旧键(纯语言键,无冒号)双写归一迁移
    // ------------------------------------------------------------------

    @Test
    fun `legacy plain key is double-written to both styles and removed`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(templates = linkedMapOf("java" to "// legacy template")))
        assertEquals(
            mapOf(
                TemplateStyle.ACM.storageKey("java") to "// legacy template",
                TemplateStyle.LEETCODE.storageKey("java") to "// legacy template",
            ),
            settings.state.templates,
        )
        // 旧键已移除(双写后不留纯语言键)
        assertFalse(settings.state.templates.containsKey("java"))
    }

    @Test
    fun `already-normalized templates are idempotent on second loadState`() {
        val settings = CodeQuestSettings()
        // 先经历一次旧键迁移
        settings.loadState(CodeQuestSettings.State(templates = linkedMapOf("java" to "// legacy template")))
        val migrated = settings.state.templates
        // 二次 loadState(已是 "style:languageId" 新格式)无变化
        settings.loadState(CodeQuestSettings.State(templates = LinkedHashMap(migrated)))
        assertEquals(migrated, settings.state.templates)
    }

    @Test
    fun `blank legacy entry is dropped during migration`() {
        val settings = CodeQuestSettings()
        settings.loadState(
            CodeQuestSettings.State(templates = linkedMapOf("java" to "   ", "kotlin" to "")),
        )
        // 空白旧值 = 未配置,不双写进任何新键
        assertEquals(emptyMap<String, String>(), settings.state.templates)
    }

    @Test
    fun `existing new-format key wins over legacy key of same language`() {
        val settings = CodeQuestSettings()
        settings.loadState(
            CodeQuestSettings.State(
                templates = linkedMapOf(
                    "kotlin" to "// legacy value",
                    "leetcode:kotlin" to "// new value",
                ),
            ),
        )
        // 降级再升级混合态:已迁移的新键优先,旧键值不覆盖
        assertEquals(
            mapOf(
                "leetcode:kotlin" to "// new value",
                "acm:kotlin" to "// legacy value",
            ),
            settings.state.templates,
        )
    }

    @Test
    fun `blank new-format entry is preserved as-is by loadState`() {
        val settings = CodeQuestSettings()
        settings.loadState(CodeQuestSettings.State(templates = linkedMapOf("acm:java" to "  ")))
        // 新格式空白条目如实保留(仅旧格式空白条目被清除);读取语义仍为未配置
        assertEquals(mapOf("acm:java" to "  "), settings.state.templates)
        assertNull(settings.entryTemplateOverride(TemplateStyle.ACM, "java"))
    }

    @Test
    @Suppress("DEPRECATION") // 锁定 WP39 旧单参签名的 LEETCODE 委托行为(WP47 迁移后移除)
    fun `deprecated single-arg overload delegates to leetcode style`() {
        val settings = CodeQuestSettings()
        settings.loadState(
            CodeQuestSettings.State(
                templates = linkedMapOf(
                    "leetcode:kotlin" to "// leetcode template",
                    "acm:kotlin" to "// acm template",
                ),
            ),
        )
        assertEquals("// leetcode template", settings.entryTemplateOverride("kotlin"))
    }
}
