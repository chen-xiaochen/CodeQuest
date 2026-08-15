package com.codequest.ui.challenge

import com.codequest.core.model.Challenge
import com.codequest.core.model.Solution
import com.codequest.core.model.TestCase
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.core.workspace.ChallengeWorkspaceService.CreatedChallenge
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.execution.JavaRunner
import com.codequest.infra.execution.KotlinRunner
import com.codequest.infra.execution.OutputComparator
import com.codequest.infra.execution.RunnerRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.logging.Logger
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * CodeQuest Challenge 工具窗口工厂(WP16)。面板实现在 [ChallengeWorkspacePanel]。
 */
class ChallengeWorkspaceToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = ChallengeWorkspacePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

/**
 * challenge 工作区面板(WP16,Swing + Kotlin UI DSL)。
 *
 * UI 选型说明:工作区是「文件 + 表格 + 进程运行」的重交互面板,与 IDE 编辑器/
 * 虚拟文件系统/本地进程深度耦合;JCEF 页面(M3+)适合只读数据浏览,故此处选 Swing。
 *
 * 三块区域:
 * 1. 工作区选择/创建(combo + New Challenge)
 * 2. 多解法列表(创建/打开文件/改笔记/删除)
 * 3. 测试用例(增删改/批量导入/本地运行比对)
 *
 * 所有 DB/文件/进程操作在 pooled 线程执行(runBlocking 包 suspend 仓库调用),
 * UI 更新一律回 EDT。
 */
