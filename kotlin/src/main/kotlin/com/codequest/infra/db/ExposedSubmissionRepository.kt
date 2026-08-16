package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.repository.SubmissionRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * SubmissionRepository 的 Exposed/SQLite 实现(WP14)。
 *
 * - verdict 列存 SubmissionResult 多态 JSON([DbCodecs]);
 * - findByChallenge 按 submitted_at 倒序(固定宽度 ISO 字符串,字典序即时序),
 *   同刻提交按 id 升序稳定 tie-break(与内存假实现契约一致,对照单测守护)。
 */
class ExposedSubmissionRepository(
    private val db: CodeQuestDatabase,
) : SubmissionRepository {
    override suspend fun save(submission: Submission) =
        db.write {
            Submissions.deleteWhere { Submissions.id eq submission.id.value }
            Submissions.insert {
                it[id] = submission.id.value
                it[challengeId] = submission.challengeId.value
                it[platformId] = submission.platformId.value
                it[remoteSubmissionId] = submission.remoteSubmissionId
                it[language] = submission.language
                it[codeSnapshot] = submission.codeSnapshot
                it[verdict] = DbCodecs.encodeVerdict(submission.verdict)
                it[timeMs] = submission.timeMs
                it[memoryKb] = submission.memoryKb
                it[submittedAt] = DbCodecs.formatInstant(submission.submittedAt)
            }
            Unit
        }

    override suspend fun findById(id: SubmissionId): Submission? =
        db.read {
            Submissions
                .selectAll()
                .where { Submissions.id eq id.value }
                .firstOrNull()
                ?.toSubmission()
        }

    override suspend fun findByChallenge(challengeId: ChallengeId): List<Submission> =
        db.read {
            Submissions
                .selectAll()
                .where { Submissions.challengeId eq challengeId.value }
                .orderBy(Submissions.submittedAt to SortOrder.DESC, Submissions.id to SortOrder.ASC)
                .map { it.toSubmission() }
        }

    /**
     * 分页列表(WP21,submissions 端点数据源):排序契约与 [findByChallenge] 一致;
     * [challengeId] 为 null 时列出全部工作区的提交。
     */
    suspend fun listPage(
        challengeId: ChallengeId?,
        limit: Int,
        offset: Int,
    ): List<Submission> =
        db.read {
            val query =
                if (challengeId == null) {
                    Submissions.selectAll()
                } else {
                    Submissions.selectAll().where { Submissions.challengeId eq challengeId.value }
                }
            query
                .orderBy(Submissions.submittedAt to SortOrder.DESC, Submissions.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toSubmission() }
        }
}

private fun ResultRow.toSubmission() =
    Submission(
        id = SubmissionId(this[Submissions.id]),
        challengeId = ChallengeId(this[Submissions.challengeId]),
        platformId = PlatformId(this[Submissions.platformId]),
        remoteSubmissionId = this[Submissions.remoteSubmissionId],
        language = this[Submissions.language],
        codeSnapshot = this[Submissions.codeSnapshot],
        verdict = DbCodecs.decodeVerdict(this[Submissions.verdict]),
        timeMs = this[Submissions.timeMs],
        memoryKb = this[Submissions.memoryKb],
        submittedAt = DbCodecs.parseInstant(this[Submissions.submittedAt]),
    )
