package com.codequest.ui.challenge

import com.codequest.core.workspace.TestCaseParser
import com.codequest.infra.i18n.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * 「粘贴样例批量导入测试用例」对话框(WP16)。
 *
 * 左侧粘贴 Input:/Output: 文本(格式见 [TestCaseParser]),右侧实时显示解析出的
 * 用例数与首条预览;OK 时由面板把解析结果逐条落库。
 */
class ImportTestCasesDialog(
    project: Project,
) : DialogWrapper(project) {
    private companion object {
        const val IMPORT_AREA_ROWS = 14
        const val IMPORT_AREA_COLUMNS = 40
    }

    private val textArea =
        JBTextArea().apply {
            rows = IMPORT_AREA_ROWS
            columns = IMPORT_AREA_COLUMNS
        }
    private val previewLabel =
        com.intellij.ui.components
            .JBLabel(PluginBundle.message("challenge.dialog.import.zeroParsed"))

    init {
        init()
        title = PluginBundle.message("challenge.dialog.import.title")
        textArea.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshPreview()

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshPreview()

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshPreview()
            },
        )
    }

    /** 解析结果;OK 时由调用方读取(格式非法时为空列表,禁止关窗)。 */
    val parsedCases: List<TestCaseParser.ParsedTestCase>
        get() = TestCaseParser.parseOrNull(textArea.text)

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                cell(textArea).resizableColumn().align(Align.FILL)
            }.resizableRow()
            row {
                cell(previewLabel)
            }
        }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (parsedCases.isEmpty()) {
            com.intellij.openapi.ui
                .ValidationInfo(PluginBundle.message("challenge.dialog.import.invalid"), textArea)
        } else {
            null
        }

    private fun refreshPreview() {
        val cases = TestCaseParser.parseOrNull(textArea.text)
        previewLabel.text =
            when {
                cases.isEmpty() -> {
                    PluginBundle.message("challenge.dialog.import.zeroParsed")
                }

                else -> {
                    PluginBundle.message(
                        "challenge.dialog.import.parsed",
                        cases.size,
                        cases
                            .first()
                            .input
                            .lineSequence()
                            .firstOrNull()
                            .orEmpty(),
                    )
                }
            }
    }
}
