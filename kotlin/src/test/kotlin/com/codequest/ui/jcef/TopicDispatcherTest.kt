package com.codequest.ui.jcef

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TopicDispatcher] 桥协议核心分发逻辑单测(WP11)。
 *
 * 该类是纯 Kotlin 逻辑,不依赖 IntelliJ/JCEF,可脱离真实浏览器测试。
 */
class TopicDispatcherTest {
    @Test
    fun `dispatch invokes handler registered for topic`() {
        val dispatcher = TopicDispatcher()
        val received = mutableListOf<String>()
        dispatcher.on("problems:list") { payload -> received.add(payload) }

        val handled = dispatcher.dispatch("problems:list", """{"page":1}""")

        assertTrue(handled, "已注册 topic 的 dispatch 应返回 true")
        assertEquals(listOf("""{"page":1}"""), received)
    }

    @Test
    fun `all handlers for same topic are invoked in registration order`() {
        val dispatcher = TopicDispatcher()
        val calls = CopyOnWriteArrayList<String>()

        dispatcher.on("submission:submit") { calls.add("first") }
        dispatcher.on("submission:submit") { calls.add("second") }

        dispatcher.dispatch("submission:submit", "{}")

        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun `dispatch on unknown topic does not throw and returns false`() {
        val dispatcher = TopicDispatcher()

        val handled = dispatcher.dispatch("nope:unknown", "{}")

        assertFalse(handled, "未注册 topic 的 dispatch 应返回 false 而非抛异常")
    }

    @Test
    fun `handler exception does not prevent other handlers and does not propagate`() {
        val dispatcher = TopicDispatcher()
        val errors = mutableListOf<Throwable>()
        val errDispatcher = TopicDispatcher { _, error -> errors.add(error) }
        val calls = mutableListOf<String>()

        errDispatcher.on("boom:topic") { throw IllegalStateException("boom") }
        errDispatcher.on("boom:topic") { calls.add("survivor") }

        val handled = errDispatcher.dispatch("boom:topic", "payload")

        assertTrue(handled)
        assertEquals(listOf("survivor"), calls)
        assertEquals(1, errors.size)

        // 未提供错误回调的 dispatcher 也不得向调用方抛异常
        dispatcher.on("boom:topic") { throw IllegalStateException("boom") }
        dispatcher.dispatch("boom:topic", "payload")
    }

    @Test
    fun `off removes previously registered handler`() {
        val dispatcher = TopicDispatcher()
        val received = mutableListOf<String>()
        val handler: (String) -> Unit = { received.add(it) }

        dispatcher.on("problems:list", handler)
        dispatcher.off("problems:list", handler)

        val handled = dispatcher.dispatch("problems:list", "{}")

        assertFalse(handled, "移除唯一 handler 后 topic 应视为未注册")
        assertTrue(received.isEmpty())
    }

    @Test
    fun `off on unknown topic does not throw`() {
        val dispatcher = TopicDispatcher()
        val handler: (String) -> Unit = { }

        dispatcher.off("nope:unknown", handler)
    }

    @Test
    fun `topics exposes currently registered topic names`() {
        val dispatcher = TopicDispatcher()
        dispatcher.on("problems:list") { }
        dispatcher.on("stats:heatmap") { }

        assertEquals(setOf("problems:list", "stats:heatmap"), dispatcher.topics)
    }

    @Test
    fun `blank topic is rejected`() {
        val dispatcher = TopicDispatcher()

        assertFailsWith<IllegalArgumentException> { dispatcher.on("  ") { } }
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch("", "{}") }
    }
}
