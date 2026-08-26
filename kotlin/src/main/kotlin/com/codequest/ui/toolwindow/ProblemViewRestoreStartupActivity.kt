package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目启动后活动(1.6.0):恢复上次打开的题目——隔天重开 IDE 时题面工具窗直接载入
 * 昨天的题目(题面走本地 SQLite 缓存,离线可用),免去重新搜索题目标题。
 *
 * 依赖顺序:先实例化 [CodeQuestHttpServerService] 保证 baseUrl 就绪
 * ([ProblemViewNavigator.openProblem] 在 baseUrl 为空时静默早退);恢复动作本身
 * 经 openProblem 的 invokeLater 上 EDT。属 ui 层(infra 禁止依赖 ui,不能挂在
 * infra.messaging 的启动活动里)。
 */
class ProblemViewRestoreStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val request =
            parseLastOpenedProblem(CodeQuestSettings.getInstance(project).state.lastOpenedProblem)
                ?: return
        project.getService(CodeQuestHttpServerService::class.java)
        project.getService(ProblemViewNavigator::class.java).openProblem(request.platformId, request.remoteId)
    }
}
