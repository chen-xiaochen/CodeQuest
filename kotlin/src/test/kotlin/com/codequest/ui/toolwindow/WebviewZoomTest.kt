package com.codequest.ui.toolwindow

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP41:`webview:zoom` 桥命令——payload 解析与级别计算(纯函数,
 * 锁定协议:delta 步进 / level 直设 / 钳制范围)。
 */
class WebviewZoomTest {
    // -------------------------------------------------------------------
    // payload 解析
    // -------------------------------------------------------------------

    @Test
    fun `parses delta step command`() {
        assertEquals(ZoomCommand(delta = 1, level = null), parseZoomPayload("""{"delta":1}"""))
        assertEquals(ZoomCommand(delta = -1, level = null), parseZoomPayload("""{"delta":-1}"""))
        assertEquals(ZoomCommand(delta = 5, level = null), parseZoomPayload("""{"delta":5}"""))
    }

    @Test
    fun `parses absolute level command`() {
        assertEquals(ZoomCommand(delta = null, level = 0), parseZoomPayload("""{"level":0}"""))
        assertEquals(ZoomCommand(delta = null, level = 3), parseZoomPayload("""{"level":3}"""))
    }

    @Test
    fun `keeps both fields when present and level wins later`() {
        assertEquals(ZoomCommand(delta = -5, level = 2), parseZoomPayload("""{"delta":-5,"level":2}"""))
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(parseZoomPayload("not-json"))
        assertNull(parseZoomPayload("""{}"""), "缺 delta/level 字段")
        assertNull(parseZoomPayload("""{"delta":1.5}"""), "非整数")
        assertNull(parseZoomPayload("""{"delta":"abc"}"""))
        assertNull(parseZoomPayload("""["zoom"]"""), "非对象")
        assertNull(parseZoomPayload("""{"speed":1}"""), "未知字段不算命令")
    }

    // -------------------------------------------------------------------
    // 级别计算(delta 步进 / level 直设 / 钳制)
    // -------------------------------------------------------------------

    @Test
    fun `steps level by delta`() {
        assertEquals(1, nextZoomLevel(0, ZoomCommand(delta = 1, level = null)))
        assertEquals(-1, nextZoomLevel(0, ZoomCommand(delta = -1, level = null)))
        assertEquals(3, nextZoomLevel(2, ZoomCommand(delta = 1, level = null)))
    }

    @Test
    fun `clamps step to host zoom range`() {
        assertEquals(WebviewZoom.MAX_LEVEL, nextZoomLevel(WebviewZoom.MAX_LEVEL, ZoomCommand(delta = 1, level = null)))
        assertEquals(WebviewZoom.MIN_LEVEL, nextZoomLevel(WebviewZoom.MIN_LEVEL, ZoomCommand(delta = -1, level = null)))
        assertEquals(WebviewZoom.MAX_LEVEL, nextZoomLevel(0, ZoomCommand(delta = 100, level = null)))
        assertEquals(WebviewZoom.MIN_LEVEL, nextZoomLevel(0, ZoomCommand(delta = -100, level = null)))
    }

    @Test
    fun `sets absolute level and reset is zero`() {
        assertEquals(0, nextZoomLevel(4, ZoomCommand(delta = null, level = 0)))
        assertEquals(-3, nextZoomLevel(4, ZoomCommand(delta = null, level = -3)))
    }

    @Test
    fun `clamps absolute level to host zoom range`() {
        assertEquals(WebviewZoom.MAX_LEVEL, nextZoomLevel(0, ZoomCommand(delta = null, level = 999)))
        assertEquals(WebviewZoom.MIN_LEVEL, nextZoomLevel(0, ZoomCommand(delta = null, level = -999)))
    }

    @Test
    fun `level takes precedence over delta`() {
        assertEquals(2, nextZoomLevel(5, ZoomCommand(delta = -5, level = 2)))
    }

    @Test
    fun `zoom level unit stays inside chromium zoom percent range`() {
        // 钳制边界落在 Chromium 宿主缩放区间 25%–500%(level = ln(pct/100)/ln(1.2))内,
        // 避免宿主端二次截断导致 Kotlin 侧状态与实际缩放不一致
        val minPercent = 100 * Math.pow(1.2, WebviewZoom.MIN_LEVEL.toDouble())
        val maxPercent = 100 * Math.pow(1.2, WebviewZoom.MAX_LEVEL.toDouble())
        assertTrue(minPercent >= 25.0, "MIN_LEVEL percent=$minPercent")
        assertTrue(maxPercent <= 500.0, "MAX_LEVEL percent=$maxPercent")
    }

    @Test
    fun `json null command field parses to null command`() {
        // 信封层 payload 恒为字符串;此处锁定 JsonNull 字段(intOrNull → null)不会
        // 被误当作 0 级命令
        assertNull(parseZoomPayload("""{"delta":null}"""))
    }

    @Test
    fun `envelope carrying json null delta is rejected before dispatch`() {
        // 模拟 Kotlin 侧信封反序列化路径(与 CQBridge.handleInbound 同式):
        // payload 文本 → JSON 对象;JsonNull delta → 命令整体判非法
        val envelope = """{"topic":"webview:zoom","payload":"{\"delta\":null}"}"""
        val payload =
            kotlinx.serialization.json.Json
                .parseToJsonElement(envelope)
                .jsonObject["payload"]!!
                .jsonPrimitive.content
        assertNull(parseZoomPayload(payload))
        assertTrue(JsonNull === JsonNull, "sanity: enum singleton")
    }
}
