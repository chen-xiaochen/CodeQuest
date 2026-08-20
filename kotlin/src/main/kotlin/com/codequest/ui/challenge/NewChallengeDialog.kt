package com.codequest.ui.challenge

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * 「从题目创建工作区」对话框(WP16)。
 *
 * 填写题目信息(平台/remoteId/标题/难度/链接)、语言与起始代码;OK 后在后台线程
 * 落库 problem + challenge,写入入口文件,并把结果回调给面板(打开编辑器、刷新列表)。
 */
class NewChallengeDialog(
    private val project: Project,
    private val onCreated: (ChallengeWorkspaceService.CreatedChallenge) -> Unit,
) : DialogWrapper(project) {
    private companion object {
        const val CODE_AREA_ROWS = 6
    }

    private val platforms = arrayOf("leetcode", "leetcodecn", "codeforces", "atcoder", "luogu", "hackerrank")
    private val languages = WorkspaceLanguage.ALL

    private var platform: String = platforms.first()
    private var language: WorkspaceLanguage = languages.first()
    private val remoteIdField = JTextField()
    private val titleField = JTextField()
    private val difficultyField = JTextField("easy")
    private val urlField = JTextField()
    private val codeArea =
        JBTextArea().apply {
            rows = CODE_AREA_ROWS
            text = ChallengeTemplates.defaultCode(languages.first().id)
        }

    init {
        init()
        title = "Create Challenge Workspace"
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Platform:") {
                comboBox(platforms.toList())
                    .bindItem({ platform }, { platform = it ?: platforms.first() })
            }
            row("Problem ID / slug:") {
                cell(remoteIdField).resizableColumn().align(Align.FILL)
            }
            row("Title:") {
                cell(titleField).resizableColumn().align(Align.FILL)
            }
            row("Difficulty:") {
                cell(difficultyField).resizableColumn().align(Align.FILL)
            }
            row("URL:") {
                cell(urlField).resizableColumn().align(Align.FILL)
            }
            row("Language:") {
                comboBox(languages)
                    .bindItem({ language }, { language = it ?: languages.first() })
            }
            row("Starting code:") {
                cell(codeArea).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isNullOrBlank()) return ValidationInfo("Title must not be empty", titleField)
        if (remoteIdField.text.isNullOrBlank()) return ValidationInfo("Problem ID must not be empty", remoteIdField)
        return null
    }

    override fun doOKAction() {
        // 后台线程做 DB + 文件 IO,成功后回 EDT 关窗并回调
        okAction.isEnabled = false
        val dialog = this
        ApplicationManager.getApplication().executeOnPooledThread<Any> {
            val result =
                runCatching {
                    val manager = project.getService(DatabaseManager::class.java)
                    val problem =
                        Problem(
                            id = ProblemId(UUID.randomUUID().toString()),
                            platformId = PlatformId(platform),
                            remoteId = remoteIdField.text.trim(),
                            title = titleField.text.trim(),
                            difficulty = Difficulty(difficultyField.text.trim().ifEmpty { "unknown" }),
                            url = urlField.text.trim(),
                            tags = emptyList(),
                            fetchedAt = Instant.now(),
                        )
                    val service =
                        ChallengeWorkspaceService(
                            challengeRepository = manager.challengeRepository,
                            solutionRepository = manager.solutionRepository,
                            testCaseRepository = manager.testCaseRepository,
                            workspaceRoot = { ChallengeWorkspaceLocations.workspaceRoot(project, it) },
                        )
                    runBlocking {
                        manager.problemRepository.save(problem)
                        // WP39:创建前读设置,自定义模板覆盖内置(无覆盖传 null)
                        createChallengeWithTemplate(
                            service = service,
                            problem = problem,
                            languageId = language.id,
                            settings = CodeQuestSettings.getInstance(project),
                            code = codeArea.text,
                        )
                    }
                }
            SwingUtilities.invokeLater {
                result
                    .onSuccess { created ->
                        dialog.close(OK_EXIT_CODE)
                        onCreated(created)
                    }.onFailure { error ->
                        com.intellij.openapi.ui.Messages.showErrorDialog(
                            project,
                            error.message ?: error.toString(),
                            "Create Challenge Failed",
                        )
                        okAction.isEnabled = true
                    }
            }
        }
    }
}
