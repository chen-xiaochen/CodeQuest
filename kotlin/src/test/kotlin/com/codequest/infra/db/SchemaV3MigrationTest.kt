package com.codequest.infra.db

import com.codequest.core.model.PlatformId
import com.codequest.core.model.ProblemRating
import com.codequest.core.model.RemoteSubmissionRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration] v2→v3 迁移测试(WP59):模拟 v2 旧库升级——三张新表建立、既有表数据
 * 不丢、版本号 2→3;重复执行迁移幂等。
 *
 * v2 库构造方式:全新库建好后删除 v3 三表、版本号回落 2(等效于 1.6.0 时代的库形态)。
 */
class SchemaV3MigrationTest {
    private suspend fun downgradeToV2(db: CodeQuestDatabase) {
        db.write {
            exec("DROP TABLE IF EXISTS problem_rating")
            exec("DROP TABLE IF EXISTS remote_submission")
            exec("DROP TABLE IF EXISTS review_schedule")
            exec("UPDATE schema_version SET version = 2")
            // 旧表种子数据(升级前后都必须完好)
            exec(
                "INSERT INTO problem (id, platform_id, dojo_problem_id, title, difficulty, url, fetched_at) " +
                    "VALUES ('p-1', 'leetcode', 'two-sum', 'Two Sum', 'easy', 'https://leetcode.com/problems/two-sum/', " +
                    "'2026-01-01T00:00:00.000000000Z')",
            )
            exec(
                "INSERT INTO submission (id, challenge_id, platform_id, remote_submission_id, language, " +
                    "code_snapshot, verdict, time_ms, memory_kb, submitted_at) " +
                    "VALUES ('s-1', 'c-1', 'leetcode', 'r-9', 'kotlin', 'fun main() {}', " +
                    "'{\"type\":\"Accepted\"}', 12, 2048, '2026-01-01T00:00:00.000000000Z')",
            )
        }
    }

    @Test
    fun `v2 database upgrades to v3 keeping existing data`() = integrationTest { db ->
        downgradeToV2(db)
        SchemaManager.createAll(db.database)

        assertSchemaVersion(db, 3)
        val tables = db.sqliteMasterNames("table")
        listOf("problem_rating", "remote_submission", "review_schedule").forEach { name ->
            assertTrue(name in tables, "升级后缺少表 $name,实际: $tables")
        }
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM problem"), "升级后旧 problem 数据丢失")
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM submission"), "升级后旧 submission 数据丢失")
    }

    @Test
    fun `upgraded tables accept writes through repositories`() = integrationTest { db ->
        downgradeToV2(db)
        SchemaManager.createAll(db.database)

        // 升级建出的三表可立即经仓库读写(remote_submission 的自增主键生效)
        val rating = ExposedRatingRepository(db)
        rating.upsertAll(listOf(ProblemRating("1", 1317.5, Instant.parse("2026-10-02T00:00:00Z"))))
        assertEquals(1L, rating.count())

        val remote = ExposedRemoteSubmissionRepository(db)
        assertTrue(
            remote.upsert(
                RemoteSubmissionRecord(
                    platformId = PlatformId("leetcode"),
                    remoteId = "r-1",
                    titleSlug = "two-sum",
                    title = "Two Sum",
                    statusDisplay = "AC",
                    lang = "kotlin",
                    runtimeMs = 40,
                    memoryKb = 40960,
                    submittedAt = Instant.parse("2026-10-01T12:00:00Z"),
                ),
            ),
        )
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM remote_submission"))
    }

    @Test
    fun `migration is idempotent on repeated createAll`() = integrationTest { db ->
        downgradeToV2(db)
        SchemaManager.createAll(db.database)
        SchemaManager.createAll(db.database) // 重复执行:不报错、版本不变、数据不翻倍

        assertSchemaVersion(db, 3)
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM problem"))
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM submission"))
    }
}
