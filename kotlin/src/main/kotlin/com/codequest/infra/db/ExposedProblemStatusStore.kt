package com.codequest.infra.db

import com.codequest.core.model.ProblemId

/**
 * [ProblemStatusStore] 的 SQLite 实现(WP37):challenge JOIN submission 一次取回
 * 请求题目的全部提交判定,Kotlin 侧复用 [DbCodecs] 解码后聚合(任一 Accepted → AC;
 * 有提交 → TRIED)。判定列存 SubmissionResult JSON,不在 SQL 里做字符串匹配——
 * 序列化格式演进只改 codec 一处。行数 = 请求题目的提交数(列表页一页 ≤ 100 题),
 * 内存聚合可接受。
 */
class ExposedProblemStatusStore(
    private val db: CodeQuestDatabase,
) : ProblemStatusStore {
    override suspend fun statuses(problemIds: Collection<ProblemId>): Map<ProblemId, SolveStatus> {
        val ids = problemIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val rows = queryStatusRows(ids)
        // 出现即「有提交」;值 = 是否出现过 Accepted(AC 粘性,后续 WA 不降级)
        val hasAc = HashMap<ProblemId, Boolean>()
        for ((id, verdict) in rows) {
            val key = ProblemId(id)
            if (DbCodecs.decodeVerdict(verdict).isSuccessful) {
                hasAc[key] = true
            } else {
                hasAc.putIfAbsent(key, false)
            }
        }
        return hasAc.mapValues { (_, accepted) -> if (accepted) SolveStatus.AC else SolveStatus.TRIED }
    }

    /**
     * [CodeQuestDatabase.rawQuery] 的参数化转发:args 为请求级小集合(一页 ≤ 100 题 id),
     * spread 的数组拷贝开销可忽略,故局部抑制(照 [ExposedSearchStore] 先例)。
     */
    @Suppress("SpreadOperator")
    private suspend fun queryStatusRows(ids: List<ProblemId>): List<Pair<String, String>> =
        db.rawQuery(
            "SELECT c.problem_id, s.verdict FROM challenge c " +
                "JOIN submission s ON s.challenge_id = c.id " +
                "WHERE c.problem_id IN (${placeholders(ids.size)})",
            *ids.map { it.value }.toTypedArray(),
        ) { rs -> rs.getString(1) to rs.getString(2) }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
}
