package com.codequest.infra.submission

import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import com.codequest.infra.db.ExposedRemoteSubmissionRepository
import com.codequest.spi.OJPlatform
import com.codequest.spi.SubmissionHistoryCapable

/**
 * 站内提交历史同步用例(1.7.0 F2,spec §2.2):按 offset 倒序分页拉取站内提交,
 * 逐条 upsert 落 `remote_submission` 表。
 *
 * **增量语义(遇已存即停)**:列表全局倒序(新在前);某页出现已存在条目时,先把
 * 该页剩余新条全部入库(insertIgnore 对已存条目本就是 no-op)再停——页内新旧混排
 * (remoteId 非严格单调)不会漏新条;停后不再拉下一页。
 *
 * **上限保护**:单次同步最多 [maxPages] 页(默认 200 页 × 20 条 = 4000 条),
 * 防御站端 hasMore 异常导致的失控循环,触顶以 [SyncResult.StoppedBy.LIMIT] 如实上报。
 *
 * pooled 线程执行由 REST 层([com.codequest.infra.messaging.ResourceHttpServer]
 * 工作线程)负责;本类纯 suspend,无并发自保护(同步入口串行,由调用方保证)。
 *
 * @param pageSize 分页步长(与站内一致 20)
 * @param maxPages 单次同步页数上限
 */
class SubmissionHistorySyncService(
    private val platforms: () -> List<OJPlatform>,
    private val remoteSubmissions: () -> ExposedRemoteSubmissionRepository,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = MAX_PAGES,
) {
    /** 同步结果;[total] 为同步完成后该平台站内历史总条数(含历史存量)。 */
    data class SyncResult(
        val synced: Int,
        val total: Int,
        val stoppedBy: StoppedBy,
    )

    /** 停止原因:遇已存条目(增量到头)/ 页数上限(保护触发)/ 自然到底(hasMore=false)。 */
    enum class StoppedBy {
        EXISTING,
        LIMIT,
        END,
    }

    /**
     * 同步指定平台的站内提交历史。
     *
     * @throws IllegalArgumentException 平台未注册或不具备 [SubmissionHistoryCapable] 能力
     *         (REST 层先行校验,此处兜底同一语义)
     */
    suspend fun sync(platformId: PlatformId): SyncResult {
        val capable =
            platforms().firstOrNull { it.id == platformId } as? SubmissionHistoryCapable
                ?: throw IllegalArgumentException(
                    "no submission-history-capable platform registered for id '${platformId.value}'",
                )
        var synced = 0
        var stoppedBy = StoppedBy.END
        var lastPageHadMore = false
        search@
        for (pageIndex in 0 until maxPages) {
            val page = capable.fetchSubmissionHistory(offset = pageIndex * pageSize, limit = pageSize)
            lastPageHadMore = page.hasMore
            var metExisting = false
            for (submission in page.submissions) {
                // upsert 返回 false = (platform_id, remote_id) 已存在 → 记命中,继续吃完整页
                val inserted = remoteSubmissions().upsert(submission.toRecord(platformId))
                if (inserted) {
                    synced++
                } else {
                    metExisting = true
                }
            }
            // 遇已存(增量到头)或自然到底即停;否则继续下一页
            val reachedEnd = metExisting || !page.hasMore
            if (reachedEnd) {
                stoppedBy = if (metExisting) StoppedBy.EXISTING else StoppedBy.END
                break@search
            }
        }
        // 循环耗尽 maxPages 页且最后一页仍 hasMore → LIMIT 保护触发
        if (stoppedBy == StoppedBy.END && lastPageHadMore) {
            stoppedBy = StoppedBy.LIMIT
        }
        val total = remoteSubmissions().countByPlatform(platformId).toInt()
        return SyncResult(synced = synced, total = total, stoppedBy = stoppedBy)
    }

    private fun com.codequest.spi.RemoteSubmission.toRecord(platformId: PlatformId) =
        RemoteSubmissionRecord(
            platformId = platformId,
            remoteId = remoteId,
            titleSlug = titleSlug,
            title = title,
            statusDisplay = statusDisplay,
            lang = lang,
            runtimeMs = runtimeMs,
            memoryKb = memoryKb,
            submittedAt = submittedAt,
        )

    companion object {
        /** 分页步长,与站内提交页一致。 */
        const val DEFAULT_PAGE_SIZE = 20

        /** 单次同步页数上限(200 × 20 = 4000 条)。 */
        const val MAX_PAGES = 200
    }
}
