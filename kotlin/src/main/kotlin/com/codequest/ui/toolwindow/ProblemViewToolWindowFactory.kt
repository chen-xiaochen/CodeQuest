package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 题面工具窗口(WP11 壳,WP16 挂载 `/problem-view` 页面渲染题面与题解)。
 */
class ProblemViewToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val component =
            JcefToolWindowSupport.createContent(
                disposableName = "codequest.problem-view",
                fallbackText =
                    "<b>Problem View</b><br>JCEF is not available in this IDE; " +
                        "enable the 'Web Browser (JCEF)' plugin to render problem statements.",
                url =
                    project
                        .getService(CodeQuestHttpServerService::class.java)
                        .baseUrl
                        ?.let { "$it/problem-view/" },
            )
        val content = ContentFactory.getInstance().createContent(component, "", false)
        JcefToolWindowSupport.disposableOf(component)?.let { content.setDisposer(it) }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
