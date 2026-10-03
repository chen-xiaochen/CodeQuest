package com.codequest.infra.db

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import com.codequest.core.repository.ProblemRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.SQLException

/** ProblemRepository 的 Exposed/SQLite 实现(WP14;WP20 追加实现 [ProblemStore] 端点端口)。 */
class ExposedProblemRepository(
    private val db: CodeQuestDatabase,
) : ProblemRepository,
    ProblemStore {
    override suspend fun save(problem: Problem) =
        db.write {
            // upsert = 全量替换(先清主档与关联,再写入;标签随 problem_tag 一并落库)
            Problems.deleteWhere { id eq problem.id.value }
            ProblemTags.deleteWhere { problemId eq problem.id.value }
            Problems.insert {
                it[id] = problem.id.value
                it[platformId] = problem.platformId.value
                it[remoteId] = problem.remoteId
                it[title] = problem.title
                it[secondaryTitle] = problem.titleSecondary
                it[difficulty] = problem.difficulty.value
                it[url] = problem.url
                it[fetchedAt] = DbCodecs.formatInstant(problem.fetchedAt)
            }
            problem.tags.forEachIndexed { index, tag ->
                // 1.6.0:富信息(slug/中文名)为 null 时保留库内已有值——tag 行跨平台
                // 共享(如 COM 数据先建行、CN 数据后 enrich),全量替换会丢 enrichment
                val existing =
                    Tags
                        .selectAll()
                        .where { Tags.id eq tag.id.value }
                        .firstOrNull()
                Tags.deleteWhere { id eq tag.id.value }
                Tags.insert {
                    it[id] = tag.id.value
                    it[name] = tag.name
                    it[slug] = tag.slug ?: existing?.get(Tags.slug)
                    it[nameCn] = tag.nameCn ?: existing?.get(Tags.nameCn)
                }
                ProblemTags.insert {
                    it[problemId] = problem.id.value
                    it[tagId] = tag.id.value
                    it[position] = index
                }
            }
            Unit
        }

    override suspend fun findById(id: ProblemId): Problem? =
        db.read {
            Problems
                .selectAll()
                .where { Problems.id eq id.value }
                .firstOrNull()
                ?.toProblem()
                ?.withTags(tagsFor(id.value))
        }

    override suspend fun search(
        platformId: PlatformId,
        keyword: String,
    ): List<Problem> {
        val candidateIds = searchCandidateIds(platformId.value, keyword)
        if (candidateIds.isEmpty()) return emptyList()
        return db.read {
            Problems
                .selectAll()
                .where { (Problems.platformId eq platformId.value) and (Problems.id inList candidateIds) }
                .map { it.toProblem() }
                .map { problem -> problem.withTags(tagsFor(problem.id.value)) }
        }
    }

    override suspend fun findByRemoteId(
        platformId: PlatformId,
        remoteId: String,
    ): Problem? =
        db.read {
            Problems
                .selectAll()
                .where { (Problems.platformId eq platformId.value) and (Problems.remoteId eq remoteId) }
                .firstOrNull()
                ?.let { row -> row.toProblem().withTags(tagsFor(row[Problems.id])) }
        }

    override suspend fun findByTag(tagId: TagId): List<Problem> =
        db.read {
            val problemIds =
                ProblemTags
                    .selectAll()
                    .where { ProblemTags.tagId eq tagId.value }
                    .map { it[ProblemTags.problemId] }
            if (problemIds.isEmpty()) return@read emptyList()
            Problems
                .selectAll()
                .where { Problems.id inList problemIds }
                .map { it.toProblem() }
                .map { problem -> problem.withTags(tagsFor(problem.id.value)) }
        }

    override suspend fun saveDetail(detail: ProblemDetail) =
        db.write {
            // 删除 + 重插(而非 UPDATE):触发器路径确定,AI/AD 触发器同步 FTS 索引
            ProblemContents.deleteWhere { problemId eq detail.problemId.value }
            ProblemContents.insert {
                it[problemId] = detail.problemId.value
                it[contentMd] = detail.contentMd
                it[contentFormat] = detail.contentFormat.name
                it[questionId] = detail.questionId
            }
            Unit
        }

    override suspend fun findDetail(problemId: ProblemId): ProblemDetail? =
        db.read {
            ProblemContents
                .selectAll()
                .where { ProblemContents.problemId eq problemId.value }
                .firstOrNull()
                ?.let { row ->
                    ProblemDetail(
                        problemId = ProblemId(row[ProblemContents.problemId]),
                        contentMd = row[ProblemContents.contentMd],
                        contentFormat = ContentFormat.valueOf(row[ProblemContents.contentFormat]),
                        questionId = row[ProblemContents.questionId],
                    )
                }
        }

    // ------------------------------------------------------------------
    // 内部:候选 id 检索(FTS5 优先 + LIKE 降级)与标签装载
    // ------------------------------------------------------------------

    /**
     * 命中题目的 id:标题/remoteId LIKE + 题面内容全文匹配。
     *
     * 全文匹配优先走 problem_content_fts(trigram);关键字不足 3 字符或 FTS 查询抛
     * [SQLException](虚拟表缺失/语法异常)时,题面列并入 LIKE 子串降级。
     */
    private suspend fun searchCandidateIds(
        platform: String,
        keyword: String,
    ): List<String> {
        val like = FtsSupport.likePattern(keyword)
        val baseCondition = "p.platform_id = ? AND (p.title LIKE ? ESCAPE '\\' OR p.dojo_problem_id LIKE ? ESCAPE '\\'"
        if (keyword.length >= FtsSupport.MIN_TRIGRAM_CHARS) {
            try {
                return db.rawQuery(
                    "SELECT p.id FROM problem p WHERE $baseCondition OR p.id IN " +
                        "(SELECT problem_id FROM problem_content_fts WHERE problem_content_fts MATCH ?))",
                    platform,
                    like,
                    like,
                    FtsSupport.matchExpression(keyword),
                ) { it.getString(1) }
            } catch (_: SQLException) {
                // FTS5 不可用/查询异常 → 整体降级 LIKE
            }
        }
        return db.rawQuery(
            "SELECT p.id FROM problem p WHERE $baseCondition OR p.id IN " +
                "(SELECT problem_id FROM problem_content WHERE content_md LIKE ? ESCAPE '\\'))",
            platform,
            like,
            like,
            like,
        ) { it.getString(1) }
    }

    /** 某题的标签,按 problem_tag.position 排序(保证 save→findById 往返顺序稳定)。 */
    private fun tagsFor(problemId: String): List<Tag> =
        ProblemTags
            .join(
                Tags,
                JoinType.INNER,
                onColumn = ProblemTags.tagId,
                otherColumn = Tags.id,
            ).selectAll()
            .where { ProblemTags.problemId eq problemId }
            .orderBy(ProblemTags.position to SortOrder.ASC)
            .map { row ->
                Tag(TagId(row[Tags.id]), row[Tags.name], row[Tags.slug], row[Tags.nameCn])
            }
}

private fun ResultRow.toProblem() =
    Problem(
        id = ProblemId(this[Problems.id]),
        platformId = PlatformId(this[Problems.platformId]),
        remoteId = this[Problems.remoteId],
        title = this[Problems.title],
        titleSecondary = this[Problems.secondaryTitle],
        difficulty = Difficulty(this[Problems.difficulty]),
        url = this[Problems.url],
        tags = emptyList(),
        fetchedAt = DbCodecs.parseInstant(this[Problems.fetchedAt]),
    )

private fun Problem.withTags(tags: List<Tag>): Problem = if (tags.isEmpty()) this else copy(tags = tags)
