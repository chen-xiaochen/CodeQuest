package com.codequest.ui.toolwindow

import com.intellij.ide.BrowserUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger

/**
 * webview 外链出口(WP26):处理 `nav:openUrl` 桥消息,经 IDE 的
 * [BrowserUtil] 在系统浏览器打开页面。
 *
 * 动机:problem-view 页题解列表的每条引用只有站外 URL(题解正文渲染是 M5),
 * JCEF 内嵌页面无法直接弹系统浏览器,故经桥消息由 Kotlin 侧代开。
 * 形态对齐 `nav:openProblem`([ProblemViewNavigator]):解析为纯函数,
 * handler 注册在各工具窗口工厂的 configure 回调。
 */
object ExternalLinkOpener {
    const val TOPIC_NAV_OPEN_URL = "nav:openUrl"

    /** 在系统浏览器打开外链(可在任意线程调用,BrowserUtil 内部自行调度 EDT)。 */
    fun open(url: String) {
        BrowserUtil.open(url)
    }
}

/** nav:openUrl 的载荷。 */
data class OpenUrlRequest(
    val url: String,
)

/**
 * 解析 `nav:openUrl` 桥消息 payload(纯函数,单测锁定协议):
 * `{"url":"https://..."}`;缺字段/非法 JSON/非 http(s) 返回 null。
 */
fun parseOpenUrlPayload(payload: String): OpenUrlRequest? =
    runCatching {
        val element = Json.parseToJsonElement(payload).jsonObject
        val url = element["url"]?.jsonPrimitive?.content
        if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            null
        } else {
            OpenUrlRequest(url)
        }
    }.getOrNull()
        .also {
            if (it == null) {
                Logger.getLogger("parseOpenUrlPayload").warning("unparseable nav:openUrl payload: $payload")
            }
        }
