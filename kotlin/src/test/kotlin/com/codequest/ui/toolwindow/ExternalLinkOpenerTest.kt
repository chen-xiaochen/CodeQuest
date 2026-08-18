package com.codequest.ui.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] WP26:nav:openUrl 桥消息 payload 解析(纯函数,锁定协议)。
 */
class ExternalLinkOpenerTest {
    @Test
    fun `parses http and https urls`() {
        assertEquals(
            OpenUrlRequest("https://www.luogu.com.cn/problem/solution/P1001"),
            parseOpenUrlPayload("""{"url":"https://www.luogu.com.cn/problem/solution/P1001"}"""),
        )
        assertEquals(OpenUrlRequest("http://example.com"), parseOpenUrlPayload("""{"url":"http://example.com"}"""))
    }

    @Test
    fun `rejects missing field malformed json and non-http schemes`() {
        assertNull(parseOpenUrlPayload("""{"href":"https://x"}"""))
        assertNull(parseOpenUrlPayload("not-json"))
        assertNull(parseOpenUrlPayload("""["https://x"]"""))
        assertNull(parseOpenUrlPayload("""{"url":"javascript:alert(1)"}"""))
        assertNull(parseOpenUrlPayload("""{"url":""}"""))
    }
}
