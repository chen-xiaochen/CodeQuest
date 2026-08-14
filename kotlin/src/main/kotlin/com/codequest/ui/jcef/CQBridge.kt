package com.codequest.ui.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * CQBridge:Kotlin 侧与 webview 前端之间的 JCEF 消息桥(WP11)。
 *
 * ## 设计原则(spec 2026-08-14 §4)
 *
 * 桥消息**只承载命令与事件**(提交、打开文件、跳转等轻量交互);页面数据(题目列表、
 * 统计、检索结果等)一律通过本地 ResourceHttpServer 的 REST 端点 HTTP 拉取,
 * 从根上消灭旧版大数据量消息序列化导致的 UI 卡顿。
 *
 * ## 消息格式
 *
 * 双向统一为 JSON 信封 `{"topic":"<namespace:action>","payload":"<原始字符串>"}`:
 * - topic 命名空间化,如 `problems:list` / `submission:submit` / `nav:openProblem`
 * - payload 为不透明字符串,业务侧自行约定并解析(通常为 JSON 文本)
 *
 * ## 传输实现
 *
 * - Kotlin → JS:[postMessage] 经 `executeJavaScript` 调用前端注入的
 *   `window.__cqBridge.emit({topic, payload})`(WP12 的 JS 侧客户端提供)
 * - JS → Kotlin:前端调用页面加载完成时注入的 `window.cqSend(JSON字符串)`,
 *   经 [JBCefJSQuery] 回到 JVM 后交 [TopicDispatcher] 分发
 *
 * 核心分发逻辑在 [TopicDispatcher](纯 Kotlin,可脱离 JCEF 单测),本类只做传输适配。
 */
@Suppress("DEPRECATION")
class CQBridge(
    val browser: JBCefBrowser,
    parentDisposable: Disposable,
    val dispatcher: TopicDispatcher = TopicDispatcher(),
) : Disposable {
    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    private val loadHandler =
        object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(
                browser: CefBrowser,
                frame: CefFrame,
                httpStatusCode: Int,
            ) {
                if (frame.isMain) {
                    injectJsApi()
                }
            }
        }

    init {
        jsQuery.addHandler { raw ->
            handleInbound(raw)
            JBCefJSQuery.Response(null)
        }
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        Disposer.register(parentDisposable, this)
    }

    /**
     * 向前端下发一条命令/事件消息。
     *
     * @param topic 命名空间化 topic,如 `problems:list`
     * @param jsonPayload 原始 JSON payload 字符串(信封内作为不透明字符串透传)
     */
    fun postMessage(
        topic: String,
        jsonPayload: String,
    ) {
        val envelope = buildEnvelope(topic, jsonPayload)
        val script = "window.${JS_BRIDGE_OBJECT} && window.${JS_BRIDGE_OBJECT}.emit($envelope);"
        execute(script)
    }

    /** 注册接收 handler(转发给 [dispatcher])。 */
    fun on(
        topic: String,
        handler: (String) -> Unit,
    ) {
        dispatcher.on(topic, handler)
    }

    /** 注销 handler(转发给 [dispatcher])。 */
    fun off(
        topic: String,
        handler: (String) -> Unit,
    ) {
        dispatcher.off(topic, handler)
    }

    override fun dispose() {
        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
        Disposer.dispose(jsQuery)
    }

    private fun execute(script: String) {
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    /** 每次主框架加载完成后向前端注入 JS 侧发送函数。 */
    private fun injectJsApi() {
        val script = "window.${JS_SEND_FUNCTION} = function(message) { ${jsQuery.inject("message")} };"
        execute(script)
    }

    private fun handleInbound(raw: String) {
        val element = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val topic = runCatching { element["topic"]?.jsonPrimitive?.content }.getOrNull() ?: return
        val payload = runCatching { element["payload"]?.jsonPrimitive?.content }.getOrNull() ?: return
        dispatcher.dispatch(topic, payload)
    }

    private companion object {
        /** 前端发送函数名:JS → Kotlin。 */
        const val JS_SEND_FUNCTION = "cqSend"

        /** 前端桥对象名:Kotlin → JS,须实现 `emit({topic, payload})`。 */
        const val JS_BRIDGE_OBJECT = "__cqBridge"
    }
}

/** 构造双向统一的 JSON 信封字符串:`{"topic":"...","payload":"..."}`。 */
private fun buildEnvelope(
    topic: String,
    payload: String,
): String =
    JsonObject(
        mapOf(
            "topic" to JsonPrimitive(topic),
            "payload" to JsonPrimitive(payload),
        ),
    ).toString()
