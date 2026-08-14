package com.codequest.infra.db

/**
 * FTS5/LIKE 关键字工具(WP14)。
 *
 * 降级链:关键字长度 >= [MIN_TRIGRAM_CHARS] 且 FTS 表可用 → FTS5 MATCH;
 * 否则(短关键字 / FTS 表缺失 / MATCH 语法异常)→ LIKE 子串(ESCAPE 转义)。
 */
internal object FtsSupport {
    /** trigram 分词器可命中的最短关键字字符数。 */
    const val MIN_TRIGRAM_CHARS = 3

    /** 关键字转 FTS5 MATCH 表达式:整体加引号成 phrase,内部引号双写转义。 */
    fun matchExpression(keyword: String): String = "\"" + keyword.replace("\"", "\"\"") + "\""

    /** 关键字转 LIKE 子串 pattern;`\`、`%`、`_` 均转义,配合 SQL `ESCAPE '\'`。 */
    fun likePattern(keyword: String): String =
        "%" +
            keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") +
            "%"
}
