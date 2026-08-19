package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.util.logging.Logger

/**
 * 题面工具窗口(WP11 壳,WP16 挂载 `/problem-view` 页面;WP20 接入导航器:
 * 消费 [ProblemViewNavigator] 的 pending URL 支持直达指定题目;WP26 注册
 * `nav:openUrl` 外链出口供题解列表使用)。
 */
class ProblemViewToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    private companion object {
        private val LOGGER = Logger.getLogger(ProblemViewToolWindowFactory::class.java.name)
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val navigator = project.getService(ProblemViewNavigator::class.java)
        val defaultUrl =
            project
                .getService(CodeQuestHttpServerService::class.java)
                .baseUrl
                ?.let { "$it/problem-view/" }
        val component =
            JcefToolWindowSupport.createContent(
                disposableName = "codequest.problem-view",
                fallbackText =
                    "<b>Problem View</b><br>JCEF is not available in this IDE; " +
                        "enable the 'Web Browser (JCEF)' plugin to render problem statements.",
                // 首次创建前若有待打开题目(工具窗口从未显示过用户就点了列表行),直达该 URL
                url = navigator.consumePendingUrl() ?: defaultUrl,
                configure = { browser, bridge ->
                    navigator.attach(browser)
                    // 1.0.x:浏览链路登录入口(auth:login,HR 题面 401 场景)
                    AuthLoginBridge.register(project, bridge)
                    // WP26:题解列表外链 → 系统浏览器(nav:openUrl 桥消息)
                    bridge.on(ExternalLinkOpener.TOPIC_NAV_OPEN_URL) { payload ->
                        val request = parseOpenUrlPayload(payload)
                        if (request == null) {
                            LOGGER.warning("dropping malformed ${ExternalLinkOpener.TOPIC_NAV_OPEN_URL} payload: $payload")
                        } else {
                            ExternalLinkOpener.open(request.url)
                        }
                    }
                },
            )
        val content = ContentFactory.getInstance().createContent(component, "", false)
        JcefToolWindowSupport.disposableOf(component)?.let { content.setDisposer(it) }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
