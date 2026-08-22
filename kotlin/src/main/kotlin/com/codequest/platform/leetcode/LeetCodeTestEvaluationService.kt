package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.TestEvaluationCapable
import com.codequest.spi.TestRunRequest
import com.codequest.spi.TestRunResult
import com.codequest.spi.TestRunStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale
import kotlin.math.roundToLong

/**
 * LeetCode 云端测试(WP53):TestEvaluationCapable 实现。
 *
 * **适配器自身不写通用轮询状态机**:节奏参数(1s 起步指数退避封顶 + 最大尝试次数)
 * 直接复用 [BaseSubmissionFlow.PollConfig] 校验与语义;interpret 的终态条件是
 * `state == "SUCCESS"`(而非判定 isTerminal)、发射类型是 [TestRunStatus] 全集,
 * 与 BaseSubmissionFlow 的快照/判定模型不同,故此处自持精简轮询循环
 * (取自 BaseSubmissionFlow 的同款节奏:首次立即查,后续按退避间隔)。
 *
 * 协议 = 旧 runAnswer/getRunCodeResult 事实(见 [LeetCodeApi.interpretSolution]):
 * POST interpret_solution → interpret_id → 轮询 check;CE/RE 取 full_* 全文优先
 * (同 WP36 formatErrorMessage 语义)。
 *
 * 超时抛 [SubmissionTimeoutException];语言不支持抛 [OJException](同提交侧守卫)。
 */
class LeetCodeTestEvaluationService(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
    private val pollConfig: BaseSubmissionFlow.PollConfig = BaseSubmissionFlow.PollConfig(),
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : TestEvaluationCapable {
    override fun watchTestRun(request: TestRunRequest): Flow<TestRunStatus> =
        flow {
            if (LeetCodeLanguages.find(request.languageId) == null) {
                throw OJException("LeetCode 不支持的测试语言 id: ${request.languageId}")
            }
            val interpretId =
                api.interpretSolution(
                    questionId = request.questionId,
                    problemSlug = request.problemRemoteId,
                    langSlug = request.languageId,
                    code = request.code,
                    dataInput = request.dataInput,
                )
            var interval = pollConfig.initialIntervalMs
            var attempt = 0
            while (attempt < pollConfig.maxAttempts) {
                if (attempt > 0) {
                    delayFn(interval)
                    interval = (interval * pollConfig.backoffMultiplier).roundToLong().coerceAtMost(pollConfig.maxIntervalMs)
                }
                val check = api.fetchInterpretCheck(interpretId)
                if (check.state.equals("SUCCESS", ignoreCase = true)) {
                    emit(TestRunStatus.Finished(check.toTestRunResult()))
                    return@flow
                }
                emit(TestRunStatus.Pending)
                attempt++
            }
            throw SubmissionTimeoutException(
                String.format(
                    Locale.ROOT,
                    "interpret 轮询 %d 次(间隔 %d→%dms)后仍未到达 SUCCESS: platform=%s, interpret=%s",
                    pollConfig.maxAttempts,
                    pollConfig.initialIntervalMs,
                    pollConfig.maxIntervalMs,
                    variant.id.value,
                    interpretId,
                ),
            )
        }
}

/** interpret check 快照 → 平台无关结果全集(CE/RE full_* 优先,回退短文)。 */
internal fun LeetCodeApi.LeetCodeInterpretCheck.toTestRunResult(): TestRunResult =
    TestRunResult(
        correctCount = totalCorrect,
        totalCount = totalTestcases,
        codeAnswers = codeAnswer,
        expectedCodeAnswers = expectedCodeAnswer,
        compareResult = compareResult,
        statusRuntime = statusRuntime,
        statusMemory = statusMemory,
        compileError = fullCompileError ?: compileError,
        runtimeError = fullRuntimeError ?: runtimeError,
        state = state,
    )
