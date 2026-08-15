package com.codequest.ui.toolwindow

import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.jcef.createCqBrowser
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.logging.Logger
import javax.swing.JComponent

/**
 * 编辑器侧 JCEF 工具窗口壳公共支撑(WP11 建立,WP16 挂载真实页面 URL + echo 自检探针)。
 *
 * 每个工具窗口创建一个 [JBCefBrowser] 容器 + [CQBridge] 桥实例:
 * - [createContent] 的 [url] 指向本地 ResourceHttpServer 的 webview 页面
 *   (`http://127.0.0.1:<port>/<page>/`,端口由 CodeQuestHttpServerService 分配);
 * - 注册 `bridge:echo` → `bridge:echo:reply` 回声 handler,并在页面加载完成后注入
 *   **echo 自检探针**(JS→Kotlin→JS 完整往返),往返成功写入 IDE 日志,作为
 *   WP16 验收证据(`CQBridge echo round-trip verified`);
 * - JCEF 不可用时降级为纯 Swing 提示。
 */
object JcefToolWindowSupport {
    const val TOPIC_ECHO = "bridge:echo"
    const val TOPIC_ECHO_REPLY = "bridge:echo:reply"

    /** echo 自检探针回执 topic(仅探针链路使用,不属业务协议)。 */
    private const val TOPIC_ECHO_PROBE_ACK = "bridge:echo:probeAck"

    private val LOGGER = Logger.getLogger(JcefToolWindowSupport::class.java.name)

    /**
     * 创建工具窗口内容组件:JCEF 可用时为浏览器容器,否则为降级提示面板。
     *
     * @param disposableName Disposer 树节点名,随 content 销毁释放浏览器与桥
     * @param fallbackText JCEF 不可用时的提示文案
     * @param url 页面 URL;null 时保持 about:blank(无页面的裸壳)
     * @param configure 浏览器与桥创建后的宿主侧挂载点(WP20:注册业务桥 handler、
     *   把浏览器引用交给导航服务等);在页面加载开始前调用
     */
    @JvmOverloads
    fun createContent(
        disposableName: String,
        fallbackText: String,
        url: String? = null,
        configure: ((JBCefBrowser, CQBridge) -> Unit)? = null,
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
        attachEchoVerification(bridge, browser, disposable, disposableName)
        configure?.invoke(browser, bridge)
        browser.loadURL(url ?: "about:blank")
        return browser.component.also {
            it.putClientProperty(DISPOSABLE_KEY, disposable)
        }
    }

    /** 取回 [createContent] 附着在组件上的 Disposer 节点,供 content.setDisposer 使用。 */
    fun disposableOf(component: JComponent) = component.getClientProperty(DISPOSABLE_KEY) as? Disposable

    /**
     * 挂载 echo 回声 handler + 页面加载后的自动往返探针。
     *
     * 探针链路:页面 JS 轮询等待 `cqSend`/`__cqBridge` 就绪 → 包装 `__cqBridge.emit`
     * 监听 reply → 发送 `bridge:echo` → Kotlin echo handler 回发 reply → JS 包装层收到
     * 后回发 `bridge:echo:probeAck` → Kotlin 记录「round-trip verified」日志。
     */
    @Suppress("DEPRECATION")
    private fun attachEchoVerification(
        bridge: CQBridge,
        browser: JBCefBrowser,
        disposable: Disposable,
        tag: String,
    ) {
        bridge.on(TOPIC_ECHO) { payload ->
            LOGGER.info("CQBridge inbound echo [$tag]: $payload")
            bridge.postMessage(TOPIC_ECHO_REPLY, payload)
        }
        bridge.on(TOPIC_ECHO_PROBE_ACK) { payload ->
            LOGGER.info("CQBridge echo round-trip verified [$tag]: reply payload=$payload")
        }
        val probeHandler =
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    browser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (frame.isMain) {
                        bridge.evaluate(probeScript(tag))
                    }
                }
            }
        browser.jbCefClient.addLoadHandler(probeHandler, browser.cefBrowser)
        Disposer.register(disposable, Disposable { browser.jbCefClient.removeLoadHandler(probeHandler, browser.cefBrowser) })
    }

    /** 注入页面的自检探针脚本(自带就绪轮询,页面 JS 慢启动也能触发)。 */
    private fun probeScript(tag: String): String {
        val safeTag = tag.replace("'", "")
        return listOf(
            "(function cqProbe(attempt) {",
            "  if (typeof window.cqSend !== 'function' || !window.__cqBridge) {",
            "    if (attempt < 60) setTimeout(function () { cqProbe(attempt + 1); }, 500);",
            "    return;",
            "  }",
            "  if (window.__cqProbeInstalled) return;",
            "  window.__cqProbeInstalled = true;",
            "  var original = window.__cqBridge.emit;",
            "  window.__cqBridge.emit = function (envelope) {",
            "    try {",
            "      var record = typeof envelope === 'string' ? JSON.parse(envelope) : envelope;",
            "      if (record && record.topic === 'bridge:echo:reply') {",
            "        window.cqSend(JSON.stringify({ topic: 'bridge:echo:probeAck', payload: record.payload }));",
            "      }",
            "    } catch (e) { /* probe only */ }",
            "    return original.apply(this, arguments);",
            "  };",
            "  window.cqSend(JSON.stringify({ topic: 'bridge:echo', payload: JSON.stringify({ probe: 'codequest', page: '$safeTag' }) }));",
            "})(0);",
        ).joinToString("\n")
    }

    private const val DISPOSABLE_KEY = "codequest.disposable"
}
