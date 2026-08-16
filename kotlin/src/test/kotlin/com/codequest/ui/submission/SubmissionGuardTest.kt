package com.codequest.ui.submission

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [unit] WP21:提交中防重复触发(幂等门)。 */
class SubmissionGuardTest {
    @Test
    fun `begin succeeds once until end is called`() {
        val guard = SubmissionGuard()
        val key = "E:/proj/codequest/two-sum-abc12345/TwoSum.kt"

        assertTrue(guard.begin(key))
        assertFalse(guard.begin(key), "same key must not begin twice")

        guard.end(key)
        assertTrue(guard.begin(key), "key is reusable after end")
    }

    @Test
    fun `different keys do not interfere`() {
        val guard = SubmissionGuard()

        assertTrue(guard.begin("a"))
        assertTrue(guard.begin("b"))
        assertFalse(guard.begin("a"))
    }

    @Test
    fun `end of unknown key is a no-op`() {
        val guard = SubmissionGuard()
        guard.end("missing")
        assertTrue(guard.begin("missing"))
    }
}
