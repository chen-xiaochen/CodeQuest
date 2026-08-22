package com.codequest.spi

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 云端测试能力(WP53):平台适配器按需实现——在平台侧用自定义输入跑一遍代码
 * (不产生正式提交记录),取回逐用例比对结果。
 *
 * 原版事实(旧 Scala runAnswer/getRunCodeResult):LeetCode 系为
 * `POST /problems/{slug}/interpret_solution/` → 轮询 `/submissions/detail/{interpretId}/check/`,
 * 终态条件 state == "SUCCESS"(区别于提交轮询的判定 isTerminal);轮询节奏
 * (1s 起步指数退避封顶 + 最大尝试次数)复用 [BaseSubmissionFlow.PollConfig] 语义,
 * 超时抛 [com.codequest.core.model.SubmissionTimeoutException]。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface TestEvaluationCapable {
    /**
     * 发起云端测试并轮询结果,包装为冷流:非终态逐次发射 [TestRunStatus.Pending],
     * 到达终态发射 [TestRunStatus.Finished] 后正常完成;超时/登录失效等以异常终止流。
     */
    fun watchTestRun(request: TestRunRequest): Flow<TestRunStatus>
}

/** 云端测试请求(WP53 最小集,字段语义 = 旧 LeetCodeRunRequest)。 */
@Serializable
data class TestRunRequest(
    /** 平台侧题目标识(titleSlug,用于 URL 寻址;与 [ProblemSummary.remoteId] 同源)。 */
    val problemRemoteId: String,
    /** 平台侧题目数字主档 id(用于请求体 question_id;与 [ProblemStatement.questionId] 同源)。 */
    val questionId: String,
    /** 平台侧语言标识(与 [LanguageInfo.id] 同源)。 */
    val languageId: String,
    /** 待测代码全文。 */
    val code: String,
    /** 自定义测试输入原文(多参数以平台约定分隔)。 */
    val dataInput: String,
)

/** 云端测试状态:非终态 Pending,终态 Finished(携带结果全集)。 */
sealed interface TestRunStatus {
    /** 平台仍在评测(收到非终态 check 快照即发射一次)。 */
    data object Pending : TestRunStatus

    /** 评测完成(平台侧 state 到达终态),结果见 [result]。 */
    data class Finished(
        val result: TestRunResult,
    ) : TestRunStatus
}

/**
 * 云端测试结果(WP53;字段名 = 旧 LeetCodeRunResult.Success 事实的平台无关子集)。
 * 平台拉不到的字段如实为 null/空,不造。
 */
@Serializable
data class TestRunResult(
    /** 通过用例数(total_correct)。 */
    val correctCount: Int? = null,
    /** 总用例数(total_testcases)。 */
    val totalCount: Int? = null,
    /** 逐用例实际输出(code_answer)。 */
    val codeAnswers: List<String> = emptyList(),
    /** 逐用例期望输出(expected_code_answer)。 */
    val expectedCodeAnswers: List<String> = emptyList(),
    /** 逐用例比对位图原文,如 "1101"(compare_result,1=过 0=挂)。 */
    val compareResult: String? = null,
    /** 耗时原文,如 "0 ms"(status_runtime)。 */
    val statusRuntime: String? = null,
    /** 内存原文,如 "41.2 MB"(status_memory,注意 interpret 与提交的 memory 形态不同)。 */
    val statusMemory: String? = null,
    /** 编译错误全文(full_compile_error 优先,回退 compile_error)。 */
    val compileError: String? = null,
    /** 运行错误全文(full_runtime_error 优先,回退 runtime_error)。 */
    val runtimeError: String? = null,
    /** 平台侧终态原文(如 "SUCCESS")。 */
    val state: String = "",
)
