package com.codequest.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 提交判定结果(WP13)。
 *
 * 平台无关的 sealed 判定体系,覆盖 AC/WA/TLE/MLE/OLE/RE/CE/部分分/Pending/Judging
 * 及平台私有判定的 [Unknown] 兜底。
 *
 * 语义契约(被消费方依赖):
 * - [isTerminal]:判定是否不会再变化。[Pending]/[Judging] 为非终态,其余均为终态
 * - [isSuccessful]:是否完全通过,仅 [Accepted] 为 true([PartialScore] 不算完全通过)
 *
 * JSON 多态序列化:discriminator 字段 `type`,取值为各子类 [SerialName](前端 webview 按此分发渲染)。
 */
@Serializable
sealed class SubmissionResult {
    /** 判定是否为终态(不会再变化)。 */
    val isTerminal: Boolean
        get() = this !is Pending && this !is Judging

    /** 是否完全通过(仅 AC)。 */
    val isSuccessful: Boolean
        get() = this is Accepted

    /** 已提交,等待平台开始评测。非终态。 */
    @Serializable
    @SerialName("Pending")
    data object Pending : SubmissionResult()

    /** 平台评测中。非终态。 */
    @Serializable
    @SerialName("Judging")
    data object Judging : SubmissionResult()

    /** 完全通过。 */
    @Serializable
    @SerialName("Accepted")
    data object Accepted : SubmissionResult()

    /** 答案错误。 */
    @Serializable
    @SerialName("WrongAnswer")
    data object WrongAnswer : SubmissionResult()

    /** 超出时间限制。 */
    @Serializable
    @SerialName("TimeLimitExceeded")
    data object TimeLimitExceeded : SubmissionResult()

    /** 超出内存限制。 */
    @Serializable
    @SerialName("MemoryLimitExceeded")
    data object MemoryLimitExceeded : SubmissionResult()

    /** 超出输出限制。 */
    @Serializable
    @SerialName("OutputLimitExceeded")
    data object OutputLimitExceeded : SubmissionResult()

    /** 运行时错误。 */
    @Serializable
    @SerialName("RuntimeError")
    data object RuntimeError : SubmissionResult()

    /** 编译错误。 */
    @Serializable
    @SerialName("CompileError")
    data object CompileError : SubmissionResult()

    /** 部分通过(得部分分);[score] 为得分比例,范围 (0, 1)。 */
    @Serializable
    @SerialName("PartialScore")
    data class PartialScore(
        val score: Double,
    ) : SubmissionResult()

    /** 平台私有/无法映射的判定,[raw] 保留平台原文。终态。 */
    @Serializable
    @SerialName("Unknown")
    data class Unknown(
        val raw: String,
    ) : SubmissionResult()
}
