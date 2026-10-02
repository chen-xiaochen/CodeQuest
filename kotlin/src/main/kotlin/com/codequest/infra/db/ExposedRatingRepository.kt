package com.codequest.infra.db

import com.codequest.core.model.ProblemRating
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 难度评分仓库(1.7.0 F1,WP59),对应表 `problem_rating`。
 *
 * upsertAll 为全量覆盖式:同 frontend_id 更新为最新值(先删后插,照
 * [ExposedSubmissionRepository.save] 的 upsert 先例,单事务内完成);不在本次
 * 数据内的既有行保留(zerotrac 为全量快照,刷新语义按 key 覆盖即可)。
 */
class ExposedRatingRepository(
    private val db: CodeQuestDatabase,
) {
    /** 全量覆盖式 upsert:同 frontend_id 更新,批量单事务。 */
    suspend fun upsertAll(ratings: List<ProblemRating>) =
        db.write {
            if (ratings.isNotEmpty()) {
                ProblemRatings.deleteWhere { ProblemRatings.frontendId inList ratings.map { it.frontendId } }
                ratings.forEach { rating ->
                    ProblemRatings.insert {
                        it[frontendId] = rating.frontendId
                        it[ProblemRatings.rating] = rating.rating
                        it[updatedAt] = DbCodecs.formatInstant(rating.updatedAt)
                    }
                }
            }
            Unit
        }

    /** 全表装载为 `frontend_id → rating` 内存映射(RatingService 启动加载用)。 */
    suspend fun loadAll(): Map<String, Double> =
        db.read {
            ProblemRatings
                .selectAll()
                .associate { it[ProblemRatings.frontendId] to it[ProblemRatings.rating] }
        }

    suspend fun count(): Long =
        db.read {
            ProblemRatings.selectAll().count()
        }
}
