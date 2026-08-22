package com.codequest.ui.challenge

import com.codequest.infra.i18n.PluginBundle
import com.codequest.spi.TestRunResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JTable

/**
 * 「云端测试结果」对话框(WP54):汇总行「X/Y 用例通过」+ 每用例明细表
 * (输入摘要/期望/实际/通过标记);compileError/runtimeError 非空时以全文区
 * 替代表格。数据组装与通过判定([TestRunResultState] 纯函数)可脱离 Swing 单测。
 *
 * @param result 云端测试终态结果(WP53 TestRunStatus.Finished 携带)
 * @param inputs 发起测试时的逐用例输入(按位对齐明细表输入摘要列,平台结果不含输入)
 */
class TestRunResultDialog(
    project: Project,
    private val result: TestRunResult,
    private val inputs: List<String> = emptyList(),
) : DialogWrapper(project) {
    private val errorText: String? = TestRunResultState.errorText(result)

    init {
        init()
        title = PluginBundle.message("challenge.dialog.testRun.title")
    }

    override fun createCenterPanel(): JComponent = if (errorText != null) errorPanel() else tablePanel()

    /** CE/RE 全文区(替代表格):标题区分编译/运行错误,正文只读可滚动。 */
    private fun errorPanel(): JComponent {
        val isCompileError = result.compileError?.isNotBlank() == true
        val errorTitleKey =
            if (isCompileError) "challenge.dialog.testRun.compileErrorTitle" else "challenge.dialog.testRun.runtimeErrorTitle"
        val area =
            JBTextArea(errorText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                rows = ERROR_AREA_ROWS
                columns = ERROR_AREA_COLUMNS
            }
        return panel {
            row {
                cell(JBLabel(PluginBundle.message(errorTitleKey)))
            }
            row {
                cell(JBScrollPane(area)).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }
    }

    private fun tablePanel(): JComponent {
        val (passed, total) = TestRunResultState.summaryCounts(result)
        val rows = TestRunResultState.caseRows(result, inputs)
        val table =
            JBTable(
                ListTableModel<TestRunCaseRow>(
                    textColumn(PluginBundle.message("challenge.dialog.testRun.column.input")) { summarize(it.inputSummary) },
                    textColumn(PluginBundle.message("challenge.dialog.testRun.column.expected")) { summarize(it.expected) },
                    textColumn(PluginBundle.message("challenge.dialog.testRun.column.actual")) { summarize(it.actual) },
                    textColumn(PluginBundle.message("challenge.dialog.testRun.column.passed")) { passedText(it.passed) },
                ).apply { addRows(rows) },
            ).apply {
                preferredScrollableViewportSize = Dimension(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                tableHeader.reorderingAllowed = false
                // 单元格截断展示,悬停看全文
                setDefaultRenderer(
                    Any::class.java,
                    object : javax.swing.table.DefaultTableCellRenderer() {
                        override fun getTableCellRendererComponent(
                            table: JTable,
                            value: Any?,
                            isSelected: Boolean,
                            hasFocus: Boolean,
                            row: Int,
                            column: Int,
                        ): Component {
                            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                            toolTipText = value?.toString()
                            return c
                        }
                    },
                )
            }
        return panel {
            row {
                cell(JBLabel(PluginBundle.message("challenge.dialog.testRun.summary", passed, total)))
            }
            row {
                cell(JBScrollPane(table)).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }
    }

    /** 通过标记文案:平台未给出判定信息时显示占位符。 */
    private fun passedText(passed: Boolean?): String =
        when (passed) {
            true -> PluginBundle.message("challenge.panel.run.passed")
            false -> PluginBundle.message("challenge.panel.run.failed")
            null -> UNKNOWN_MARK
        }

    /** 单元格摘要:取首行、截断到固定长度(全文经悬停提示可见)。 */
    private fun summarize(text: String?): String {
        if (text == null) return UNKNOWN_MARK
        val firstLine =
            text
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
        return if (firstLine.length <= SUMMARY_MAX_CHARS) firstLine else firstLine.take(SUMMARY_MAX_CHARS) + ELLIPSIS
    }

    private companion object {
        const val UNKNOWN_MARK = "-"
        const val ELLIPSIS = "…"
        const val SUMMARY_MAX_CHARS = 60
        const val VIEWPORT_WIDTH = 720
        const val VIEWPORT_HEIGHT = 300
        const val ERROR_AREA_ROWS = 14
        const val ERROR_AREA_COLUMNS = 60
    }
}

/** 只读字符串列。 */
private fun textColumn(
    header: String,
    read: (TestRunCaseRow) -> String,
): ColumnInfo<TestRunCaseRow, String> =
    object : ColumnInfo<TestRunCaseRow, String>(header) {
        override fun valueOf(item: TestRunCaseRow): String = read(item)
    }
