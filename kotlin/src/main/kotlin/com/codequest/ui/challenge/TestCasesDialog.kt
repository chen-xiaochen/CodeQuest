package com.codequest.ui.challenge

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.TestCase
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * 「编辑测试用例」对话框(WP54):两列 JBTable(输入/期望输出)批量编辑当前挑战用例。
 *
 * 打开前由调用方在 pooled 线程装载 [initialCases](testCaseRepository.findByChallenge);
 * 工具条提供增行/删行/清空;「确定」时 diff 出增/删/改([TestCasesEditState] 纯函数),
 * 后台写库(saveTestCase upsert + deleteTestCase 缺席行,与挑战工具窗测试用例区
 * 同源互通),成功后回调 [onSaved] 刷新面板。逻辑核可脱离 Swing 单测。
 */
class TestCasesDialog(
    private val project: Project,
    private val challengeId: ChallengeId,
    private val initialCases: List<TestCase>,
    private val onSaved: () -> Unit = {},
) : DialogWrapper(project) {
    private val tableModel =
        ListTableModel<TestCaseRow>(
            rowColumn(PluginBundle.message("challenge.dialog.testCases.column.input"), { it.input }, { row, v -> row.input = v }),
            rowColumn(PluginBundle.message("challenge.dialog.testCases.column.expected"), { it.expected }, { row, v -> row.expected = v }),
        ).apply { addRows(TestCasesEditState.toRows(initialCases)) }

    private val table =
        JBTable(tableModel).apply {
            preferredScrollableViewportSize = Dimension(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
            columnModel.getColumn(COL_INPUT).preferredWidth = INPUT_COLUMN_WIDTH
            columnModel.getColumn(COL_EXPECTED).preferredWidth = EXPECTED_COLUMN_WIDTH
            tableHeader.reorderingAllowed = false
        }

    init {
        init()
        title = PluginBundle.message("challenge.dialog.testCases.title")
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                button(PluginBundle.message("challenge.dialog.testCases.add")) { addRow() }
                button(PluginBundle.message("challenge.dialog.testCases.remove")) { removeSelectedRows() }
                button(PluginBundle.message("challenge.dialog.testCases.clear")) { clearAllRows() }
            }
            row {
                cell(JBScrollPane(table)).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }

    override fun doOKAction() {
        val diff = TestCasesEditState.diff(initialCases, tableModel.items)
        if (diff.upserts.isEmpty() && diff.deletes.isEmpty()) {
            close(OK_EXIT_CODE)
            return
        }
        // 后台线程写库,成功后回 EDT 关窗并回调(对齐 NewChallengeDialog 模式)
        okAction.isEnabled = false
        val dialog = this
        ApplicationManager.getApplication().executeOnPooledThread<Any> {
            val outcome =
                runCatching {
                    runBlocking {
                        val service = service()
                        diff.upserts.forEach { service.saveTestCase(challengeId, it.input, it.expected, existingId = it.id) }
                        diff.deletes.forEach { service.deleteTestCase(it) }
                    }
                }
            SwingUtilities.invokeLater {
                outcome
                    .onSuccess {
                        dialog.close(OK_EXIT_CODE)
                        onSaved()
                    }.onFailure { error ->
                        Messages.showErrorDialog(
                            dialog.project,
                            error.message ?: error.toString(),
                            PluginBundle.message("challenge.panel.dialog.errorTitle"),
                        )
                        okAction.isEnabled = true
                    }
            }
        }
    }

    private fun addRow() {
        tableModel.addRow(TestCaseRow())
        val last = tableModel.rowCount - 1
        table.setRowSelectionInterval(last, last)
        table.scrollRectToVisible(table.getCellRect(last, COL_INPUT, true))
        table.editCellAt(last, COL_INPUT)
    }

    private fun removeSelectedRows() {
        val selected = table.selectedRows
        if (selected.isEmpty()) return
        selected.reversed().forEach { tableModel.removeRow(it) }
    }

    private fun clearAllRows() {
        if (tableModel.rowCount == 0) return
        if (
            Messages.showYesNoDialog(
                PluginBundle.message("challenge.dialog.testCases.clearConfirm", tableModel.rowCount),
                PluginBundle.message("challenge.dialog.testCases.title"),
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) {
            return
        }
        repeat(tableModel.rowCount) { tableModel.removeRow(0) }
    }

    /** 与挑战面板同源的服务装配(测试用例读写走同一仓库/服务)。 */
    private fun service(): ChallengeWorkspaceService =
        ChallengeWorkspaceService(
            challengeRepository = manager().challengeRepository,
            solutionRepository = manager().solutionRepository,
            testCaseRepository = manager().testCaseRepository,
            workspaceRoot = { ChallengeWorkspaceLocations.workspaceRoot(project, it) },
        )

    private fun manager(): DatabaseManager = project.getService(DatabaseManager::class.java)

    private companion object {
        const val COL_INPUT = 0
        const val COL_EXPECTED = 1
        const val VIEWPORT_WIDTH = 720
        const val VIEWPORT_HEIGHT = 360
        const val INPUT_COLUMN_WIDTH = 420
        const val EXPECTED_COLUMN_WIDTH = 320
    }
}

/** 可编辑字符串列:读写 [TestCaseRow] 的一个字段。 */
private fun rowColumn(
    header: String,
    read: (TestCaseRow) -> String,
    write: (TestCaseRow, String) -> Unit,
): ColumnInfo<TestCaseRow, String> =
    object : ColumnInfo<TestCaseRow, String>(header) {
        override fun valueOf(item: TestCaseRow): String = read(item)

        override fun setValue(
            item: TestCaseRow,
            value: String?,
        ) = write(item, value ?: "")

        override fun isCellEditable(item: TestCaseRow?): Boolean = true
    }
