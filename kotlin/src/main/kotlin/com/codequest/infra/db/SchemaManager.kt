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
    const val CURRENT_VERSION: Int = 4

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
            // v3 三表走 raw DDL(remote_submission 的 AUTOINCREMENT 不赌 Exposed 方言生成),
            // 新库与旧库升级统一经 [MIGRATIONS_V3] 建表,全部 IF NOT EXISTS 幂等
            MIGRATIONS_V3.forEach(::exec)
            val existingVersion = SchemaVersionTable.selectAll().firstOrNull()?.let { it[SchemaVersionTable.version] }
            when {
                existingVersion == null -> {
                    SchemaVersionTable.insert { it[version] = CURRENT_VERSION }
                }

                existingVersion < CURRENT_VERSION -> {
                    // v1→v2(1.6.0):problem/tag 补列(新库由 SchemaUtils.create 直接建全,
                    // 仅旧库走 ALTER)。SQLite 无 ADD COLUMN IF NOT EXISTS,按版本号门控幂等
                    if (existingVersion < 2) MIGRATIONS_V2.forEach { exec(it) }
                    // v3→v4(1.9.0):problem_content 补 question_id(收藏写寻址;新库已建全)
                    if (existingVersion < SCHEMA_VERSION_V4) MIGRATIONS_V4.forEach { exec(it) }
                    exec("UPDATE schema_version SET version = $CURRENT_VERSION")
                }
            }
        }
    }
}

/** v1→v2 增量 DDL:双语副标题 + tag slug/中文名(全部 nullable,旧行为 null)。 */
private val MIGRATIONS_V2 =
    listOf(
        "ALTER TABLE problem ADD COLUMN secondary_title VARCHAR(1024)",
        "ALTER TABLE tag ADD COLUMN slug VARCHAR(256)",
        "ALTER TABLE tag ADD COLUMN name_cn VARCHAR(256)",
    )

/**
 * v2→v3(1.7.0)增量 DDL:三张新表(难度评分/站内提交历史/复习排期),既有表零改动。
 * 全部 CREATE TABLE IF NOT EXISTS,新库、旧库升级、重复执行三条路径共用,天然幂等。
 */
private val MIGRATIONS_V3 =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS problem_rating (
            frontend_id VARCHAR(256) NOT NULL,
            rating REAL NOT NULL,
            updated_at VARCHAR(48) NOT NULL,
            CONSTRAINT pk_problem_rating PRIMARY KEY (frontend_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS remote_submission (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            platform_id VARCHAR(256) NOT NULL,
            remote_id VARCHAR(256) NOT NULL,
            title_slug VARCHAR(256) NOT NULL,
            title VARCHAR(1024) NOT NULL,
            status_display VARCHAR(256) NOT NULL,
            lang VARCHAR(256) NOT NULL,
            runtime_ms INT NULL,
            memory_kb INT NULL,
            submitted_at VARCHAR(48) NOT NULL,
            CONSTRAINT uq_remote_submission_platform_remote UNIQUE (platform_id, remote_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS review_schedule (
            problem_id VARCHAR(128) NOT NULL,
            plan_dates TEXT NOT NULL,
            history_dates TEXT NOT NULL,
            updated_at VARCHAR(48) NOT NULL,
            CONSTRAINT pk_review_schedule PRIMARY KEY (problem_id)
        )
        """,
    )

/** v4 版本号门控值(SQLite 无 ADD COLUMN IF NOT EXISTS,按版本号幂等)。 */
private const val SCHEMA_VERSION_V4 = 4

/** v3→v4(1.9.0)增量 DDL:problem_content 补平台数字主档 id 列(nullable,旧行 null,首个 remote 拉取补全)。 */
private val MIGRATIONS_V4 =
    listOf(
        "ALTER TABLE problem_content ADD COLUMN question_id VARCHAR(128)",
    )

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
