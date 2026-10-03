package com.codequest.ui.challenge

import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.codequest.spi.AuthCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.TestEvaluationCapable
import com.codequest.ui.submission.ChallengeEditorGate
import com.codequest.ui.submission.SubmissionNotifications
import com.codequest.ui.submission.SubmissionPlatforms
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * 「修改测试用例」浮动键(WP55):按编辑器文件定位挑战 → pooled 线程装载
 * testCaseRepository.findByChallenge → EDT 打开 [TestCasesDialog](WP54,
 * 写库与刷新由对话框自管,此处无面板需刷新,`onSaved` 缺省)。
 *
 * 1.5.0:文本/描述改构造器显式传入(PluginBundle 直读,随 locale 生效)——
 * XML `%key` 在浮动工具栏 tooltip 上曾不被解析,显示 % 原文。
 */
class EditTestCasesAction :
    AnAction(
        PluginBundle.message("action.editTestCases.text"),
        PluginBundle.message("action.editTestCases.description"),
        AllIcons.Actions.Edit,
    ) {
    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        val file: VirtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val path =
            runCatching { file.toNioPath() }.getOrNull()
                ?: return notifyNotChallengeFile(project)
        ApplicationManager.getApplication().executeOnPooledThread<Any> {
            val gate = project.getService(ChallengeEditorGate::class.java)
            val target = runBlocking { gate.targetFor(path) }
            if (target == null) {
                notifyNotChallengeFile(project)
                return@executeOnPooledThread
            }
            val cases =
                runBlocking {
                    project
                        .getService(DatabaseManager::class.java)
                        .testCaseRepository
                        .findByChallenge(target.challenge.id)
                }
            SwingUtilities.invokeLater {
                TestCasesDialog(project, target.challenge.id, cases).show()
            }
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = isChallengeEditorFile(event)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun notifyNotChallengeFile(project: Project) {
        SubmissionNotifications.notifyError(
            project,
            PluginBundle.message(TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_CHALLENGE_FILE)),
        )
    }
}

/**
 * 「提交测试」浮动键(WP55):saveDocument → 读取编辑器文本 → 委托
 * [CloudTestCoordinator](定位 → 区域提取 → 拼用例 → watchTestRun → 结果展示)。
 *
 * update 门控(BGT,仅查快照缓存):非挑战文件隐藏;平台无 TestEvaluationCapable
 * 隐藏;未登录置灰;同文件测试进行中置灰(防重入)——见 [updateCloudTestActionState]。
 *
 * 1.5.0:文本/描述改构造器显式传入(见 [EditTestCasesAction] 注释)。
 */
class RunCloudTestAction :
    AnAction(
        PluginBundle.message("action.runCloudTest.text"),
        PluginBundle.message("action.runCloudTest.description"),
        AllIcons.Actions.Execute,
    ) {
    override fun actionPerformed(event: AnActionEvent) =
        launchCloudTest(event) { coordinator, file, text -> coordinator.runTest(file, text) }

    override fun update(event: AnActionEvent) = updateCloudTestActionState(event)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * 「全示例测试」浮动键(WP74,第四键):与「提交测试」同壳,数据源不同——
 * dataInput 取题面全部示例(题面 detail 的 exampleTestcases,与 questionId 同次
 * fetchProblemDetail 换取),一次云测全量跑;结果同一右窗「测试用例」tab 展示
 * (payload mode=examples,页面标题「全部示例」区分);题面无(非空白)示例
 * 通知 testRun.error.noExamples,不发起云测。
 *
 * update 门控与「提交测试」完全一致(平台无 TestEvaluationCapable 隐藏,
 * 未登录/同文件云测进行中置灰)——见 [updateCloudTestActionState]。
 */
class RunExamplesTestAction :
    AnAction(
        PluginBundle.message("action.runExamplesTest.text"),
        PluginBundle.message("action.runExamplesTest.description"),
        AllIcons.Actions.RunAll,
    ) {
    override fun actionPerformed(event: AnActionEvent) =
        launchCloudTest(event) { coordinator, file, text -> coordinator.runExamplesTest(file, text) }

    override fun update(event: AnActionEvent) = updateCloudTestActionState(event)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * 云测系浮动键(提交测试/全示例测试)共用的发起壳:saveDocument → 读文本
 * (测试针对磁盘上的最新内容发起)→ 经 [launch] 委托协调器。
 */
internal fun launchCloudTest(
    event: AnActionEvent,
    launch: (CloudTestCoordinator, VirtualFile, String) -> Unit,
) {
    val project: Project = event.project ?: return
    val file: VirtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    val documentManager = FileDocumentManager.getInstance()
    val document = documentManager.getDocument(file)
    // 先保存再取文本:测试针对磁盘上的最新内容发起
    document?.let { documentManager.saveDocument(it) }
    val text =
        document?.text
            ?: ApplicationManager.getApplication().runReadAction(
                Computable { VfsUtil.loadText(file) },
            )
    launch(project.getService(CloudTestCoordinator::class.java), file, text)
}

/**
 * 云测系浮动键共用 update 门控(WP55「提交测试」原逻辑提取,BGT 仅查快照缓存):
 * 非挑战文件隐藏;平台无 [TestEvaluationCapable] 隐藏([cloudTestCapable]);
 * 未登录置灰;同文件云测进行中置灰(防重入,两键共用 guard)。
 */
internal fun updateCloudTestActionState(event: AnActionEvent) {
    val project = event.project
    val path = editorFilePath(event)
    if (project == null || path == null) {
        event.presentation.isEnabledAndVisible = false
        return
    }
    val gate = project.getService(ChallengeEditorGate::class.java)
    if (!gate.cachedIsChallengeFile(path)) {
        event.presentation.isEnabledAndVisible = false
        return
    }
    val platform =
        gate.cachedTargetFor(path)?.let {
            runCatching { SubmissionPlatforms.resolve(project, it.problem.platformId) }.getOrNull()
        }
    if (!cloudTestCapable(platform)) {
        // 平台无云端测试能力:该键隐藏
        event.presentation.isEnabledAndVisible = false
        return
    }
    event.presentation.isVisible = true
    val loggedIn = (platform as? AuthCapable)?.currentSession() != null || platform !is AuthCapable
    val coordinator = project.getService(CloudTestCoordinator::class.java)
    event.presentation.isEnabled = loggedIn && !coordinator.isRunning(path)
}

/** 云测键可见的能力判定(纯,WP74 抽出可单测):平台未实现 TestEvaluationCapable(含 null)均隐藏。 */
internal fun cloudTestCapable(platform: OJPlatform?): Boolean = platform is TestEvaluationCapable

/** event → 编辑器文件 nio 路径(非文件类 VirtualFile / 无路径时 null)。 */
internal fun editorFilePath(event: AnActionEvent): Path? {
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    return runCatching { file.toNioPath() }.getOrNull()
}

/** event 对应文件是否属于某挑战工作区(仅查门控快照缓存,无 IO)。 */
internal fun isChallengeEditorFile(event: AnActionEvent): Boolean {
    val project = event.project ?: return false
    val path = editorFilePath(event) ?: return false
    return project.getService(ChallengeEditorGate::class.java).cachedIsChallengeFile(path)
}
