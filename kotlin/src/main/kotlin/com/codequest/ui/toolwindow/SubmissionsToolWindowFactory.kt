package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 提交结果工具窗口(WP11 壳,WP16 挂载 `/submissions` 页面展示提交结果与历史)。
 */
class SubmissionsToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val component =
            JcefToolWindowSupport.createContent(
                disposableName = "codequest.submissions",
                fallbackText =
                    "<b>Submissions</b><br>JCEF is not available in this IDE; " +
                        "enable the 'Web Browser (JCEF)' plugin to show submission results.",
                url =
                    project
                        .getService(CodeQuestHttpServerService::class.java)
                        .baseUrl
                        ?.let { "$it/submissions/" },
            )
        val content = ContentFactory.getInstance().createContent(component, "", false)
        JcefToolWindowSupport.disposableOf(component)?.let { content.setDisposer(it) }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
