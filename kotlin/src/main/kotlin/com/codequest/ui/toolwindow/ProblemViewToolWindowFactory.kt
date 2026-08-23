package com.codequest.ui.toolwindow

import com.codequest.infra.i18n.PluginBundle
import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.ui.challenge.CloudTestCoordinator
import com.codequest.ui.submission.SubmissionCoordinator
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.util.logging.Logger

/**
 * 题面工具窗口(WP11 壳;1.5.0 改顶部三 tab,样式对齐左窗 [CodeQuestToolWindowFactory]):
 *
 * - 「题面」= JCEF `/problem-view/`(现有逻辑:ProblemViewNavigator pending URL 直达、
 *   登录桥、一键开刷桥、`nav:openUrl` 外链出口),随内容创建即建;
 * - 「测试用例」= [LazyJcefRouteTab] 惰性 JCEF `/testcases/`(桥挂接 [CloudTestCoordinator],
 *   云端测试结果经 `testrun:result` 推送本页展示);
 * - 「提交记录」= [LazyJcefRouteTab] 惰性 JCEF `/submissions/`(桥挂接
 *   [SubmissionCoordinator],提交落库后 `submission:updated` 推送刷新);
 * - 三 tab 的 challengeId 联动与定向导航经 [ProblemViewTabNavigator]。
 */
class ProblemViewToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    /** WP51:显示名本地化(id 不动——id 改动使用户既有窗口布局失效)。 */
    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(CQIcons.toolWindow) // 1.5.0:插件自有图标
        val name = PluginBundle.message("toolwindow.displayName.problemView")
        toolWindow.stripeTitle = name
        toolWindow.title = name
    }

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val statement = statementComponent(project)
        val testCases = testCasesTab(project)
        val submissions = submissionsTab(project)

        val tabs = JBTabbedPane()
        tabs.addTab(PluginBundle.message(KEY_STATEMENT_TITLE), AllIcons.Nodes.Template, statement)
        tabs.addTab(PluginBundle.message(KEY_TEST_CASES_TITLE), AllIcons.Nodes.Template, testCases.component)
        tabs.addTab(PluginBundle.message(KEY_SUBMISSIONS_TITLE), AllIcons.Nodes.Template, submissions.component)
        tabs.addChangeListener {
            when (tabs.selectedIndex) {
                TEST_CASES_INDEX -> testCases.onTabSelected()
                SUBMISSIONS_INDEX -> submissions.onTabSelected()
            }
        }

        // challengeId 联动与定向导航(开刷/提交完成/云端测试完成);内容创建前的请求记 pending
        val navigator = project.getService(ProblemViewTabNavigator::class.java)
        val controller =
            object : ProblemViewTabNavigator.Controller {
                override fun onChallengeChanged(challengeId: String?) {
                    testCases.onChallengeChanged(challengeId)
                    submissions.onChallengeChanged(challengeId)
                }

                override fun selectTab(
                    tab: ProblemViewTabNavigator.Tab,
                    challengeId: String?,
                ) {
                    tabs.selectedIndex = tab.index
                    when (tab) {
                        ProblemViewTabNavigator.Tab.TEST_CASES -> testCases.show(challengeId)
                        ProblemViewTabNavigator.Tab.SUBMISSIONS -> submissions.show(challengeId)
                        ProblemViewTabNavigator.Tab.STATEMENT -> Unit
                    }
                }
            }
        navigator.attach(controller)
        navigator.consumePendingChallengeId()?.let(controller::onChallengeChanged)
        navigator.consumePendingSelect()?.let { (tab, challengeId) -> controller.selectTab(tab, challengeId) }

        val root =
            panel {
                row {
                    cell(tabs)
                        .resizableColumn()
                        .align(Align.FILL)
                }.resizableRow()
            }
        val content = ContentFactory.getInstance().createContent(root, "", false)
        val rootDisposable = Disposer.newDisposable("codequest.problem-view.tabs")
        Disposer.register(rootDisposable, Disposable { navigator.attach(null) })
        JcefToolWindowSupport.disposableOf(statement)?.let { Disposer.register(rootDisposable, it) }
        content.setDisposer(rootDisposable)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    // ------------------------------------------------------------------
    // 题面 tab(随内容创建;pending URL 直达 + 三个业务桥)
    // ------------------------------------------------------------------

    private fun statementComponent(project: Project): javax.swing.JComponent {
        val navigator = project.getService(ProblemViewNavigator::class.java)
        val defaultUrl =
            project
                .getService(CodeQuestHttpServerService::class.java)
                .baseUrl
                ?.let { "$it/problem-view/" }
        return JcefToolWindowSupport.createContent(
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
                // 1.0.x:一键开刷(challenge:create,题面数据全量自动带入)
                ChallengeCreateBridge.register(project, bridge)
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
    }

    // ------------------------------------------------------------------
    // 测试用例/提交记录 tab(惰性 JCEF + 桥生命周期挂接)
    // ------------------------------------------------------------------

    private fun testCasesTab(project: Project): LazyJcefRouteTab {
        val coordinator = project.getService(CloudTestCoordinator::class.java)
        return LazyJcefRouteTab(
            project = project,
            disposableName = "codequest.problem-view.testcases",
            fallbackText = fallbackText(KEY_TEST_CASES_TITLE, KEY_TEST_CASES_DESCRIPTION),
            route = ROUTE_TEST_CASES,
            configure = { _, bridge -> coordinator.attachBridge(bridge) },
            onDisposed = { bridge -> coordinator.detachBridge(bridge) },
        )
    }

    private fun submissionsTab(project: Project): LazyJcefRouteTab {
        val coordinator = project.getService(SubmissionCoordinator::class.java)
        return LazyJcefRouteTab(
            project = project,
            disposableName = "codequest.problem-view.submissions",
            fallbackText = fallbackText(KEY_SUBMISSIONS_TITLE, KEY_SUBMISSIONS_DESCRIPTION),
            route = ROUTE_SUBMISSIONS,
            configure = { _, bridge -> coordinator.attachSubmissionsBridge(bridge) },
            onDisposed = { bridge -> coordinator.detachSubmissionsBridge(bridge) },
        )
    }

    private fun fallbackText(
        titleKey: String,
        descriptionKey: String,
    ): String =
        PluginBundle.message(
            "toolwindow.panel.jcefFallback",
            PluginBundle.message(titleKey),
            PluginBundle.message(descriptionKey),
        )

    private companion object {
        private val LOGGER = Logger.getLogger(ProblemViewToolWindowFactory::class.java.name)

        const val KEY_STATEMENT_TITLE = "toolwindow.panel.statement.title"

        const val KEY_TEST_CASES_TITLE = "toolwindow.panel.testcases.title"

        const val KEY_TEST_CASES_DESCRIPTION = "toolwindow.panel.testcases.description"

        const val KEY_SUBMISSIONS_TITLE = "toolwindow.panel.submissions.title"

        const val KEY_SUBMISSIONS_DESCRIPTION = "toolwindow.panel.submissions.description"

        const val TEST_CASES_INDEX = 1

        const val SUBMISSIONS_INDEX = 2

        const val ROUTE_TEST_CASES = "testcases"

        const val ROUTE_SUBMISSIONS = "submissions"
    }
}
