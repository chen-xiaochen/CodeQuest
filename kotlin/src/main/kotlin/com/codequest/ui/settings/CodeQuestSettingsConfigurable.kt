package com.codequest.ui.settings

import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.infra.db.DatabaseFolders
import com.codequest.infra.i18n.PluginBundle
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JComponent

/**
 * CodeQuest 设置面板(WP15 数据库文件夹 / WP39 模板与自定义 CSS):Kotlin UI DSL。
 *
 * ## Templates 区布局选型(WP39)
 *
 * 「语言下拉 + 单编辑器 + 恢复默认按钮」而非每语言一个编辑器:WorkspaceLanguage
 * 共 5 门语言,逐语言平铺编辑器会把面板撑到数屏高;tab 形态要多一层容器且 reset
 * 时需逐 tab 同步。下拉切换只动一个编辑器,草稿状态集中管理在
 * [TemplateDraftsState](纯 Kotlin 逻辑核,单测覆盖 isModified/归一化/持久化)。
 * 两个 UI DSL `group` 折叠区承载,默认展开。
 *
 * ## 修改/生效语义
 *
 * - 模板:**空白或与内置默认一致 = 无覆盖**(内置来自 ChallengeTemplates);
 *   修改只对**之后创建**的 challenge 生效(两条创建链路经 createChallengeWithTemplate 读取);
 * - 自定义 CSS:仅文本存储(不校验,YAGNI),题面页**下次加载**时经
 *   `/api/custom.css` 拉取注入(端点按请求现读设置,apply 后无需重启)。
 *
 * 「数据库文件夹」校验语义(与旧版"路径不能为空"的差别,写明行为):
 * - **空值 = 默认态**,始终合法:数据库落在默认目录 `~/.codequest/`(面板注释展示当前默认目录),
 *   因此空值不报错——这正是回退默认目录的入口;
 * - **非空值**要求:必须是绝对路径(`settings.databaseFolder.error.invalid`),
 *   且若已存在则必须是目录而非文件(`settings.databaseFolder.error.notDirectory`);
 *   校验时机在 [apply](面板 OK/Apply 时抛 [ConfigurationException] 阻止保存)。
 *
 * apply 成功后经项目消息总线发布 [CodeQuestSettings.DATABASE_FOLDER_TOPIC],
 * `infra.db.DatabaseManager` 收到后 close 旧库并按新路径重建。
 *
 * WP22 修复(保留):`comment()` 文案必须保持**纯文本**——UI DSL `comment()`
 * 在 2026.2 不再按 JLabel HTML 渲染,含 `<html>` 标签会触发 UiDslException SEVERE 日志。
 */
class CodeQuestSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val languages = WorkspaceLanguage.ALL

    private var folderField: TextFieldWithBrowseButton? = null
    private var languageComboValue: WorkspaceLanguage = languages.first()
    private var templateArea: JBTextArea? = null
    private var cssArea: JBTextArea? = null
    private var drafts: TemplateDraftsState? = null

    override fun getDisplayName(): String = PluginBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val settings = CodeQuestSettings.getInstance(project)
        drafts = TemplateDraftsState(initial = settings.state.templates)

        val field = TextFieldWithBrowseButton()
        field.addBrowseFolderListener(
            PluginBundle.message("settings.databaseFolder.title"),
            null,
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
        folderField = field

        val templateEditor = codeTextArea(rows = TEMPLATE_AREA_ROWS)
        templateArea = templateEditor
        val cssEditor = codeTextArea(rows = CSS_AREA_ROWS)
        cssArea = cssEditor

        return panel {
            row(PluginBundle.message("settings.databaseFolder.label")) {
                cell(field).align(Align.FILL)
            }
            row {
                comment(
                    PluginBundle.message(
                        "settings.databaseFolder.description",
                        DatabaseFolders.defaultFolder().toString(),
                    ),
                )
            }

            group(PluginBundle.message("settings.templates.group")) {
                row(PluginBundle.message("settings.templates.language.label")) {
                    comboBox(languages)
                        .bindItem({ languageComboValue }, { language -> language?.let(::switchLanguage) })
                }
                row {
                    comment(PluginBundle.message("settings.templates.description"))
                }
                row(PluginBundle.message("settings.templates.editor.label")) {
                    cell(templateEditor).resizableColumn().align(Align.FILL)
                }.resizableRow()
                row {
                    button(PluginBundle.message("settings.templates.restoreDefault")) { restoreDefaultTemplate() }
                }
            }

            group(PluginBundle.message("settings.customCss.group")) {
                row {
                    comment(PluginBundle.message("settings.customCss.description"))
                }
                row(PluginBundle.message("settings.customCss.editor.label")) {
                    cell(cssEditor).resizableColumn().align(Align.FILL)
                }.resizableRow()
            }
        }
    }

    override fun isModified(): Boolean {
        val state = CodeQuestSettings.getInstance(project).state
        val folderModified = folderField?.text?.trim() != state.databaseFolder
        val cssModified = cssArea?.text.orEmpty() != state.customCss
        // 当前语言的编辑器文本未 flush,按实时值参与判定(与 apply 的 flush 语义一致)
        val templatesModified =
            drafts?.isModified(languageComboValue.id, templateArea?.text.orEmpty()) ?: false
        return folderModified || cssModified || templatesModified
    }

    override fun reset() {
        val state = CodeQuestSettings.getInstance(project).state
        folderField?.text = state.databaseFolder
        drafts = TemplateDraftsState(initial = state.templates)
        cssArea?.text = state.customCss
        // 不重置语言选择(combo 与 languageComboValue 始终经 switchLanguage 同步),
        // 仅按新草稿回填当前语言编辑器文本
        templateArea?.text = drafts?.editorText(languageComboValue.id).orEmpty()
    }

    override fun apply() {
        val text = folderField?.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val path = Paths.get(text)
            if (!path.isAbsolute) {
                throw ConfigurationException(PluginBundle.message("settings.databaseFolder.error.invalid"))
            }
            if (Files.exists(path) && !Files.isDirectory(path)) {
                throw ConfigurationException(PluginBundle.message("settings.databaseFolder.error.notDirectory"))
            }
        }
        // flush 当前语言编辑器文本后再归一化持久化
        drafts?.onEditorText(languageComboValue.id, templateArea?.text.orEmpty())
        val settings = CodeQuestSettings.getInstance(project)
        val effectiveFile = settings.updateDatabaseFolder(text)
        settings.updateTemplatesAndCustomCss(
            templates = drafts?.toPersisted() ?: emptyMap(),
            customCss = cssArea?.text.orEmpty(),
        )
        // 按新持久化状态刷新草稿基线(否则 apply 后 isModified 仍对旧基线报 true),
        // 并把编辑器回填为规范文本(空覆盖 → 内置默认)
        drafts = TemplateDraftsState(initial = settings.state.templates)
        templateArea?.text = drafts?.editorText(languageComboValue.id).orEmpty()
        project.messageBus
            .syncPublisher(CodeQuestSettings.DATABASE_FOLDER_TOPIC)
            .databaseFolderChanged(effectiveFile.parent)
    }

    override fun disposeUIResources() {
        folderField = null
        templateArea = null
        cssArea = null
        drafts = null
    }

    /** 语言切换:旧语言编辑器文本落草稿,新语言回填(覆盖 ?: 内置默认)。 */
    private fun switchLanguage(newLanguage: WorkspaceLanguage) {
        if (newLanguage == languageComboValue) return
        drafts?.onEditorText(languageComboValue.id, templateArea?.text.orEmpty())
        languageComboValue = newLanguage
        templateArea?.text = drafts?.editorText(newLanguage.id).orEmpty()
    }

    /** 恢复默认:当前语言草稿清空,编辑器回填内置默认文本(归一化后即无覆盖)。 */
    private fun restoreDefaultTemplate() {
        drafts?.restoreDefault(languageComboValue.id)
        templateArea?.text = drafts?.editorText(languageComboValue.id).orEmpty()
    }

    private fun codeTextArea(rows: Int): JBTextArea =
        JBTextArea().apply {
            this.rows = rows
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }

    private companion object {
        const val TEMPLATE_AREA_ROWS = 10
        const val CSS_AREA_ROWS = 5
    }
}
