package com.codequest.ui.challenge

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.core.workspace.TemplateAvailability
import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.codequest.infra.settings.CodeQuestSettings
import com.codequest.ui.settings.CodeQuestSettingsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * 「从题目创建工作区」对话框(WP16;WP47 语言门控)。
 *
 * 填写题目信息(平台/remoteId/标题/难度/链接)、语言与起始代码;OK 后在后台线程
 * 落库 problem + challenge,写入入口文件,并把结果回调给面板(打开编辑器、刷新列表)。
 *
 * WP47:语言下拉只列**该平台风格下已配置模版**的语言([TemplateAvailability]),
 * 默认选中 Java;全无可用语言时禁用创建并给出「打开设置」引导。
 */
class NewChallengeDialog(
    private val project: Project,
    private val onCreated: (ChallengeWorkspaceService.CreatedChallenge) -> Unit,
) : DialogWrapper(project) {
    private companion object {
        const val CODE_AREA_ROWS = 6
    }

    private val platforms = arrayOf("leetcode", "leetcodecn", "codeforces", "atcoder", "luogu", "hackerrank")

    private var platform: String = platforms.first()
    private val remoteIdField = JTextField()
    private val titleField = JTextField()
    private val difficultyField = JTextField("easy")
    private val urlField = JTextField()
    private val languageCombo = ComboBox<WorkspaceLanguage>()
    private val noTemplateHint =
        JBLabel(PluginBundle.message("challenge.new.noTemplateHint"))
    private val openSettingsButton =
        JButton(PluginBundle.message("challenge.new.openSettings")).apply {
            addActionListener {
                // 模态设置页;返回后重读设置刷新语言(配置了模版即可继续创建)
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeQuestSettingsConfigurable::class.java)
                refreshLanguageOptions()
            }
        }
    private val codeArea =
        JBTextArea().apply {
            rows = CODE_AREA_ROWS
        }

    init {
        init()
        title = PluginBundle.message("challenge.dialog.new.title")
        refreshLanguageOptions()
        // 起始代码桩跟随默认语言(仅构造时;之后切换语言不覆盖已编辑内容)
        (languageCombo.selectedItem as? WorkspaceLanguage)?.let { codeArea.text = ChallengeTemplates.defaultCode(it.id) }
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row(PluginBundle.message("challenge.dialog.new.platform")) {
                comboBox(platforms.toList())
                    .bindItem({ platform }, { platform = it ?: platforms.first() })
                    .apply { component.addActionListener { refreshLanguageOptions() } }
            }
            row(PluginBundle.message("challenge.dialog.new.remoteId")) {
                cell(remoteIdField).resizableColumn().align(Align.FILL)
            }
            row(PluginBundle.message("challenge.dialog.new.problemTitle")) {
                cell(titleField).resizableColumn().align(Align.FILL)
            }
            row(PluginBundle.message("challenge.dialog.new.difficulty")) {
                cell(difficultyField).resizableColumn().align(Align.FILL)
            }
            row(PluginBundle.message("challenge.dialog.new.url")) {
                cell(urlField).resizableColumn().align(Align.FILL)
            }
            row(PluginBundle.message("challenge.dialog.new.language")) {
                cell(languageCombo)
            }
            row {
                cell(noTemplateHint)
                cell(openSettingsButton)
            }
            row(PluginBundle.message("challenge.dialog.new.startingCode")) {
                cell(codeArea).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }

    /** 当前选中的语言;空列表时为 null(创建被禁用,防双重触发)。 */
    private fun selectedLanguage(): WorkspaceLanguage? = languageCombo.selectedItem as? WorkspaceLanguage

    /**
     * (WP47) 平台→语言门控:下拉仅列该平台风格([templateStyleOf])下已配置模版的
     * 语言,默认 Java 优先;全无可用时显示提示 + 「打开设置」并禁用创建。
     */
    private fun refreshLanguageOptions() {
        val usable =
            TemplateAvailability.usableLanguages(
                templateStyleOf(PlatformId(platform)),
                CodeQuestSettings.getInstance(project).state.templates,
            )
        languageCombo.removeAllItems()
        usable.forEach(languageCombo::addItem)
        languageCombo.selectedItem = TemplateAvailability.defaultLanguage(usable)
        val blocked = usable.isEmpty()
        noTemplateHint.isVisible = blocked
        openSettingsButton.isVisible = blocked
        setOKActionEnabled(!blocked)
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isNullOrBlank()) {
            return ValidationInfo(PluginBundle.message("challenge.dialog.new.titleRequired"), titleField)
        }
        if (remoteIdField.text.isNullOrBlank()) {
            return ValidationInfo(PluginBundle.message("challenge.dialog.new.remoteIdRequired"), remoteIdField)
        }
        // WP47:无可用语言禁创建(OK 已禁用,此处防程序化触发)
        selectedLanguage() ?: return ValidationInfo(PluginBundle.message("challenge.new.noTemplateHint"))
        return null
    }

    override fun doOKAction() {
        val language = selectedLanguage() ?: return
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
                            PluginBundle.message("challenge.dialog.new.createFailedTitle"),
                        )
                        okAction.isEnabled = true
                    }
            }
        }
    }
}
