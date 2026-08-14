package com.codequest.infra.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * schema 管理(WP14)。
 *
 * 首版直接建全量表 + 写入版本号;未来迁移按「读 schema_version → 增量 DDL → 更新版本号」
 * 的机制扩展(幂等:全部 IF NOT EXISTS)。
 *
 * FTS5 选型(见完成报告):两张外部内容(external content)FTS5 虚拟表 + 同步触发器,
 * `tokenize='trigram'`——trigram 分词支持任意子串匹配,中文按子串命中(unicode61 会把
 * 连续 CJK 并成长 token,子串查不中);代价是关键字不足 3 个字符时 FTS 无法命中,
 * 由调用侧降级 LIKE([FtsSupport])。
 */
object SchemaManager {
    const val CURRENT_VERSION: Int = 1

    fun createAll(db: Database) {
        transaction(db) {
            SchemaUtils.create(
                Problems,
                ProblemContents,
                Challenges,
                ChallengeLanguages,
                Solutions,
                TestCases,
                Submissions,
                Tags,
                ProblemTags,
                ContestReminders,
                SettingsKv,
                SchemaVersionTable,
            )
            exec(CREATE_PROBLEM_CONTENT_FTS)
            exec(CREATE_SOLUTION_NOTE_FTS)
            PROBLEM_CONTENT_TRIGGERS.forEach(::exec)
            SOLUTION_NOTE_TRIGGERS.forEach(::exec)
            if (SchemaVersionTable.selectAll().count() == 0L) {
                SchemaVersionTable.insert { it[version] = CURRENT_VERSION }
            }
        }
    }
}

private const val CREATE_PROBLEM_CONTENT_FTS = """
CREATE VIRTUAL TABLE IF NOT EXISTS problem_content_fts USING fts5(
    problem_id UNINDEXED,
    content_md,
    content='problem_content',
    content_rowid='rowid',
    tokenize='trigram'
)
"""

private const val CREATE_SOLUTION_NOTE_FTS = """
CREATE VIRTUAL TABLE IF NOT EXISTS solution_note_fts USING fts5(
    id UNINDEXED,
    note_md,
    content='solution',
    content_rowid='rowid',
    tokenize='trigram'
)
"""

private val PROBLEM_CONTENT_TRIGGERS =
    listOf(
        """
    CREATE TRIGGER IF NOT EXISTS problem_content_fts_ai AFTER INSERT ON problem_content BEGIN
        INSERT INTO problem_content_fts(rowid, problem_id, content_md)
        VALUES (new.rowid, new.problem_id, new.content_md);
    END
    """,
        """
    CREATE TRIGGER IF NOT EXISTS problem_content_fts_ad AFTER DELETE ON problem_content BEGIN
        INSERT INTO problem_content_fts(problem_content_fts, rowid, problem_id, content_md)
        VALUES ('delete', old.rowid, old.problem_id, old.content_md);
    END
    """,
        """
    CREATE TRIGGER IF NOT EXISTS problem_content_fts_au AFTER UPDATE ON problem_content BEGIN
        INSERT INTO problem_content_fts(problem_content_fts, rowid, problem_id, content_md)
        VALUES ('delete', old.rowid, old.problem_id, old.content_md);
        INSERT INTO problem_content_fts(rowid, problem_id, content_md)
        VALUES (new.rowid, new.problem_id, new.content_md);
    END
    """,
    )

private val SOLUTION_NOTE_TRIGGERS =
    listOf(
        """
    CREATE TRIGGER IF NOT EXISTS solution_note_fts_ai AFTER INSERT ON solution BEGIN
        INSERT INTO solution_note_fts(rowid, id, note_md)
        VALUES (new.rowid, new.id, new.note_md);
    END
    """,
        """
    CREATE TRIGGER IF NOT EXISTS solution_note_fts_ad AFTER DELETE ON solution BEGIN
        INSERT INTO solution_note_fts(solution_note_fts, rowid, id, note_md)
        VALUES ('delete', old.rowid, old.id, old.note_md);
    END
    """,
        """
    CREATE TRIGGER IF NOT EXISTS solution_note_fts_au AFTER UPDATE ON solution BEGIN
        INSERT INTO solution_note_fts(solution_note_fts, rowid, id, note_md)
        VALUES ('delete', old.rowid, old.id, old.note_md);
        INSERT INTO solution_note_fts(rowid, id, note_md)
        VALUES (new.rowid, new.id, new.note_md);
    END
    """,
    )
