package com.codequest.infra.db

import com.codequest.core.model.SolutionId
import java.sql.SQLException

/**
 * 解法笔记全文检索(WP14,infra 层入口,供后续统一检索服务/Webview 搜索端点消费)。
 *
 * core 的 [com.codequest.core.repository.SolutionRepository] 无检索方法,故检索入口
 * 留在 infra;keyword >= 3 字符走 solution_note_fts(trigram,中文子串命中),
 * 短关键字或 FTS 异常降级 LIKE。
 */
object SolutionNoteSearch {
    suspend fun searchByNote(
        db: CodeQuestDatabase,
        keyword: String,
    ): List<SolutionId> {
        val like = FtsSupport.likePattern(keyword)
        if (keyword.length >= FtsSupport.MIN_TRIGRAM_CHARS) {
            try {
                return db.rawQuery(
                    "SELECT id FROM solution_note_fts WHERE solution_note_fts MATCH ? " +
                        "ORDER BY id",
                    FtsSupport.matchExpression(keyword),
                ) { rs -> SolutionId(rs.getString(1)) }
            } catch (_: SQLException) {
                // 降级 LIKE
            }
        }
        return db.rawQuery(
            "SELECT id FROM solution WHERE note_md LIKE ? ESCAPE '\\' ORDER BY id",
            like,
        ) { rs -> SolutionId(rs.getString(1)) }
    }
}