class ChallengeWorkspacePanel(
    private val project: Project,
) {
    private val logger = Logger.getLogger(ChallengeWorkspacePanel::class.java.name)

    private val challengeComboModel = javax.swing.DefaultComboBoxModel<Challenge>()
    private val challengeCombo = JComboBox(challengeComboModel)

    private val solutionModel = CollectionListModel<Solution>()
    private val solutionList = JBList(solutionModel)
    private val noteArea =
        JBTextArea().apply {
            rows = NOTE_AREA_ROWS
            lineWrap = true
        }

    private val testCaseModel = CollectionListModel<TestCase>()
    private val testCaseList = JBList(testCaseModel)
    private val inputArea = JBTextArea().apply { rows = CASE_AREA_ROWS }
    private val expectedArea = JBTextArea().apply { rows = CASE_AREA_ROWS }
    private val runOutputArea =
        JBTextArea().apply {
            rows = RUN_OUTPUT_ROWS
            isEditable = false
        }

    private val runnerRegistry = RunnerRegistry(JavaRunner(), KotlinRunner())

    val component: JComponent = buildPanel()

    init {
        challengeCombo.renderer = textRenderer { it.name }
        solutionList.cellRenderer = textRenderer { it.name }
        testCaseList.cellRenderer = textRenderer { testCase -> "Case: ${testCase.input.lineSequence().firstOrNull().orEmpty()}" }
        challengeCombo.addActionListener { loadSelectedChallenge() }
        solutionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        testCaseList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        solutionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                solutionList.selectedValue?.let { solution -> noteArea.text = solution.noteMd }
            }
        }
        testCaseList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                testCaseList.selectedValue?.let { case ->
                    inputArea.text = case.input
                    expectedArea.text = case.expected
                }
            }
        }
        refreshChallenges()
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    private fun buildPanel(): JComponent =
        panel {
            row("Challenge:") {
                cell(challengeCombo)
                    .resizableColumn()
                    .align(Align.FILL)
                button("Refresh") { refreshChallenges() }
                button("New Challenge...") { showNewChallengeDialog() }
                button("Open Entry") { openSelectedEntry() }
            }
            group("Solutions") {
                row {
                    cell(solutionList).resizableColumn().align(Align.FILL)
                }.resizableRow()
                row {
                    button("New Solution...") { createNewSolution() }
                    button("Open File") { openSelectedSolution() }
                    button("Save Note") { saveSelectedNote() }
                    button("Delete") { deleteSelectedSolution() }
                }
                row("Note (Markdown, searchable):") {
                    cell(noteArea).resizableColumn().align(Align.FILL)
                }.resizableRow()
            }
            group("Test Cases") {
                row {
                    cell(testCaseList).resizableColumn().align(Align.FILL)
                }.resizableRow()
                row {
                    button("New") { clearTestCaseForm() }
                    button("Save") { saveTestCaseForm() }
                    button("Import...") { importTestCases() }
                    button("Delete") { deleteSelectedTestCase() }
                    button("Run All") { runAllTestCases() }
                }
                row("Input:") {
                    cell(inputArea).resizableColumn().align(Align.FILL)
                }.resizableRow()
                row("Expected output:") {
                    cell(expectedArea).resizableColumn().align(Align.FILL)
                }.resizableRow()
                row("Run output:") {
                    cell(runOutputArea).resizableColumn().align(Align.FILL)
                }.resizableRow()
            }
        }

    // ------------------------------------------------------------------
    // 动作:工作区
    // ------------------------------------------------------------------

    private fun refreshChallenges() {
        onPooled {
            val challenges = runBlocking { allChallenges() }
            onEdt {
                challengeComboModel.removeAllElements()
                challenges.forEach(challengeComboModel::addElement)
                if (challenges.isNotEmpty() && challengeCombo.selectedIndex < 0) {
                    challengeCombo.selectedIndex = 0
                }
                loadSelectedChallenge()
            }
        }
    }

    /** 枚举全部工作区:仓库无全量接口,按已知平台聚合(题量有限,顺序扫可接受)。 */
    private suspend fun allChallenges(): List<Challenge> {
        val m = manager()
        return KNOWN_PLATFORMS
            .flatMap { platform ->
                m.problemRepository.search(
                    com.codequest.core.model
                        .PlatformId(platform),
                    "",
                )
            }.flatMap { problem -> m.challengeRepository.findByProblem(problem.id) }
            .sortedBy { it.name }
    }

    private fun showNewChallengeDialog() {
        NewChallengeDialog(project) { created ->
            openInEditor(created.entryFile)
            refreshChallenges()
        }.show()
    }

    private fun openSelectedEntry() {
        val challenge = selectedChallenge() ?: return
        onPooled {
            val created = runBlocking { loadCreated(challenge) }
            if (created != null) {
                openInEditor(created.entryFile)
            } else {
                onEdt { Messages.showWarningDialog(project, "No language configuration found for this challenge.", "Open Entry") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 动作:多解法
    // ------------------------------------------------------------------

    private fun createNewSolution() {
        val challenge = selectedChallenge() ?: return
        val name =
            Messages.showInputDialog("Solution name:", "New Solution", null)
                ?: return
        if (name.isBlank()) return
        onPooled {
            val created = runBlocking { loadCreated(challenge) } ?: error("challenge has no language configuration")
            val solution = runBlocking { service().createSolution(created, name.trim(), "") }
            openInEditor(created.workspaceRoot.resolve(solution.filePath))
            reloadSolutions(challenge)
        }
    }

    private fun openSelectedSolution() {
        val challenge = selectedChallenge() ?: return
        val solution = solutionList.selectedValue ?: return
        onPooled {
            val created = runBlocking { loadCreated(challenge) } ?: return@onPooled
            openInEditor(created.workspaceRoot.resolve(solution.filePath))
        }
    }

    private fun saveSelectedNote() {
        val challenge = selectedChallenge() ?: return
        val solution = solutionList.selectedValue ?: return
        onPooled {
            runBlocking {
                manager().solutionRepository.save(solution.copy(noteMd = noteArea.text))
            }
            reloadSolutions(challenge)
        }
    }

    private fun deleteSelectedSolution() {
        val solution = solutionList.selectedValue ?: return
        val confirmed =
            Messages.showYesNoDialog("Delete solution '${solution.name}'?", "Delete Solution", Messages.getQuestionIcon())
        if (confirmed != Messages.YES) return
        onPooled {
            runBlocking { manager().solutionRepository.delete(solution.id) }
            reloadSolutions(selectedChallenge())
        }
    }

    // ------------------------------------------------------------------
    // 动作:测试用例
    // ------------------------------------------------------------------

    private fun clearTestCaseForm() {
        testCaseList.clearSelection()
        inputArea.text = ""
        expectedArea.text = ""
    }

    private fun saveTestCaseForm() {
        val challenge = selectedChallenge() ?: return
        val existing = testCaseList.selectedValue
        onPooled {
            runBlocking {
                service().saveTestCase(challenge.id, inputArea.text, expectedArea.text, existingId = existing?.id)
            }
            reloadTestCases(challenge)
        }
    }

    private fun deleteSelectedTestCase() {
        val case = testCaseList.selectedValue ?: return
        if (Messages.showYesNoDialog("Delete this test case?", "Delete Test Case", Messages.getQuestionIcon()) != Messages.YES) {
            return
        }
        onPooled {
            runBlocking { service().deleteTestCase(case.id) }
            onEdt { clearTestCaseForm() }
            reloadTestCases(selectedChallenge())
        }
    }

    private fun importTestCases() {
        val challenge = selectedChallenge() ?: return
        val dialog = ImportTestCasesDialog(project)
        if (!dialog.showAndGet()) return
        val cases = dialog.parsedCases
        if (cases.isEmpty()) return
        onPooled {
            runBlocking {
                cases.forEach { service().saveTestCase(challenge.id, it.input, it.expected) }
            }
            reloadTestCases(challenge)
        }
    }

    /** 本地运行:逐用例执行入口文件,与 expected 宽松比对。 */
    private fun runAllTestCases() {
        val challenge = selectedChallenge() ?: return
        appendRunOutput("Running test cases for '${challenge.name}'...\n")
        onPooled {
            val created = runBlocking { loadCreated(challenge) }
            if (created == null) {
                onEdt { appendRunOutput("No language configuration found.\n") }
                return@onPooled
            }
            val languageId = created.language.language
            val runner = runnerRegistry.forLanguage(languageId)
            if (runner == null) {
                onEdt { appendRunOutput("Local run is not supported for language '$languageId' yet.\n") }
                return@onPooled
            }
            if (!runner.isAvailable()) {
                onEdt { appendRunOutput("Runner for '$languageId' unavailable: ${runner.unavailableReason()}\n") }
                return@onPooled
            }
            val cases = runBlocking { manager().testCaseRepository.findByChallenge(challenge.id) }
            if (cases.isEmpty()) {
                onEdt { appendRunOutput("No test cases to run.\n") }
                return@onPooled
            }
            var passed = 0
            cases.forEachIndexed { index, testCase ->
                val result = runner.run(created.entryFile, testCase.input, timeoutSeconds = RUN_TIMEOUT_SECONDS)
                val ok = result.succeeded && OutputComparator.matches(testCase.expected, result.stdout)
                if (ok) passed++
                onEdt {
                    appendRunOutput(
                        buildString {
                            append("Case ")
                                .append(index + 1)
                                .append(": ")
                                .append(if (ok) "PASSED" else "FAILED")
                                .append('\n')
                            if (!ok) {
                                if (!result.succeeded) {
                                    append("  program failed (exit=").append(result.exitCode)
                                    if (result.timedOut) append(", timed out")
                                    append(")\n  stderr: ").append(result.stderr.take(STDERR_PREVIEW_CHARS)).append('\n')
                                } else {
                                    append("  expected: ").append(testCase.expected.take(PREVIEW_CHARS)).append('\n')
                                    append("  actual:   ").append(result.stdout.take(PREVIEW_CHARS)).append('\n')
                                }
                            }
                        },
                    )
                }
            }
            onEdt { appendRunOutput("Summary: $passed/${cases.size} passed.\n\n") }
        }
    }

    private fun appendRunOutput(text: String) {
        runOutputArea.append(text)
        runOutputArea.caretPosition = runOutputArea.document.length
    }

    // ------------------------------------------------------------------
    // 公共支撑
    // ------------------------------------------------------------------

    private fun selectedChallenge(): Challenge? = challengeCombo.selectedItem as? Challenge

    private fun loadSelectedChallenge() {
        val challenge = selectedChallenge()
        if (challenge == null) {
            solutionModel.replaceAll(emptyList())
            testCaseModel.replaceAll(emptyList())
            return
        }
        reloadSolutions(challenge)
        reloadTestCases(challenge)
    }

    private fun reloadSolutions(challenge: Challenge?) {
        if (challenge == null) return
        onPooled {
            val solutions = runBlocking { manager().solutionRepository.findByChallenge(challenge.id) }
            onEdt {
                solutionModel.replaceAll(solutions)
                if (solutions.isNotEmpty()) solutionList.selectedIndex = 0 else noteArea.text = ""
            }
        }
    }

    private fun reloadTestCases(challenge: Challenge?) {
        if (challenge == null) return
        onPooled {
            val cases = runBlocking { manager().testCaseRepository.findByChallenge(challenge.id) }
            onEdt { testCaseModel.replaceAll(cases) }
        }
    }

    /** 从 DB 重建 [CreatedChallenge](多解法/本地运行共用)。 */
    private suspend fun loadCreated(challenge: Challenge): CreatedChallenge? {
        val m = manager()
        val language = m.challengeRepository.findLanguages(challenge.id).firstOrNull() ?: return null
        val problem = m.problemRepository.findById(challenge.problemId) ?: return null
        val root = ChallengeWorkspaceLocations.workspaceRoot(project, challenge)
        return CreatedChallenge(challenge, language, problem, root, root.resolve(language.entryFilePath))
    }

    private fun service(): ChallengeWorkspaceService =
        ChallengeWorkspaceService(
            challengeRepository = manager().challengeRepository,
            solutionRepository = manager().solutionRepository,
            testCaseRepository = manager().testCaseRepository,
            workspaceRoot = { ChallengeWorkspaceLocations.workspaceRoot(project, it) },
        )

    private fun manager(): DatabaseManager = project.getService(DatabaseManager::class.java)

    @Suppress("DEPRECATION")
    private fun <T> textRenderer(text: (T) -> String) = SimpleListCellRenderer.create("", text)

    private fun openInEditor(file: Path) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toFile().absolutePath)
        if (virtualFile != null) {
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        } else {
            logger.warning("Workspace file not found on disk: $file")
        }
    }

    private fun onPooled(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread<Any> {
            try {
                block()
            } catch (error: Exception) {
                logger.severe("Challenge workspace action failed: $error")
                onEdt {
                    Messages.showErrorDialog(project, error.message ?: error.toString(), "CodeQuest Challenge")
                }
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }

    private companion object {
        const val RUN_TIMEOUT_SECONDS = 60L
        const val NOTE_AREA_ROWS = 4
        const val CASE_AREA_ROWS = 6
        const val RUN_OUTPUT_ROWS = 8
        const val PREVIEW_CHARS = 300
        const val STDERR_PREVIEW_CHARS = 800

        /** 已知平台(与 NewChallengeDialog 平台列表一致)。 */
        val KNOWN_PLATFORMS =
            listOf("leetcode", "leetcodecn", "codeforces", "atcoder", "luogu", "hackerrank")
    }
}
