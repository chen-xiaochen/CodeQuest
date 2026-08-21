package com.codequest.ui.challenge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 挑战工具窗口导航器(WP51):提交完成后把焦点带到挑战工具窗口的「提交记录」tab。
 *
 * 原提交记录为独立工具窗(WP11/WP21),WP51 并入挑战窗;[com.codequest.ui.submission.SubmissionCoordinator]
 * 提交落库后调用 [showSubmissions](challengeId 定向过滤透传给提交记录 tab 的页面 URL)。
 *
 * 时序与 [com.codequest.ui.toolwindow.ProblemViewNavigator] 同款:工具窗内容未创建时
 * `show()` 触发工厂注册选中回调,回调已就绪则直接调用;尚未注册(内容惰性创建)
 * 记 pending challengeId,由工厂创建内容后消费——避免「先空转后二次导航」。
 */
@Service(Level.PROJECT)
class ChallengeToolWindowNavigator(
    private val project: Project,
) {
    /** 选中「提交记录」tab 并加载指定 challengeId 过滤视图;参数 null = 不带过滤。 */
    @Volatile
    private var selectSubmissions: ((String?) -> Unit)? = null

    @Volatile
    private var pendingChallengeId: String? = null

    /** 挑战工具窗创建内容时注册选中回调(内容销毁时置 null)。 */
    fun attach(handler: ((String?) -> Unit)?) {
        selectSubmissions = handler
    }

    /** 工厂注册回调后取走待显示 challengeId(无则 null)。 */
    fun consumePendingChallengeId(): String? =
        pendingChallengeId.also {
            pendingChallengeId = null
        }

    /** 激活挑战工具窗并选中提交记录 tab(可在任意线程调用)。 */
    fun showSubmissions(challengeId: String?) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow =
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                    ?: return@invokeLater
            toolWindow.show(null)
            val handler = selectSubmissions
            if (handler == null) {
                pendingChallengeId = challengeId
            } else {
                pendingChallengeId = null
                handler(challengeId)
            }
        }
    }

    companion object {
        /** id 一律不动(见 WP51):改 id 会使既有用户窗口布局失效。 */
        const val TOOL_WINDOW_ID = "CodeQuest Challenge"
    }
}
