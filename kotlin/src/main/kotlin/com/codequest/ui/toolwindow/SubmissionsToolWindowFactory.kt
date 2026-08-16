package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.submission.SubmissionCoordinator
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 提交结果工具窗口(WP11 壳,WP16 挂载 `/submissions` 页面)。
 *
 * WP21:页面数据走 `GET /api/submissions`;创建内容时把桥挂接到
 * [SubmissionCoordinator],提交完成后 Kotlin 侧经 `submission:updated`
 * 桥消息推送页面刷新(内容销毁时解挂)。
 */
class SubmissionsToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val coordinator = project.getService(SubmissionCoordinator::class.java)
        var attachedBridge: CQBridge? = null
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
                configure = { _, bridge ->
                    attachedBridge = bridge
                    coordinator.attachSubmissionsBridge(bridge)
                },
            )
        // configure 在 createContent 内同步执行,返回时 bridge 引用已就绪;随内容销毁解挂
        attachedBridge?.let { bridge ->
            JcefToolWindowSupport.disposableOf(component)?.let { disposable ->
                Disposer.register(
                    disposable,
                    Disposable { coordinator.detachSubmissionsBridge(bridge) },
                )
            }
        }
        val content = ContentFactory.getInstance().createContent(component, "", false)
        JcefToolWindowSupport.disposableOf(component)?.let { content.setDisposer(it) }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
