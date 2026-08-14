package com.codequest.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel

/**
 * CodeQuest 主工具窗口壳(WP11,左侧)。
 *
 * Kotlin UI DSL 实现(无 GUI Designer .form):一个可切换面板容器(JBTabbedPane),
 * 预留 Problems / Contest / Statistics / Search 四个面板位;各面板内容区将由
 * WP12 的 webview JCEF 页面挂载(见 spec §4 ToolWindow 布局)。
 */
class CodeQuestToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val tabs = JBTabbedPane()
        PANELS.forEach { spec -> tabs.addTab(spec.title, placeholderPanel(spec)) }

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

    private fun placeholderPanel(spec: PanelSpec) =
        panel {
            row {
                icon(spec.icon)
            }
            row {
                text(
                    "<b>${spec.title}</b><br>" +
                        "${spec.description}<br><br>" +
                        "Web content (webview/${spec.route}) mounts here in WP12.",
                )
            }
        }

    /** 面板占位描述(与 spec §4 的左侧侧栏四面板一一对应)。 */
    private data class PanelSpec(
        val title: String,
        val description: String,
        val route: String,
        val icon: javax.swing.Icon,
    )

    private companion object {
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
