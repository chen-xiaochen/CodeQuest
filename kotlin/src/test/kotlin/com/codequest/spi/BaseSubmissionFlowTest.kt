package com.codequest.spi

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.PlatformId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.SubmissionTimeoutException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [unit] BaseSubmissionFlow 提交轮询状态机(WP17):
 * 轮询节奏(退避递增/上限封顶)、终态判定即完成、maxAttempts 耗尽超时、钩子异常传播。
 *
 * 通过注入假 delayFn(记录请求的间隔,不真正休眠)使节奏完全确定性可断言。
 */
class BaseSubmissionFlowTest {

    private val handle = SubmissionHandle(PlatformId("fake"), "sub-1")

    private class FakeFlow(
        config: BaseSubmissionFlow.PollConfig,
        val recordedDelays: MutableList<Long> = mutableListOf(),
        private val script: suspend (call: Int) -> SubmissionStatusSnapshot,
    ) : BaseSubmissionFlow(config, delayFn = { ms -> recordedDelays.add(ms) }) {
        /** 钩子被调用的次数(含以异常收场的调用,故在脚本执行前自增)。 */
        var calls: Int = 0
            private set

        override suspend fun fetchRemoteStatus(handle: SubmissionHandle): SubmissionStatusSnapshot {
            val current = calls
            calls++
            return script(current)
        }
    }

    private fun snapshot(result: SubmissionResult) = SubmissionStatusSnapshot(result)

    private fun pollConfig(
        initialIntervalMs: Long = 100,
        maxIntervalMs: Long = 1_000,
        backoffMultiplier: Double = 2.0,
        maxAttempts: Int = 10,
    ) = BaseSubmissionFlow.PollConfig(
        initialIntervalMs = initialIntervalMs,
        maxIntervalMs = maxIntervalMs,
        backoffMultiplier = backoffMultiplier,
        maxAttempts = maxAttempts,
    )

    @Test
    fun `first poll terminal emits once and completes without delay`() = runBlocking {
        val flow = FakeFlow(pollConfig(), script = { snapshot(SubmissionResult.Accepted) })

        val emissions = flow.watchSnapshots(handle).toList()

        assertEquals(listOf(snapshot(SubmissionResult.Accepted)), emissions)
        assertEquals(1, flow.calls)
        assertEquals(emptyList(), flow.recordedDelays)
    }

    @Test
    fun `polls with exponential backoff until terminal`() = runBlocking {
        val flow = FakeFlow(
            pollConfig(),
            script = { call ->
                when (call) {
                    0 -> snapshot(SubmissionResult.Pending)
                    1 -> snapshot(SubmissionResult.Judging)
                    else -> snapshot(SubmissionResult.Accepted)
                }
            },
        )

        val emissions = flow.watchSnapshots(handle).toList()

        assertEquals(
            listOf(
                snapshot(SubmissionResult.Pending),
                snapshot(SubmissionResult.Judging),
                snapshot(SubmissionResult.Accepted),
            ),
            emissions,
        )
        assertEquals(3, flow.calls)
        // 第一次轮询立即执行,之后每次先退避再拉取:100ms → 200ms
        assertEquals(listOf(100L, 200L), flow.recordedDelays)
    }

    @Test
    fun `backoff interval is capped at maxIntervalMs`() = runBlocking {
        val flow = FakeFlow(
            pollConfig(initialIntervalMs = 100, maxIntervalMs = 300, maxAttempts = 10),
            script = { call ->
                if (call == 5) snapshot(SubmissionResult.Accepted) else snapshot(SubmissionResult.Pending)
            },
        )

        flow.watchSnapshots(handle).toList()

        // 100 → 200 → 400 超上限封顶为 300 → 300 → 300
        assertEquals(listOf(100L, 200L, 300L, 300L, 300L), flow.recordedDelays)
        assertEquals(6, flow.calls)
    }

    @Test
    fun `exhausting maxAttempts throws SubmissionTimeoutException`() {
        val flow = FakeFlow(
            pollConfig(initialIntervalMs = 100, maxAttempts = 3),
            script = { snapshot(SubmissionResult.Pending) },
        )

        val error = assertFailsWith<SubmissionTimeoutException> {
            runBlocking { flow.watchSnapshots(handle).toList() }
        }
        assertEquals(3, flow.calls)
        assertEquals(listOf(100L, 200L), flow.recordedDelays)
        // 超时异常信息携带平台侧提交 id,便于上层定位
        kotlin.test.assertTrue(error.message!!.contains("sub-1"))
    }

    @Test
    fun `hook exception propagates to collector`() {
        val flow = FakeFlow(
            pollConfig(initialIntervalMs = 100),
            script = { call ->
                if (call == 1) throw AuthExpiredException("cookie 失效") else snapshot(SubmissionResult.Pending)
            },
        )

        assertFailsWith<AuthExpiredException> {
            runBlocking { flow.watchSnapshots(handle).toList() }
        }
        assertEquals(2, flow.calls)
        assertEquals(listOf(100L), flow.recordedDelays)
    }

    @Test
    fun `watchSubmission maps snapshots to bare results`() = runBlocking {
        val flow = FakeFlow(
            pollConfig(),
            script = { call ->
                if (call == 1) snapshot(SubmissionResult.WrongAnswer) else snapshot(SubmissionResult.Judging)
            },
        )

        val emissions = flow.watchSubmission(handle).toList()

        assertEquals(listOf(SubmissionResult.Judging, SubmissionResult.WrongAnswer), emissions)
        assertEquals(listOf(100L), flow.recordedDelays)
    }

    @Test
    fun `snapshot metrics are carried through until terminal`() = runBlocking {
        val finalSnapshot = SubmissionStatusSnapshot(
            result = SubmissionResult.Accepted,
            timeMs = 42,
            memoryKb = 4_200,
        )
        val flow = FakeFlow(
            pollConfig(),
            script = { call ->
                if (call == 1) finalSnapshot else snapshot(SubmissionResult.Pending)
            },
        )

        val emissions = flow.watchSnapshots(handle).toList()

        assertEquals(finalSnapshot, emissions.last())
    }
}
