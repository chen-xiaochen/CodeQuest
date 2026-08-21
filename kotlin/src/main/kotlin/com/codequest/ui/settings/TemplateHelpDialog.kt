package com.codequest.ui.settings

import com.codequest.infra.i18n.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.Action
import javax.swing.JComponent

/**
 * 模版帮助对话框(WP46):设置页 Templates 区「帮助」按钮弹出,四节只读说明——
 * ①模版变量表 ②提交区域标记 ③入口文件名规则 ④风格说明;文案全部走 [PluginBundle]
 * (英中同步,keys 见 `settings.templates.help.*`)。
 *
 * 呈现选只读 [JBTextArea](lineWrap)而非 JLabel+html:内容含 `${challenge.slug}`、
 * `<扩展名>` 等字面量,html 呈现需转义 `<`;纯文本零转义。WP22 教训是 UI DSL
 * `comment()` 禁 `<html>`,这里干脆不用 html,规避同类坑(properties 值可含 `<html>`
 * 的先例见 `cookieDialog.description`,但那是受控短文案,帮助长文走纯文本)。
 */
class TemplateHelpDialog(
    project: Project,
) : DialogWrapper(project) {
    init {
        init()
        title = PluginBundle.message("settings.templates.help.title")
    }

    /** 只读帮助,仅保留 OK(关闭)一个动作。 */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val helpArea =
            JBTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                rows = HELP_AREA_ROWS
                columns = HELP_AREA_COLUMNS
                text = HELP_SECTION_KEYS.joinToString("\n\n") { PluginBundle.message(it) }
            }
        return panel {
            row {
                cell(helpArea).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }
    }

    private companion object {
        const val HELP_AREA_ROWS = 20
        const val HELP_AREA_COLUMNS = 72

        /** 四节帮助文案 key(拼接顺序即展示顺序),值见 PluginBundle{,_zh}.properties。 */
        val HELP_SECTION_KEYS =
            listOf(
                "settings.templates.help.variables",
                "settings.templates.help.region",
                "settings.templates.help.filename",
                "settings.templates.help.styles",
            )
    }
}
