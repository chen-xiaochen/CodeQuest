package com.codequest.infra.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [unit] 数据库文件夹 → 数据库文件路径解析(WP15)。
 *
 * 语义:设置为空(默认态)→ `~/.codequest/codequest.db`;非空 → `<folder>/codequest.db`。
 */
class DatabaseFoldersTest {
    @Test
    fun `null folder falls back to default home directory`() {
        val file = DatabaseFolders.databaseFile(null)
        val expected = Paths.get(System.getProperty("user.home"), ".codequest", "codequest.db")
        assertEquals(expected, file)
    }

    @Test
    fun `blank folder falls back to default home directory`() {
        assertEquals(
            Paths.get(System.getProperty("user.home"), ".codequest", "codequest.db"),
            DatabaseFolders.databaseFile("   "),
        )
    }

    @Test
    fun `custom folder resolves database file inside it`() {
        val dir: Path = Files.createTempDirectory("cq-folders")
        assertEquals(
            dir.resolve(DatabaseFolders.DATABASE_FILE_NAME),
            DatabaseFolders.databaseFile(dir.toString()),
        )
    }

    @Test
    fun `custom folder is trimmed before resolving`() {
        val dir: Path = Files.createTempDirectory("cq-folders-trim")
        assertEquals(
            dir.resolve(DatabaseFolders.DATABASE_FILE_NAME),
            DatabaseFolders.databaseFile("  ${dir.toString()}  "),
        )
    }
}
