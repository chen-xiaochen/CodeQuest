package com.codequest.spi

import com.codequest.core.model.SubmissionResult
import kotlinx.serialization.Serializable

/**
 * 提交详情拉取能力(WP36,M7-A):平台适配器按需实现。
 *
 * 原版事实(旧 Scala `toolwindows/sidebar/submissionlog/` 各平台 Form):
 * - LeetCode 系(LeetCodeWrongAnswerResultForm + submissionDetails 数据):
 *   错误用例三件套(input_formatted/code_output/expected_output)+ 判定/耗时/
 *   内存/语言(pretty_lang)/编译与运行错误全文/百分位;
 * - HackerRank(HackerRankSubmissionResultForm):用例通过计数(testcase_status
 *   列表中 1 的个数/总数)+ 编译错误信息;
 * - CodeForces/AtCoder/洛谷:详情区仅有本地数据(判定/时间/语言/代码),平台侧
 *   无补充字段;平台侧出口是「View in browser」按钮(浏览器兜底)。
 *
 * ## 设计取舍(WP36,相对「默认抛 OJException」建议的两处偏离与理由)
 *
 * 1. **签名带 problemRemoteId 而非只有 remoteSubmissionId**:HackerRank 的详情
 *    端点与各平台浏览器 URL 寻址需要题目标识(旧实现提交时闭包持有,重写版
 *    提交后重启即失——端点侧从 challenge.problemId 反解后传入);LeetCode 系
 *    可忽略该参数。
 * 2. **返回可空 [SubmissionDetail] 而非默认抛异常**:照
 *    SolutionViewCapable.fetchSolutionContent 先例本可默认抛 OJException,但
 *    「平台无补充详情」对 CF/AtCoder/洛谷是旧 Form 的既成事实而非错误——若当
 *    异常抛,端点按既有模式映射 502 后这三平台的详情面板将永远报错。null 语义
 *    = 平台如实申报"无补充";真正的失败(登录失效/反爬/网络)仍抛 core 领域
 *    异常,由端点按既有模式映射 HTTP 状态。
 */
interface SubmissionDetailCapable {
    /**
     * 拉取单次提交的平台侧详情(轮询判定快照之外的增强数据,按需低频调用,不落库)。
     *
     * 平台无补充详情时返回 null(非错误);网络/鉴权失败抛 core 领域异常。
     * [problemRemoteId] 与 [ProblemSummary.remoteId] 同源。
     */
    suspend fun fetchSubmissionDetail(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): SubmissionDetail? = null

    /**
     * 该提交在平台网站的详情页 URL(旧 Form 的 View in browser 兜底出口,
     * 形态为 `https://<domain>/...`)。无法从参数推导(题目标识缺失/不可解析)
     * 时返回 null;不得抛异常。
     */
    fun submissionViewUrl(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): String? = null
}

/** 错误用例三件套(旧 LeetCodeWrongAnswerResultForm 的事实字段,WP36)。 */
@Serializable
data class SubmissionErrorCase(
    /** 失败用例输入(LC `input_formatted`,缺格式化版回退 `input`)。 */
    val input: String? = null,
    /** 期望输出(LC `expected_output`)。 */
    val expectedOutput: String? = null,
    /** 实际输出(LC `code_output`)。 */
    val actualOutput: String? = null,
)

/**
 * 平台侧提交详情(WP36):verdict/timeMs/memoryKb/language 为平台侧最新值
 * (调用方以其覆盖本地库快照展示),错误用例与平台特有展示字段见其余成员;
 * 平台拉不到的字段如实为 null,不造。
 */
@Serializable
data class SubmissionDetail(
    /** 平台侧判定(重拉可刷新本地库中的陈旧值);平台不复判时为 null。 */
    val verdict: SubmissionResult? = null,
    val timeMs: Int? = null,
    val memoryKb: Int? = null,
    /** 平台侧语言展示名(如 LC `pretty_lang`)。 */
    val language: String? = null,
    /** 平台侧错误/提示原文(如 LC 编译错误全文、HR compile_message)。 */
    val message: String? = null,
    val errorCase: SubmissionErrorCase? = null,
    /**
     * 平台特有键值对(键为稳定契约,前端原样展示)。已约定的键:
     * - `runtimePercentile` / `memoryPercentile`(LC,如 "95.20%");
     * - `testcases`(LC total_correct/total_testcases、HR 通过数/总数,如 "12/14")。
     */
    val extras: Map<String, String> = emptyMap(),
)
