package com.codequest.infra.db

import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import com.codequest.core.repository.ContestReminderRepository
import com.codequest.core.repository.TagRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** TagRepository 的 Exposed/SQLite 实现(WP14)。 */
class ExposedTagRepository(
    private val db: CodeQuestDatabase,
) : TagRepository {
    override suspend fun save(tag: Tag) =
        db.write {
            Tags.deleteWhere { Tags.id eq tag.id.value }
            Tags.insert {
                it[id] = tag.id.value
                it[name] = tag.name
            }
            Unit
        }

    override suspend fun findById(id: TagId): Tag? =
        db.read {
            Tags
                .selectAll()
                .where { Tags.id eq id.value }
                .firstOrNull()
                ?.toTag()
        }

    override suspend fun findByName(name: String): Tag? =
        db.read {
            Tags
                .selectAll()
                .where { Tags.name eq name }
                .limit(1)
                .firstOrNull()
                ?.toTag()
        }

    override suspend fun findAll(): List<Tag> =
        db.read {
            Tags
                .selectAll()
                .orderBy(Tags.name to SortOrder.ASC)
                .map { it.toTag() }
        }

    override suspend fun findByProblem(problemId: ProblemId): List<Tag> =
        db.read {
            ProblemTags
                .join(
                    Tags,
                    JoinType.INNER,
                    onColumn = ProblemTags.tagId,
                    otherColumn = Tags.id,
                ).selectAll()
                .where { ProblemTags.problemId eq problemId.value }
                .orderBy(ProblemTags.position to SortOrder.ASC)
                .map { it.toTag() }
        }
}

private fun ResultRow.toTag() = Tag(TagId(this[Tags.id]), this[Tags.name])

/** ContestReminderRepository 的 Exposed/SQLite 实现(WP14)。 */
class ExposedContestReminderRepository(
    private val db: CodeQuestDatabase,
) : ContestReminderRepository {
    override suspend fun save(reminder: ContestReminder) =
        db.write {
            // upsert 以 contest_id 为键:每场竞赛至多一条
            ContestReminders.deleteWhere { ContestReminders.contestId eq reminder.contestId.value }
            ContestReminders.insert {
                it[contestId] = reminder.contestId.value
                it[remindAt] = DbCodecs.formatInstant(reminder.remindAt)
                it[notified] = reminder.notified
            }
            Unit
        }

    override suspend fun findByContest(contestId: ContestId): ContestReminder? =
        db.read {
            ContestReminders
                .selectAll()
                .where { ContestReminders.contestId eq contestId.value }
                .firstOrNull()
                ?.toReminder()
        }

    override suspend fun findDue(now: Instant): List<ContestReminder> =
        db.read {
            // remind_at 为固定宽度 ISO 字符串,字典序比较即时序
            ContestReminders
                .selectAll()
                .where { (ContestReminders.remindAt lessEq DbCodecs.formatInstant(now)) and (ContestReminders.notified eq false) }
                .orderBy(ContestReminders.remindAt to SortOrder.ASC)
                .map { it.toReminder() }
        }

    override suspend fun markNotified(contestId: ContestId) =
        db.write {
            ContestReminders.update({ ContestReminders.contestId eq contestId.value }) {
                it[notified] = true
            }
            Unit
        }

    override suspend fun delete(contestId: ContestId): Boolean =
        db.write {
            ContestReminders.deleteWhere { ContestReminders.contestId eq contestId.value } > 0
        }
}

private fun ResultRow.toReminder() =
    ContestReminder(
        contestId = ContestId(this[ContestReminders.contestId]),
        remindAt = DbCodecs.parseInstant(this[ContestReminders.remindAt]),
        notified = this[ContestReminders.notified],
    )
