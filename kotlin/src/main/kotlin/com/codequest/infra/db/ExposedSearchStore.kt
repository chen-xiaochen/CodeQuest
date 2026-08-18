package com.codequest.infra.db

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import java.sql.ResultSet
import java.sql.SQLException

/**
 * [SearchStore] 的 Exposed/SQLite 实现(WP30)。
 *
 * 检索分两段:先按关键字取三源命中 id(标题/remoteId LIKE、题面 FTS5+降级、
 * 题解笔记经 [SolutionNoteSearch] + challenge 映射),Kotlin 侧聚合出
 * `题目 id → 命中来源集合`;再以动态 WHERE(platform = ? / id IN / 标签 AND
 * 子查询)过滤主档并分页。全走 [CodeQuestDatabase.rawQuery] 参数化查询
 * (命中 id 集合与 limit/offset 为服务端生成的确定值,内插安全;标签名
 * 一律参数化)。
 */
class ExposedSearchStore(
    private val db: CodeQuestDatabase,
) : SearchStore {
    override suspend fun search(request: SearchRequest): SearchPage {
        val keyword = request.keyword?.takeIf { it.isNotBlank() }
        val sourcesByProblem = keyword?.let { matchSources(it) }.orEmpty()
        // 有关键字但三源零命中 → 空结果(不得退化为浏览全表)
        if (keyword != null && sourcesByProblem.isEmpty()) return SearchPage(0, emptyList())

        val (whereSql, args) = buildWhere(keyword, sourcesByProblem.keys, request.tags, request.platform)
        val total =
            query(
                "SELECT COUNT(*) FROM problem p $whereSql",
                args,
            ) { it.getLong(1).toInt() }.firstOrNull() ?: 0
        if (total == 0 || request.limit <= 0) return SearchPage(total, emptyList())

        val rows =
            query(
                "SELECT $PROBLEM_COLUMNS FROM problem p $whereSql " +
                    "ORDER BY p.fetched_at DESC, p.id ASC LIMIT ${request.limit} OFFSET ${request.offset}",
                args,
            ) { rs ->
                Problem(
                    id = ProblemId(rs.getString("id")),
                    platformId = PlatformId(rs.getString("platform_id")),
                    remoteId = rs.getString("dojo_problem_id"),
                    title = rs.getString("title"),
                    difficulty = Difficulty(rs.getString("difficulty")),
                    url = rs.getString("url"),
                    tags = emptyList(),
                    fetchedAt = DbCodecs.parseInstant(rs.getString("fetched_at")),
                )
            }
        return SearchPage(
            total = total,
            items =
                rows.map { problem ->
                    SearchHit(problem.withTags(tagsFor(problem.id.value)), sourcesByProblem[problem.id.value].orEmpty())
                },
        )
    }

    override suspend fun tags(platform: String?): List<TagSummary> {
        val platformFilter = platform?.takeIf { it.isNotBlank() }
        val platformJoin =
            if (platformFilter == null) "" else "JOIN problem p ON p.id = pt.problem_id AND p.platform_id = ? "
        return query(
            "SELECT t.name, COUNT(pt.problem_id) FROM tag t " +
                "JOIN problem_tag pt ON pt.tag_id = t.id $platformJoin" +
                "GROUP BY t.id ORDER BY COUNT(pt.problem_id) DESC, t.name ASC",
            platformFilter?.let(::listOf).orEmpty(),
        ) { rs -> TagSummary(rs.getString(1), rs.getInt(2)) }
    }

    // ------------------------------------------------------------------
    // 三源命中:题目 id → 命中来源集合
    // ------------------------------------------------------------------

    /**
     * [CodeQuestDatabase.rawQuery] 的参数化转发:args 为请求级小集合(平台 + 标签名),
     * spread 的数组拷贝开销可忽略,故局部抑制。
     */
    @Suppress("SpreadOperator")
    private suspend fun <T> query(
        sql: String,
        args: List<String>,
        mapRow: (ResultSet) -> T,
    ): List<T> = db.rawQuery(sql, *args.toTypedArray(), mapRow = mapRow)

    private suspend fun matchSources(keyword: String): Map<String, Set<SearchSource>> {
        val sources = HashMap<String, MutableSet<SearchSource>>()

        fun record(
            ids: List<String>,
            source: SearchSource,
        ) {
            ids.forEach { id -> sources.getOrPut(id) { mutableSetOf() }.add(source) }
        }

        record(titleLikeIds(keyword), SearchSource.TITLE)
        record(contentMatchIds(keyword), SearchSource.CONTENT)
        record(noteProblemIds(keyword), SearchSource.NOTE)
        return sources
    }

    /** 标题 / remoteId 子串命中(LIKE,ESCAPE 转义;两列同 pattern)。 */
    private suspend fun titleLikeIds(keyword: String): List<String> {
        val like = FtsSupport.likePattern(keyword)
        return db.rawQuery(
            "SELECT id FROM problem WHERE title LIKE ? ESCAPE '\\' OR dojo_problem_id LIKE ? ESCAPE '\\'",
            like,
            like,
        ) { it.getString(1) }
    }

    /** 题面命中:FTS5 优先(关键字 >= 3 字符),SQLException/短词降级 LIKE(照 searchCandidateIds 模式)。 */
    private suspend fun contentMatchIds(keyword: String): List<String> {
        if (keyword.length >= FtsSupport.MIN_TRIGRAM_CHARS) {
            try {
                return db.rawQuery(
                    "SELECT problem_id FROM problem_content_fts WHERE problem_content_fts MATCH ?",
                    FtsSupport.matchExpression(keyword),
                ) { it.getString(1) }
            } catch (_: SQLException) {
                // FTS5 不可用/查询异常 → 降级 LIKE
            }
        }
        return db.rawQuery(
            "SELECT problem_id FROM problem_content WHERE content_md LIKE ? ESCAPE '\\'",
            FtsSupport.likePattern(keyword),
        ) { it.getString(1) }
    }

    /** 题解笔记命中:[SolutionNoteSearch] 取 solution id(自带降级),经 challenge 映射回题目。 */
    private suspend fun noteProblemIds(keyword: String): List<String> {
        val solutionIds = SolutionNoteSearch.searchByNote(db, keyword)
        if (solutionIds.isEmpty()) return emptyList()
        return db
            .rawQuery(
                "SELECT c.problem_id FROM challenge c JOIN solution s ON s.challenge_id = c.id " +
                    "WHERE s.id IN (${sqlIdList(solutionIds.map { it.value })})",
            ) { it.getString(1) }
            .distinct()
    }

    // ------------------------------------------------------------------
    // 过滤条件与标签装载
    // ------------------------------------------------------------------

    /** 动态 WHERE:platform / 命中 id 集合 / 标签 AND;返回 SQL 片段 + 有序参数。 */
    private fun buildWhere(
        keyword: String?,
        matchedIds: Set<String>,
        tags: List<String>,
        platform: String?,
    ): Pair<String, List<String>> {
        val conditions = ArrayList<String>()
        val args = ArrayList<String>()
        platform?.let {
            conditions += "p.platform_id = ?"
            args += it
        }
        // 有关键字时结果限定在三源命中集合(空集合由调用方以 total==0 短路,不进这里)
        if (keyword != null && matchedIds.isNotEmpty()) {
            conditions += "p.id IN (${sqlIdList(matchedIds)})"
        }
        if (tags.isNotEmpty()) {
            conditions +=
                "p.id IN (SELECT pt.problem_id FROM problem_tag pt JOIN tag t ON t.id = pt.tag_id " +
                "WHERE t.name IN (${placeholders(tags.size)}) " +
                "GROUP BY pt.problem_id HAVING COUNT(DISTINCT t.name) = ${tags.size})"
            args += tags
        }
        return if (conditions.isEmpty()) "" to emptyList() else "WHERE ${conditions.joinToString(" AND ")}" to args
    }

    /** 某题的标签,按 problem_tag.position 排序(与 ExposedProblemRepository 同语义)。 */
    private suspend fun tagsFor(problemId: String): List<Tag> =
        db.rawQuery(
            "SELECT t.id, t.name FROM problem_tag pt JOIN tag t ON t.id = pt.tag_id " +
                "WHERE pt.problem_id = ? ORDER BY pt.position ASC",
            problemId,
        ) { rs -> Tag(TagId(rs.getString(1)), rs.getString(2)) }

    private fun Problem.withTags(tags: List<Tag>): Problem = if (tags.isEmpty()) this else copy(tags = tags)

    private companion object {
        private const val PROBLEM_COLUMNS = "p.id, p.platform_id, p.dojo_problem_id, p.title, p.difficulty, p.url, p.fetched_at"

        /** id 集合内插为 IN 列表;值来自本地库(服务端生成),单引号双写兜底转义。 */
        fun sqlIdList(ids: Collection<String>): String = ids.joinToString(",") { "'${it.replace("'", "''")}'" }

        fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
    }
}
