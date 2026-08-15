package com.codequest.ui.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [unit] WP20:nav:openProblem 桥消息 payload 解析(协议锁定)。 */
class ProblemViewNavigatorTest {
    @Test
    fun `parses platform and remote id`() {
        val request = parseOpenProblemPayload("""{"platformId":"leetcodecn","remoteId":"two-sum"}""")
        assertEquals(OpenProblemRequest("leetcodecn", "two-sum"), request)
    }

    @Test
    fun `extra fields are ignored`() {
        val request = parseOpenProblemPayload("""{"platformId":"leetcode","remoteId":"x","title":"X"}""")
        assertEquals(OpenProblemRequest("leetcode", "x"), request)
    }

    @Test
    fun `missing fields return null`() {
        assertNull(parseOpenProblemPayload("""{"platformId":"leetcode"}"""))
        assertNull(parseOpenProblemPayload("""{"remoteId":"two-sum"}"""))
        assertNull(parseOpenProblemPayload("""{}"""))
    }

    @Test
    fun `non json and non object payloads return null`() {
        assertNull(parseOpenProblemPayload("not-json"))
        assertNull(parseOpenProblemPayload("""["leetcode","two-sum"]"""))
    }
}
