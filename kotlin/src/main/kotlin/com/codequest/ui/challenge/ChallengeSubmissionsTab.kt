package com.codequest.ui.challenge

import com.codequest.infra.i18n.PluginBundle
import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.submission.SubmissionCoordinator
import com.codequest.ui.toolwindow.JcefToolWindowSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 挑战工具窗「提交记录」tab(WP51):JCEF 惰性加载 `<base>/submissions/?challengeId=<id>`。
 *
 * 原 SubmissionsToolWindowFactory(WP11/WP21 独立工具窗)删除后,提交记录页迁移至此:
 * - **惰性创建**:tab 组件先为空容器,首次切换到本 tab([onTabSelected])才创建 JCEF
 *   浏览器,避免从不看提交记录的用户白付一个浏览器实例;
 * - **challengeId 联动**:工作区面板选中挑战变化([onChallengeChanged])时,已创建则
 *   重新 load 新 URL,未创建只记录当前 challengeId(首次切换时作为初始 URL);
 * - 提交完成导航([show]):切到本 tab 并按指定 challengeId 定向过滤;
 * - 桥挂接沿用 WP21 机制:提交落库后 `submission:updated` 桥推送页面刷新
 *   (attach/detach 生命周期与 JCEF 内容一致)。
 */
class ChallengeSubmissionsTab(
    private val project: Project,
) {
    private val logger = Logger.getLogger(ChallengeSubmissionsTab::class.java.name)

    private val container = JPanel(BorderLayout())
    private var browser: JBCefBrowser? = null
    private var created = false
    private var challengeId: String? = null

    val component: JComponent = container

    /** 选中挑战变化:已创建则刷新 URL,未创建记 challengeId(首次切换加载)。 */
    fun onChallengeChanged(challengeId: String?) {
        this.challengeId = challengeId
        if (created) reload()
    }

    /** 首次切换到本 tab 时创建 JCEF 内容(幂等)。 */
    fun onTabSelected() {
        if (!created) ensureCreated()
    }

    /** 定向显示某挑战的提交记录(提交完成导航入口;null = 不带过滤)。 */
    fun show(challengeId: String?) {
        if (challengeId != null) this.challengeId = challengeId
        if (created) reload() else ensureCreated()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun ensureCreated() {
        if (created) return
        created = true
        val coordinator = project.getService(SubmissionCoordinator::class.java)
        var attachedBridge: CQBridge? = null
        val content =
            JcefToolWindowSupport.createContent(
                disposableName = "codequest.challenge.submissions",
                fallbackText = PluginBundle.message("challenge.panel.submissionsFallback"),
                url = submissionsUrl(),
                configure = { jbBrowser, bridge ->
                    browser = jbBrowser
                    attachedBridge = bridge
                    coordinator.attachSubmissionsBridge(bridge)
                },
            )
        // configure 在 createContent 内同步执行,返回时引用已就绪;随内容销毁解挂
        attachedBridge?.let { bridge ->
            JcefToolWindowSupport.disposableOf(content)?.let { disposable ->
                Disposer.register(
                    disposable,
                    Disposable { coordinator.detachSubmissionsBridge(bridge) },
                )
            }
        }
        container.add(content, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun reload() {
        val url = submissionsUrl() ?: return
        val target = browser
        if (target == null) {
            logger.warning("submissions tab reload skipped: browser not available")
        } else {
            target.loadURL(url)
        }
    }

    /** `<base>/submissions/` + 可选 challengeId 过滤参数;服务未就绪时为 null。 */
    private fun submissionsUrl(): String? =
        project.getService(CodeQuestHttpServerService::class.java).baseUrl?.let { base ->
            buildString {
                append(base).append("/submissions/")
                challengeId?.let { append("?challengeId=").append(encode(it)) }
            }
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
