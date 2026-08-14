package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Solution
import com.codequest.core.model.SolutionId
import com.codequest.core.repository.SolutionRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * SolutionRepository 的 Exposed/SQLite 实现(WP14)。
 *
 * note_md 的 FTS5 索引(solution_note_fts)由表触发器自动同步——save/delete 走
 * 行级删除 + 重插,触发器路径确定;全文检索入口见 [SolutionNoteSearch]。
 */
class ExposedSolutionRepository(
    private val db: CodeQuestDatabase,
) : SolutionRepository {
    override suspend fun save(solution: Solution) =
        db.write {
            Solutions.deleteWhere { Solutions.id eq solution.id.value }
            Solutions.insert {
                it[id] = solution.id.value
                it[challengeId] = solution.challengeId.value
                it[name] = solution.name
                it[noteMd] = solution.noteMd
                it[filePath] = solution.filePath
                it[createdAt] = DbCodecs.formatInstant(solution.createdAt)
            }
            Unit
        }

    override suspend fun findById(id: SolutionId): Solution? =
        db.read {
            Solutions
                .selectAll()
                .where { Solutions.id eq id.value }
                .firstOrNull()
                ?.toSolution()
        }

    override suspend fun findByChallenge(challengeId: ChallengeId): List<Solution> =
        db.read {
            Solutions
                .selectAll()
                .where { Solutions.challengeId eq challengeId.value }
                .orderBy(Solutions.createdAt to SortOrder.ASC, Solutions.id to SortOrder.ASC)
                .map { it.toSolution() }
        }

    override suspend fun delete(id: SolutionId): Boolean =
        db.write {
            Solutions.deleteWhere { Solutions.id eq id.value } > 0
        }
}

private fun ResultRow.toSolution() =
    Solution(
        id = SolutionId(this[Solutions.id]),
        challengeId = ChallengeId(this[Solutions.challengeId]),
        name = this[Solutions.name],
        noteMd = this[Solutions.noteMd],
        filePath = this[Solutions.filePath],
        createdAt = DbCodecs.parseInstant(this[Solutions.createdAt]),
    )
