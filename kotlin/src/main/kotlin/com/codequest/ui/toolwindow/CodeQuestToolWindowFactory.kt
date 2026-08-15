package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.ui.jcef.CQBridge
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.util.logging.Logger

/**
 * CodeQuest 主工具窗口壳(WP11 建立,WP16 挂载前端页面 URL)。
 *
 * Kotlin UI DSL + JBTabbedPane 四面板;每面板一个 JCEF 浏览器,加载
 * `http://127.0.0.1:<port>/<page>/`(端口取自 [CodeQuestHttpServerService],
 * 服务随项目启动)。JCEF 不可用时该面板降级为提示面板。
 */
class CodeQuestToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val tabs = JBTabbedPane()
        PANELS.forEach { spec -> tabs.addTab(spec.title, spec.icon, pageComponent(project, spec)) }

        val root =
            panel {
                row {
                    cell(tabs)
                        .resizableColumn()
                        .align(Align.FILL)
                }.resizableRow()
            }
        val content = ContentFactory.getInstance().createContent(root, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    private fun pageComponent(
        project: Project,
        spec: PanelSpec,
    ): java.awt.Component =
        JcefToolWindowSupport.createContent(
            disposableName = "codequest.${spec.route}",
            fallbackText =
                "<b>${spec.title}</b><br>${spec.description}<br><br>" +
                    "This page requires JCEF; enable the 'Web Browser (JCEF)' plugin to render it.",
            url = pageUrl(project, spec.route),
            // WP20:problems 面板注册「点击行 → 打开题面工具窗口」的导航桥 handler
            configure = if (spec.route == PROBLEMS_ROUTE) { _, bridge -> attachOpenProblemNavigation(project, bridge) } else null,
        )

    /** `nav:openProblem {platformId, remoteId}` → [ProblemViewNavigator.openProblem]。 */
    private fun attachOpenProblemNavigation(
        project: Project,
        bridge: CQBridge,
    ) {
        val navigator = project.getService(ProblemViewNavigator::class.java)
        bridge.on(ProblemViewNavigator.TOPIC_NAV_OPEN_PROBLEM) { payload ->
            val request = parseOpenProblemPayload(payload)
            if (request == null) {
                LOGGER.warning("dropping malformed ${ProblemViewNavigator.TOPIC_NAV_OPEN_PROBLEM} payload: $payload")
            } else {
                navigator.openProblem(request.platformId, request.remoteId)
            }
        }
    }

    /** 服务基地址 + 路由;服务未就绪(理论上不会,随项目启动)时回退空白页。 */
    internal fun pageUrl(
        project: Project,
        route: String,
    ): String? = project.getService(CodeQuestHttpServerService::class.java).baseUrl?.let { "$it/$route/" }

    /** 面板描述(与 spec §4 的左侧侧栏四面板一一对应)。 */
    private data class PanelSpec(
        val title: String,
        val description: String,
        val route: String,
        val icon: javax.swing.Icon,
    )

    private companion object {
        private val LOGGER = Logger.getLogger(CodeQuestToolWindowFactory::class.java.name)

        private const val PROBLEMS_ROUTE = "problems"

        val PANELS =
            listOf(
                PanelSpec(
                    "Problems",
                    "Problem list with platform filters, keywords and tags.",
                    "problems",
                    AllIcons.Nodes.Template,
                ),
                PanelSpec(
                    "Contest",
                    "Contests (CodeForces) with details and reminders.",
                    "contest",
                    AllIcons.Nodes.Template,
                ),
                PanelSpec(
                    "Statistics",
                    "Solving statistics with heatmap and trends.",
                    "stats",
                    AllIcons.Nodes.Template,
                ),
                PanelSpec(
                    "Search",
                    "Cross-problem full-text search (FTS) with tags.",
                    "search",
                    AllIcons.Nodes.Template,
                ),
            )
    }
}
