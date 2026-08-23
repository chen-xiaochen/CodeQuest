package com.codequest.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 右窗(题面工具窗)三 tab 导航器(1.5.0):底部挑战工具窗删除后,测试用例/提交记录
 * 迁入本窗,本服务承接原 ChallengeToolWindowNavigator 的职责并扩展:
 *
 * - [challengeCreated]:题面页 `challenge:create` 成功 → 测试用例/提交记录 tab 的 URL
 *   更新为带 challengeId(不切 tab,用户停在题面页);
 * - [showSubmissions]:提交完成 → 激活右窗 + 选中提交记录 tab + challengeId 过滤;
 * - [showTestCases]:云端测试完成 → 激活右窗 + 选中测试用例 tab(结果经桥推送展示)。
 *
 * 时序与 [ProblemViewNavigator] 同款:工具窗内容未创建时 `show()` 触发工厂注册
 * 控制器,控制器已就绪则直接调用;尚未注册(内容惰性创建)记 pending,由工厂
 * 创建内容后消费——避免「先空转后二次导航」。
 */
@Service(Level.PROJECT)
class ProblemViewTabNavigator(
    private val project: Project,
) {
    /** tab 索引常量(与 [ProblemViewToolWindowFactory] 的三 tab 顺序一致)。 */
    enum class Tab(
        val index: Int,
    ) {
        STATEMENT(0),
        TEST_CASES(1),
        SUBMISSIONS(2),
    }

    /** 工厂创建内容时注册的 tab 控制器(内容销毁置 null)。 */
    interface Controller {
        /** 当前挑战变化:已建 tab 刷新 URL,未建 tab 记住 challengeId 供惰性创建。 */
        fun onChallengeChanged(challengeId: String?)

        /** 选中指定 tab 并按 challengeId 定向(创建/刷新按需)。 */
        fun selectTab(
            tab: Tab,
            challengeId: String?,
        )
    }

    @Volatile
    private var controller: Controller? = null

    @Volatile
    private var pendingChallengeId: String? = null

    @Volatile
    private var pendingSelect: Pair<Tab, String?>? = null

    /** 工厂创建内容时注册控制器(随内容销毁置 null)。 */
    fun attach(controller: Controller?) {
        this.controller = controller
    }

    /** 工厂注册控制器后取走待生效的挑战联动(无则 null)。 */
    fun consumePendingChallengeId(): String? =
        pendingChallengeId.also {
            pendingChallengeId = null
        }

    /** 工厂注册控制器后取走待生效的 tab 定向请求(无则 null)。 */
    fun consumePendingSelect(): Pair<Tab, String?>? =
        pendingSelect.also {
            pendingSelect = null
        }

    /** 开刷成功:更新测试用例/提交记录 tab 的 challengeId 联动(不切 tab;任意线程可调)。 */
    fun challengeCreated(challengeId: String) {
        ApplicationManager.getApplication().invokeLater {
            val handler = controller
            if (handler == null) {
                pendingChallengeId = challengeId
            } else {
                pendingChallengeId = null
                handler.onChallengeChanged(challengeId)
            }
        }
    }

    /** 激活右窗并选中指定 tab(challengeId 定向;任意线程可调)。 */
    private fun showTab(
        tab: Tab,
        challengeId: String?,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow =
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                    ?: return@invokeLater
            toolWindow.show(null)
            val handler = controller
            if (handler == null) {
                pendingChallengeId = challengeId ?: pendingChallengeId
                pendingSelect = tab to challengeId
            } else {
                pendingSelect = null
                handler.selectTab(tab, challengeId)
            }
        }
    }

    /** 提交完成导航:激活右窗 + 选中提交记录 tab + challengeId 过滤。 */
    fun showSubmissions(challengeId: String?) = showTab(Tab.SUBMISSIONS, challengeId)

    /** 云端测试完成导航:激活右窗 + 选中测试用例 tab(结果展示页)。 */
    fun showTestCases() = showTab(Tab.TEST_CASES, null)

    companion object {
        /** id 一律不动(见 WP51):改 id 会使既有用户窗口布局失效。 */
        const val TOOL_WINDOW_ID = "CodeQuest Problem View"
    }
}
