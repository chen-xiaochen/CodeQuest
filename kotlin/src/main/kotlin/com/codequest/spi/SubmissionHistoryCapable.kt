package com.codequest.spi

import com.codequest.core.model.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 站内提交历史查询能力(1.7.0 F2):平台适配器按需实现——按用户全局倒序分页
 * 拉取站内提交(不依赖本地 challenge,覆盖插件外发起的提交)。
 *
 * 仅 OJ 站内暴露全局提交历史的平台实现(LeetCode 两变体);其余平台不实现,
 * REST `POST /api/submissions/sync` 对不支持平台回 400。查询异常照实抛领域
 * 异常(消费方 [com.codequest.infra.submission.SubmissionHistorySyncService] 映射 502)。
 */
interface SubmissionHistoryCapable {
    /**
     * 拉一页站内提交(全局倒序,offset/limit 分页)。
     *
     * @return [RemoteSubmissionPage.hasMore] = false 即最后一页(协议上对应
     *         站点分页游标 lastKey 为 null)。
     */
    suspend fun fetchSubmissionHistory(
        offset: Int,
        limit: Int,
    ): RemoteSubmissionPage
}

/** 站内提交分页结果(游标语义由 [hasMore] 承载,游标本体不外泄)。 */
@Serializable
data class RemoteSubmissionPage(
    val submissions: List<RemoteSubmission>,
    /** false = 已到最后一页。 */
    val hasMore: Boolean,
)

/** 站内提交条目(字段与 `remote_submission` 表对齐,见 [com.codequest.core.model.RemoteSubmissionRecord])。 */
@Serializable
data class RemoteSubmission(
    /** 平台侧提交 id(LC submissionId)。 */
    val remoteId: String,
    val titleSlug: String,
    val title: String,
    /** 站内判定文案,AC 判定 = "AC"(站方 "Accepted" 等价形态由平台层归一)。 */
    val statusDisplay: String,
    /** 提交语言(站内名称,如 "kotlin")。 */
    val lang: String,
    /** 耗时(毫秒);站方未给出/解析失败为 null。 */
    val runtimeMs: Int? = null,
    /** 内存(KB);站方未给出/解析失败为 null。 */
    val memoryKb: Int? = null,
    @Serializable(with = InstantSerializer::class)
    val submittedAt: Instant,
)
