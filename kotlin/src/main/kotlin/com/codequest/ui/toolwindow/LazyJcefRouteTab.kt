package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.ui.jcef.CQBridge
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
 * 惰性 JCEF 路由 tab(1.5.0):`<base>/<route>/?challengeId=<id>` 页面的通用壳,
 * 测试用例/提交记录两 tab 共用(原 ChallengeSubmissionsTab 泛化)。
 *
 * - **惰性创建**:tab 组件先为空容器,首次切换到本 tab([onTabSelected])才创建
 *   JCEF 浏览器,避免从不查看的用户白付浏览器实例;
 * - **challengeId 联动**:[onChallengeChanged] 时已创建则重新 load 新 URL,
 *   未创建只记录(首次切换时作为初始 URL);
 * - **定向显示**:[show] 强制创建/刷新并按 challengeId 过滤(导航器入口)。
 */
class LazyJcefRouteTab(
    private val project: Project,
    private val disposableName: String,
    private val fallbackText: String,
    private val route: String,
    private val configure: ((JBCefBrowser, CQBridge) -> Unit)? = null,
    /** 内容销毁时回调(随 JCEF disposable 注销,供调用方解挂桥引用)。 */
    private val onDisposed: ((CQBridge) -> Unit)? = null,
) {
    private val logger = Logger.getLogger(LazyJcefRouteTab::class.java.name)

    private val container = JPanel(BorderLayout())
    private var browser: JBCefBrowser? = null
    private var created = false
    private var challengeId: String? = null

    val component: JComponent = container

    /** 当前挑战变化:已创建则刷新 URL,未创建记 challengeId(首次切换加载)。 */
    fun onChallengeChanged(challengeId: String?) {
        this.challengeId = challengeId
        if (created) reload()
    }

    /** 首次切换到本 tab 时创建 JCEF 内容(幂等)。 */
    fun onTabSelected() {
        if (!created) ensureCreated()
    }

    /** 定向显示(navigator 入口;null = 不带过滤)。 */
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
        var attachedBridge: CQBridge? = null
        val content =
            JcefToolWindowSupport.createContent(
                disposableName = disposableName,
                fallbackText = fallbackText,
                url = pageUrl(),
                configure = { jbBrowser, bridge ->
                    browser = jbBrowser
                    attachedBridge = bridge
                    configure?.invoke(jbBrowser, bridge)
                },
            )
        // configure 在 createContent 内同步执行,返回时引用已就绪;随内容销毁解挂
        attachedBridge?.let { bridge ->
            onDisposed?.let { handler ->
                JcefToolWindowSupport.disposableOf(content)?.let { disposable ->
                    Disposer.register(
                        disposable,
                        Disposable { handler(bridge) },
                    )
                }
            }
        }
        container.add(content, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun reload() {
        val url = pageUrl() ?: return
        val target = browser
        if (target == null) {
            logger.warning("$route tab reload skipped: browser not available")
        } else {
            target.loadURL(url)
        }
    }

    /** `<base>/<route>/` + 可选 challengeId 过滤参数;服务未就绪时为 null。 */
    private fun pageUrl(): String? =
        project.getService(CodeQuestHttpServerService::class.java).baseUrl?.let { base ->
            buildString {
                append(base).append('/').append(route).append('/')
                challengeId?.let { append("?challengeId=").append(encode(it)) }
            }
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
