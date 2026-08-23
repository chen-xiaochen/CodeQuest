package com.codequest.ui.submission

import com.codequest.infra.i18n.PluginBundle
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

/**
 * 「Submit to OJ」编辑器 action(WP21;WP55 并入编辑器浮动工具栏三键之「提交」):
 *
 * 当前编辑器文件属于某 challenge 工作区(按路径定位 challenge 与 ChallengeLanguage)→
 * 保存文档(WP55:浮动键语义,提交前落盘)→ 读取编辑器最新内容(含未保存修改)→
 * 委托 [SubmissionCoordinator]
 * (区域标记提取 → 平台 SPI submit → 轮询 → 落库 → 通知 → 前端刷新推送)。
 *
 * EDT 只做文件/文本读取(读操作),网络与落库全部在后台线程;重复触发由协调器幂等门拦截。
 */
class SubmitToOjAction :
    AnAction(
        PluginBundle.message("action.submitToOj.text"),
        PluginBundle.message("action.submitToOj.description"),
        AllIcons.Actions.Upload,
    ) {
    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        val file: VirtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file)
        document?.let { documentManager.saveDocument(it) }
        val text =
            document?.text
                ?: ApplicationManager.getApplication().runReadAction(
                    Computable { VfsUtil.loadText(file) },
                )

        project.getService(SubmissionCoordinator::class.java).submitFile(file, text)
    }

    override fun update(event: AnActionEvent) {
        val inWorkspaceFile = event.project != null && event.getData(CommonDataKeys.VIRTUAL_FILE) != null
        event.presentation.isEnabledAndVisible = inWorkspaceFile
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
