package com.codequest.platform.luogu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP26:洛谷 HTML/信封解析纯函数——lentille-context 提取、csrf-token meta、
 * C3VK 反爬标记、登录态 title / 401 标题、record decodeURIComponent 提取。
 * 页面切片为 2026-08 firecrawl rawHtml 实测形态的固化夹具。
 */
class LuoguHtmlTest {
    @Test
    fun `lentille context extracted from script tag`() {
        val html =
            """
            <html><head><meta name="csrf-token" content="t">
            <script id="lentille-context" type="application/json">{"instance":"main","data":{"problems":{"count":1}}}</script>
            </head></html>
            """.trimIndent()

        assertEquals("""{"instance":"main","data":{"problems":{"count":1}}}""", LuoguHtml.extractLentilleContext(html))
    }

    @Test
    fun `lentille context missing returns null`() {
        assertNull(LuoguHtml.extractLentilleContext("<html><body>plain</body></html>"))
        assertNull(LuoguHtml.extractLentilleContext("""<script id="other"></script>"""))
    }

    @Test
    fun `csrf token extracted from meta with special characters`() {
        val html = """<meta name="renderer" content="webkit"><meta name="csrf-token" content="1787113333:O8QOVr+QEB/NmpweP0mFgpa4yAWn3YsnZ12Ea4GF3yg=">"""
        assertEquals("1787113333:O8QOVr+QEB/NmpweP0mFgpa4yAWn3YsnZ12Ea4GF3yg=", LuoguHtml.extractCsrfToken(html))
        assertNull(LuoguHtml.extractCsrfToken("""<meta name="renderer" content="webkit">"""))
    }

    @Test
    fun `c3vk marker extracted when present`() {
        assertEquals("abc123", LuoguHtml.extractC3vk("xxx C3VK=abc123; yyy"))
        assertNull(LuoguHtml.extractC3vk("no marker here"))
    }

    @Test
    fun `title extracted and trimmed`() {
        assertEquals("题目列表 - 洛谷", LuoguHtml.extractTitle("<head><title> 题目列表 - 洛谷 </title></head>"))
        assertNull(LuoguHtml.extractTitle("<html></html>"))
    }

    @Test
    fun `401 heading detected`() {
        assertTrue(LuoguHtml.contains401Heading("""<div id="app"><h1>401 : 未登录</h1></div>"""))
        assertFalse(LuoguHtml.contains401Heading("""<div id="app"><h1>用户设置</h1></div>"""))
        assertFalse(LuoguHtml.contains401Heading("<div>no heading</div>"))
    }

    @Test
    fun `encoded record json extracted greedily`() {
        val html = """<script>var x = decodeURIComponent("%7B%22a%22%3A1%7D");</script>"""
        assertEquals("%7B%22a%22%3A1%7D", LuoguHtml.extractEncodedRecordJson(html))
        assertNull(LuoguHtml.extractEncodedRecordJson("<script>var x = 1;</script>"))
    }
}
