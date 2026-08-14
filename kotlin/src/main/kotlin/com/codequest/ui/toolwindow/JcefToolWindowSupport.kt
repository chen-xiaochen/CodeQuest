package com.codequest.ui.toolwindow

import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.jcef.createCqBrowser
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.JComponent

/**
 * 编辑器侧 JCEF 工具窗口占位壳公共支撑(WP11)。
 *
 * 每个工具窗口创建一个 [JBCefBrowser] 空容器 + [CQBridge] 桥实例;页面 URL 由 WP12
 * 接入(届时加载 `http://127.0.0.1:<port>/<route>`)。当前注册一个 echo handler,
 * 供沙箱手工验证桥往返:JS 侧 `cqSend({"topic":"bridge:echo","payload":"..."})`
 * 会收到 Kotlin 回发的 `bridge:echo:reply` 事件。JCEF 不可用时降级为纯 Swing 提示。
 */
object JcefToolWindowSupport {
    const val TOPIC_ECHO = "bridge:echo"
    const val TOPIC_ECHO_REPLY = "bridge:echo:reply"

    /**
     * 创建工具窗口内容组件:JCEF 可用时为浏览器容器,否则为降级提示面板。
     *
     * @param disposableName Disposer 树节点名,随 content 销毁释放浏览器与桥
     * @param fallbackText JCEF 不可用时的提示文案
     */
    fun createContent(
        disposableName: String,
        fallbackText: String,
    ): JComponent {
        if (!JBCefApp.isSupported()) {
            return panel {
                row { text(fallbackText) }
            }
        }
        val disposable = Disposer.newDisposable(disposableName)
        val browser = createCqBrowser()
        Disposer.register(disposable, browser)
        val bridge = CQBridge(browser, disposable)
        bridge.on(TOPIC_ECHO) { payload -> bridge.postMessage(TOPIC_ECHO_REPLY, payload) }
        browser.loadURL("about:blank")
        return browser.component.also {
            it.putClientProperty(DISPOSABLE_KEY, disposable)
        }
    }

    /** 取回 [createContent] 附着在组件上的 Disposer 节点,供 content.setDisposer 使用。 */
    fun disposableOf(component: JComponent) = component.getClientProperty(DISPOSABLE_KEY) as? com.intellij.openapi.Disposable

    private const val DISPOSABLE_KEY = "codequest.disposable"
}
