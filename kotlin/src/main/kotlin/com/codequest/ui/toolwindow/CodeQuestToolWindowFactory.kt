package com.codequest.ui.toolwindow

import com.codequest.infra.i18n.PluginBundle
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
 * CodeQuest 主工具窗口壳(WP11 建立,WP16 挂载前端页面 URL;WP51 面板 4→3 + 全文案 bundle 化)。
 *
 * Kotlin UI DSL + JBTabbedPane 三面板(题库/竞赛/统计);每面板一个 JCEF 浏览器,加载
 * `http://127.0.0.1:<port>/<page>/`(端口取自 [CodeQuestHttpServerService],
 * 服务随项目启动)。JCEF 不可用时该面板降级为提示面板。
 * Search 不再单列 tab(WP51 起并入题库页「本地搜索」子视图,`/search/` 路由仍保留)。
 */
class CodeQuestToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    /** WP51:显示名本地化(id 不动——id 改动使用户既有窗口布局失效)。 */
    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(CQIcons.toolWindow) // 1.5.0:插件自有图标
        val name = PluginBundle.message("toolwindow.displayName.main")
        toolWindow.stripeTitle = name
        toolWindow.title = name
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val tabs = JBTabbedPane()
        PANELS.forEach { spec ->
            tabs.addTab(PluginBundle.message(spec.titleKey), spec.icon, pageComponent(project, spec))
        }

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
                PluginBundle.message(
                    "toolwindow.panel.jcefFallback",
                    PluginBundle.message(spec.titleKey),
                    PluginBundle.message(spec.descriptionKey),
                ),
            url = pageUrl(project, spec.route),
            // WP20:problems 面板注册「点击行 → 打开题面工具窗口」的导航桥 handler;
            // 1.0.x:浏览链路登录入口(auth:login,如 leetcode.com 列表 401 场景)
            configure =
                if (spec.route == PROBLEMS_ROUTE) {
                    { _, bridge ->
                        attachOpenProblemNavigation(project, bridge)
                        AuthLoginBridge.register(project, bridge)
                    }
                } else {
                    null
                },
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

    /** 面板描述(WP51:三面板,文案键指向 PluginBundle,中文标签题库/竞赛/统计)。 */
    private data class PanelSpec(
        val titleKey: String,
        val descriptionKey: String,
        val route: String,
        val icon: javax.swing.Icon,
    )

    private companion object {
        private val LOGGER = Logger.getLogger(CodeQuestToolWindowFactory::class.java.name)

        private const val PROBLEMS_ROUTE = "problems"

        val PANELS =
            listOf(
                PanelSpec(
                    "toolwindow.panel.problems.title",
                    "toolwindow.panel.problems.description",
                    "problems",
                    AllIcons.Nodes.Template,
                ),
                PanelSpec(
                    "toolwindow.panel.contest.title",
                    "toolwindow.panel.contest.description",
                    "contest",
                    AllIcons.Nodes.Template,
                ),
                PanelSpec(
                    "toolwindow.panel.stats.title",
                    "toolwindow.panel.stats.description",
                    "stats",
                    AllIcons.Nodes.Template,
                ),
            )
    }
}
