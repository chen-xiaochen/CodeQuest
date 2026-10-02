package com.codequest.infra.db

import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 站内提交历史仓库(1.7.0 F2,WP59),对应表 `remote_submission`。
 *
 * - upsert 为 INSERT OR IGNORE:`UNIQUE(platform_id, remote_id)` 冲突时静默跳过
 *   (站内历史只增不改),返回是否真正插入——增量同步据此判定「已拉到旧数据」;
 * - listPage 按 submitted_at 倒序(固定宽度 ISO,字典序即时序),同刻按 id 升序
 *   稳定 tie-break(与 [ExposedSubmissionRepository.listPage] 契约一致)。
 */
class ExposedRemoteSubmissionRepository(
    private val db: CodeQuestDatabase,
) {
    /** 插入记录,`(platform_id, remote_id)` 冲突时跳过;返回 false = 已存在未插入。 */
    suspend fun upsert(record: RemoteSubmissionRecord): Boolean =
        db.write {
            RemoteSubmissions
                .insertIgnore {
                    it[platformId] = record.platformId.value
                    it[remoteId] = record.remoteId
                    it[titleSlug] = record.titleSlug
                    it[title] = record.title
                    it[statusDisplay] = record.statusDisplay
                    it[lang] = record.lang
                    it[runtimeMs] = record.runtimeMs
                    it[memoryKb] = record.memoryKb
                    it[submittedAt] = DbCodecs.formatInstant(record.submittedAt)
                }.insertedCount > 0
        }

    /** 增量同步停止判定:`(platform_id, remote_id)` 是否已存在。 */
    suspend fun exists(
        platformId: PlatformId,
        remoteId: String,
    ): Boolean =
        db.read {
            RemoteSubmissions
                .selectAll()
                .where { (RemoteSubmissions.platformId eq platformId.value) and (RemoteSubmissions.remoteId eq remoteId) }
                .limit(1)
                .any()
        }

    /**
     * 分页列表(submissions 端点数据源):按 submitted_at 倒序;[platformId] 为 null
     * 时跨平台列出全部。
     */
    suspend fun listPage(
        platformId: PlatformId?,
        limit: Int,
        offset: Int,
    ): List<RemoteSubmissionRecord> =
        db.read {
            val query =
                if (platformId == null) {
                    RemoteSubmissions.selectAll()
                } else {
                    RemoteSubmissions.selectAll().where { RemoteSubmissions.platformId eq platformId.value }
                }
            query
                .orderBy(RemoteSubmissions.submittedAt to SortOrder.DESC, RemoteSubmissions.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toRemoteSubmission() }
        }

    suspend fun countByPlatform(platformId: PlatformId): Long =
        db.read {
            RemoteSubmissions
                .selectAll()
                .where { RemoteSubmissions.platformId eq platformId.value }
                .count()
        }
}

private fun ResultRow.toRemoteSubmission() =
    RemoteSubmissionRecord(
        platformId = PlatformId(this[RemoteSubmissions.platformId]),
        remoteId = this[RemoteSubmissions.remoteId],
        titleSlug = this[RemoteSubmissions.titleSlug],
        title = this[RemoteSubmissions.title],
        statusDisplay = this[RemoteSubmissions.statusDisplay],
        lang = this[RemoteSubmissions.lang],
        runtimeMs = this[RemoteSubmissions.runtimeMs],
        memoryKb = this[RemoteSubmissions.memoryKb],
        submittedAt = DbCodecs.parseInstant(this[RemoteSubmissions.submittedAt]),
    )
