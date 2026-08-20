package com.codequest.ui.toolwindow

import com.codequest.ui.jcef.CQBridge
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger

/**
 * webview 缩放(WP41,对齐原版 #I ZoomIn/ZoomOut/ActualZoom):桥命令
 * `webview:zoom` 调整当前工具窗口 JCEF 浏览器的整页缩放级别。
 *
 * ## 命令设计:delta 步进 + level 直设
 *
 * payload(信封内的 JSON 文本):
 * - `{"delta":1}` / `{"delta":-1}`:在当前级别上步进(任意整数,越界被钳制);
 * - `{"level":0}`:直接设置绝对级别(0 = 100%;`{"level":0}` 即原版 ActualZoom);
 * - level 与 delta 同时出现时 level 优先。
 *
 * 级别单位 = Chromium zoom level(对数刻度,每 ±1 级 ≈ ×1.2);钳制范围
 * [MIN_LEVEL, MAX_LEVEL] ≈ 28%–430%,严格落在 Chromium 宿主缩放区间
 * 25%–500% 内,避免宿主端二次截断导致的双端状态偏差。**不做持久化**
 * (页面加载后基线 0,YAGNI;题面与阅读器同属一个浏览器实例,天然共用)。
 *
 * 解析与级别计算是纯函数([parseZoomPayload] / [nextZoomLevel],单测锁定);
 * [attach] 负责把 handler 挂到工具窗口桥上,每个浏览器实例各持一份级别状态。
 * `CefBrowser.setZoomLevel` 可在任意线程调用(CEF 内部调度到 UI 线程生效)。
 *
 * 挂载点:[JcefToolWindowSupport.createContent] 统一注册——所有工具窗口的
 * webview 均具备缩放能力,是否暴露按钮由前端页面自行决定(WP41:题面页)。
 */
object WebviewZoom {
    /** 桥命令 topic(前端 cq-bridge.ts `TOPIC_WEBVIEW_ZOOM` 同名对齐)。 */
    const val TOPIC_WEBVIEW_ZOOM = "webview:zoom"

    /** 最小级别 ≈ 28%(1.2^-7)。 */
    const val MIN_LEVEL = -7

    /** 最大级别 ≈ 430%(1.2^8)。 */
    const val MAX_LEVEL = 8

    /** 默认/复位级别(100%)。 */
    const val RESET_LEVEL = 0

    /** Chromium 缩放因子(对数刻度:每 +1 级 ×1.2)。 */
    const val ZOOM_STEP_FACTOR = 1.2

    /** 百分比基数(级别 0 = 100%)。 */
    private const val PERCENT_BASE = 100

    private val LOGGER = Logger.getLogger(WebviewZoom::class.java.name)

    /** 在 [bridge] 上注册缩放 handler,缩放作用于 [browser](每个工具窗口独立)。 */
    @Suppress("DEPRECATION")
    fun attach(
        bridge: CQBridge,
        browser: JBCefBrowser,
    ) {
        var level = RESET_LEVEL
        bridge.on(TOPIC_WEBVIEW_ZOOM) { payload ->
            val command = parseZoomPayload(payload)
            if (command == null) {
                LOGGER.warning("dropping malformed $TOPIC_WEBVIEW_ZOOM payload: $payload")
            } else {
                level = nextZoomLevel(level, command)
                // CEF 允许任意线程调用,内部调度到 UI 线程异步生效
                browser.cefBrowser.setZoomLevel(level.toDouble())
                LOGGER.fine { "webview zoom applied: level=$level (${percentOf(level)}%)" }
            }
        }
    }

    /** 级别 → 显示百分比(ZOOM_STEP_FACTOR^level × 100,仅供日志/调试)。 */
    internal fun percentOf(level: Int): Int = (PERCENT_BASE * Math.pow(ZOOM_STEP_FACTOR, level.toDouble())).toInt()
}

/** `webview:zoom` 命令(delta 与 level 至少其一,同时存在时 level 优先)。 */
data class ZoomCommand(
    val delta: Int? = null,
    val level: Int? = null,
)

/**
 * 解析 `webview:zoom` payload(纯函数,单测锁定协议):
 * `{"delta":±n}` 或 `{"level":n}`;非法 JSON/非对象/缺字段/非整数 → null。
 */
fun parseZoomPayload(payload: String): ZoomCommand? =
    runCatching {
        val element = Json.parseToJsonElement(payload).jsonObject
        val delta = element["delta"]?.jsonPrimitive?.intOrNull
        val level = element["level"]?.jsonPrimitive?.intOrNull
        if (delta == null && level == null) null else ZoomCommand(delta, level)
    }.getOrNull()

/**
 * 计算下一缩放级别(纯函数):level 优先,否则当前级别 + delta;
 * 结果钳制到 [WebviewZoom.MIN_LEVEL, WebviewZoom.MAX_LEVEL]。
 */
fun nextZoomLevel(
    current: Int,
    command: ZoomCommand,
): Int {
    val target = command.level ?: current + (command.delta ?: 0)
    return target.coerceIn(WebviewZoom.MIN_LEVEL, WebviewZoom.MAX_LEVEL)
}
