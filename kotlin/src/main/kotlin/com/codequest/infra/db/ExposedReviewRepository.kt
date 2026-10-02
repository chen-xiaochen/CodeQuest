package com.codequest.infra.db

import com.codequest.core.model.ProblemId
import com.codequest.core.model.ReviewSchedule
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 间隔重复复习排期仓库(1.7.0 F3,WP59),对应表 `review_schedule`。
 *
 * plan/history 两列存 epoch 秒数组 JSON([DbCodecs.encodeEpochSeconds]);
 * upsert 为先删后插(照 [ExposedSubmissionRepository.save] 先例,单事务)。
 */
class ExposedReviewRepository(
    private val db: CodeQuestDatabase,
) {
    /** 新增或更新某题的复习排期(排期由 ReviewService 计算,仓库只负责整行落库)。 */
    suspend fun upsert(record: ReviewSchedule) =
        db.write {
            ReviewSchedules.deleteWhere { ReviewSchedules.problemId eq record.problemId.value }
            ReviewSchedules.insert {
                it[problemId] = record.problemId.value
                it[planDates] = DbCodecs.encodeEpochSeconds(record.planDates)
                it[historyDates] = DbCodecs.encodeEpochSeconds(record.historyDates)
                it[updatedAt] = DbCodecs.formatInstant(record.updatedAt)
            }
            Unit
        }

    suspend fun findByProblem(problemId: ProblemId): ReviewSchedule? =
        db.read {
            ReviewSchedules
                .selectAll()
                .where { ReviewSchedules.problemId eq problemId.value }
                .firstOrNull()
                ?.toReviewSchedule()
        }

    /** 全部排期(REST `/api/review/today` 分组装配用)。 */
    suspend fun listAll(): List<ReviewSchedule> =
        db.read {
            ReviewSchedules
                .selectAll()
                .map { it.toReviewSchedule() }
        }
}

private fun ResultRow.toReviewSchedule() =
    ReviewSchedule(
        problemId = ProblemId(this[ReviewSchedules.problemId]),
        planDates = DbCodecs.decodeEpochSeconds(this[ReviewSchedules.planDates]),
        historyDates = DbCodecs.decodeEpochSeconds(this[ReviewSchedules.historyDates]),
        updatedAt = DbCodecs.parseInstant(this[ReviewSchedules.updatedAt]),
    )
