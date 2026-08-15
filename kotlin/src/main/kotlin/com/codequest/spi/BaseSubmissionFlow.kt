package com.codequest.spi

import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.SubmissionTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 通用「提交 → 轮询 fetchRemoteStatus → 终态/超时」状态机(WP17)。
 *
 * 平台只实现 [fetchRemoteStatus] 钩子(单次拉取远端判定),轮询节奏(指数退避 +
 * 间隔封顶 + 最大尝试次数)全部由本类承担,消灭各平台复制的轮询代码。
 * 不依赖任何平台包;典型用法:
 *
 * ```kotlin
 * class LeetCodeSubmissionFlow : BaseSubmissionFlow() {
 *     override suspend fun fetchRemoteStatus(handle: SubmissionHandle) = api.checkSubmission(handle)
 * }
 * // SubmissionCapable.watchSubmission = leetCodeSubmissionFlow.watchSubmission(handle)
 * ```
 *
 * 协程约定:返回冷流,可从任意线程收集;钩子抛出的领域异常原样向收集者传播
 * (AuthExpiredException 触发重登、RateLimitedException 触发退避)。
 *
 * @param config 轮询参数(初始间隔/最大间隔/退避倍数/最大尝试次数)。
 * @param delayFn 休眠策略,默认 [delay];测试注入假实现以确定性断言轮询节奏。
 */
abstract class BaseSubmissionFlow(
    private val config: PollConfig = PollConfig(),
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    /** 轮询参数;超时语义为「maxAttempts 次内未到终态即抛 [SubmissionTimeoutException]」。 */
    data class PollConfig(
        val initialIntervalMs: Long = DEFAULT_INITIAL_INTERVAL_MS,
        val maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS,
        val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ) {
        init {
            require(initialIntervalMs > 0) { "initialIntervalMs 必须为正: $initialIntervalMs" }
            require(maxIntervalMs >= initialIntervalMs) { "maxIntervalMs 不得小于 initialIntervalMs" }
            require(backoffMultiplier >= 1.0) { "backoffMultiplier 必须 >= 1.0: $backoffMultiplier" }
            require(maxAttempts > 0) { "maxAttempts 必须为正: $maxAttempts" }
        }

        companion object {
            const val DEFAULT_INITIAL_INTERVAL_MS: Long = 1_000L
            const val DEFAULT_MAX_INTERVAL_MS: Long = 15_000L
            const val DEFAULT_BACKOFF_MULTIPLIER: Double = 2.0
            const val DEFAULT_MAX_ATTEMPTS: Int = 20
        }
    }

    /**
     * 单次远端状态拉取钩子:返回该提交当前快照;平台侧网络/鉴权错误必须映射为
     * core 领域异常后抛出(映射归 infra/http,WP18)。
     */
    protected abstract suspend fun fetchRemoteStatus(handle: SubmissionHandle): SubmissionStatusSnapshot

    /** 轮询快照流(带耗时/内存指标),供需要完整快照的调用方(如落库)使用。 */
    fun watchSnapshots(handle: SubmissionHandle): Flow<SubmissionStatusSnapshot> =
        flow {
            var interval = config.initialIntervalMs
            var attempt = 0
            while (attempt < config.maxAttempts) {
                if (attempt > 0) {
                    delayFn(interval)
                    interval = (interval * config.backoffMultiplier).roundToLong().coerceAtMost(config.maxIntervalMs)
                }
                val snapshot = fetchRemoteStatus(handle)
                emit(snapshot)
                if (snapshot.result.isTerminal) return@flow
                attempt++
            }
            throw SubmissionTimeoutException(
                String.format(
                    Locale.ROOT,
                    "轮询 %d 次(间隔 %d→%dms)后仍未到达终态: platform=%s, submission=%s",
                    config.maxAttempts,
                    config.initialIntervalMs,
                    config.maxIntervalMs,
                    handle.platformId.value,
                    handle.remoteSubmissionId,
                ),
            )
        }

    /** [SubmissionCapable.watchSubmission] 的直接实现:快照流映射为纯判定流。 */
    fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = watchSnapshots(handle).map { it.result }
}

/** 提交状态快照:判定 + 平台报告的耗时/内存(未给出为 null)。 */
@Serializable
data class SubmissionStatusSnapshot(
    val result: SubmissionResult,
    val timeMs: Int? = null,
    val memoryKb: Int? = null,
)
