package com.codequest.infra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration] schema 建表集成测试(WP14)。
 *
 * 临时 SQLite 文件库上验证:全量表 + FTS5 虚拟表 + 同步触发器 + WAL + 版本号,幂等重建。
 */
class SchemaIntegrationTest {
    @Test
    fun `creates all core tables`() = integrationTest { db ->
        val tables = db.sqliteMasterNames("table")
        listOf(
            "problem", "problem_content", "challenge", "challenge_language", "solution",
            "test_case", "submission", "tag", "problem_tag", "contest_reminder",
            "settings_kv", "schema_version",
            "problem_rating", "remote_submission", "review_schedule",
        ).forEach { name ->
            assertTrue(name in tables, "缺少表 $name,实际: $tables")
        }
    }

    @Test
    fun `creates fts5 virtual tables and sync triggers`() = integrationTest { db ->
        val tables = db.sqliteMasterNames("table")
        assertTrue("problem_content_fts" in tables, "缺少 problem_content_fts,实际: $tables")
        assertTrue("solution_note_fts" in tables, "缺少 solution_note_fts,实际: $tables")

        val triggers = db.sqliteMasterNames("trigger")
        listOf(
            "problem_content_fts_ai", "problem_content_fts_ad", "problem_content_fts_au",
            "solution_note_fts_ai", "solution_note_fts_ad", "solution_note_fts_au",
        ).forEach { name ->
            assertTrue(name in triggers, "缺少触发器 $name,实际: $triggers")
        }
    }

    @Test
    fun `journal mode is wal`() = integrationTest { db ->
        assertEquals("wal", db.queryString("PRAGMA journal_mode").lowercase())
    }

    @Test
    fun `writes schema version row`() = integrationTest { db ->
        assertSchemaVersion(db, SchemaManager.CURRENT_VERSION)
    }

    @Test
    fun `schema creation is idempotent`() = integrationTest { db ->
        SchemaManager.createAll(db.database) // 第二次建表必须无异常、版本不变
        assertSchemaVersion(db, SchemaManager.CURRENT_VERSION)
    }
}
