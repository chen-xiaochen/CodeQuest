package com.codequest.infra.db

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [unit] 1.3.1 修复回归:新库(文件不存在)构造 [CodeQuestDatabase] 必须落盘 schema——
 * 用户实测 1.3.0 曾出现 0 字节无表库(/api/problems 500 no such table: problem),
 * 本测试锁定「构造完成 = 文件非空 + 全表存在」的契约,防回归。
 */
class CodeQuestDatabaseFreshFileTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private var db: CodeQuestDatabase? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("cq-fresh")
        dbFile = dir.resolve("codequest.db")
    }

    @AfterTest
    fun tearDown() {
        db?.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `fresh database construction creates non-empty file with all tables`() {
        db = CodeQuestDatabase(DatabaseConfigProvider { dbFile })

        assertTrue(Files.exists(dbFile), "db 文件应被创建")
        assertTrue(Files.size(dbFile) > 0, "db 文件不应为 0 字节(schema 已落盘)")

        val tables =
            transaction(db!!.database) {
                exec("SELECT name FROM sqlite_master WHERE type='table'") { rs ->
                    generateSequence { rs.next().let { if (it) rs.getString(1) else null } }.toList()
                } ?: emptyList<String>()
            }
        val required =
            listOf(
                "problem", "problem_content", "challenge", "challenge_language",
                "solution", "test_case", "submission", "tag", "problem_tag",
                "contest_reminder", "settings_kv", "schema_version",
            )
        val missing = required.filter { it !in tables }
        assertTrue(missing.isEmpty(), "缺表: $missing; 实际: $tables")
    }

    @Test
    fun `reopening existing database is idempotent`() {
        val first = CodeQuestDatabase(DatabaseConfigProvider { dbFile })
        val sizeAfterFirst = Files.size(dbFile)
        first.close()

        db = CodeQuestDatabase(DatabaseConfigProvider { dbFile })
        assertTrue(Files.size(dbFile) >= sizeAfterFirst, "二次打开不应缩小文件")
    }
}
