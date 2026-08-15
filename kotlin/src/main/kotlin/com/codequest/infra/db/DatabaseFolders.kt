package com.codequest.infra.db

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 数据库文件夹 → 数据库文件路径解析(WP15)。
 *
 * 「数据库文件夹」设置语义:
 * - 空/空白(默认态)→ 用户 home 下 `.codequest/`(与 [DefaultDatabaseConfigProvider] 默认一致);
 * - 非空 → 以该文件夹为数据库父目录,库名固定 [DATABASE_FILE_NAME]。
 */
object DatabaseFolders {
    /** SQLite 数据库文件名(所有文件夹下固定不变)。 */
    const val DATABASE_FILE_NAME = "codequest.db"

    /** 默认数据库文件夹:`~/.codequest/`。 */
    fun defaultFolder(): Path = Paths.get(System.getProperty("user.home"), ".codequest")

    /** 默认数据库文件:`~/.codequest/codequest.db`。 */
    fun defaultDatabaseFile(): Path = defaultFolder().resolve(DATABASE_FILE_NAME)

    /** 设置文件夹(空=默认)→ 数据库文件路径。 */
    fun databaseFile(folder: String?): Path {
        val trimmed = folder?.trim()
        return when {
            trimmed.isNullOrEmpty() -> defaultDatabaseFile()
            else -> Paths.get(trimmed).resolve(DATABASE_FILE_NAME)
        }
    }
}
