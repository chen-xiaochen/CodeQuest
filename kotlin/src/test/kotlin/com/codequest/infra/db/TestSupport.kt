package com.codequest.infra.db

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** 集成测试公共工具:临时 SQLite 文件库 + sqlite_master 检查 + 用例包裹器。 */

fun newTempDatabase(): Pair<CodeQuestDatabase, Path> {
    val dir = Files.createTempDirectory("codequest-db-test")
    val dbFile = dir.resolve("test.db")
    val db = CodeQuestDatabase(DatabaseConfigProvider { dbFile })
    return db to dbFile
}

/** 打开临时库执行用例并自动关闭;block 内可直接调用 suspend 仓库方法。 */
fun integrationTest(block: suspend (db: CodeQuestDatabase) -> Unit) {
    val (db, _) = newTempDatabase()
    db.use {
        kotlinx.coroutines.runBlocking { block(db) }
    }
}

/** 读取 sqlite_master 中指定类型的对象名集合。 */
suspend fun CodeQuestDatabase.sqliteMasterNames(type: String): Set<String> =
    rawQuery("SELECT name FROM sqlite_master WHERE type = ?", type) { it.getString(1) }.toSet()

/** 读取单个标量查询值(字符串形态)。 */
suspend fun CodeQuestDatabase.queryString(sql: String): String =
    rawQuery(sql) { it.getString(1) }.firstOrNull() ?: ""

/** 读取单个标量查询值(整数形态)。 */
suspend fun CodeQuestDatabase.queryLong(sql: String): Long =
    rawQuery(sql) { it.getLong(1) }.firstOrNull() ?: 0L

suspend fun assertSchemaVersion(
    db: CodeQuestDatabase,
    expected: Int,
) {
    val version = db.rawQuery("SELECT version FROM schema_version LIMIT 1") { it.getInt(1) }.firstOrNull()
    assertEquals(expected, version)
}
