package com.codequest.infra.db

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 数据库文件位置提供者(WP14 契约,WP15 消费)。
 *
 * WP14 的默认实现指向用户 home 下的 `.codequest` 目录;WP15 设置面板落地后,
 * 以「数据库文件夹」设置驱动的实现替换默认实现(接口保持不变):
 *
 * ```kotlin
 * val provider = DatabaseConfigProvider { settings.databaseDir.resolve("codequest.db") }
 * val db = CodeQuestDatabase(provider)
 * ```
 *
 * 路径仅在打开/重建连接时读取;设置变更后重建 [CodeQuestDatabase] 实例即可切换库文件。
 */
fun interface DatabaseConfigProvider {
    /** 返回 SQLite 数据库文件路径(父目录不存在时由数据层负责创建)。 */
    fun databaseFile(): Path
}

/** 默认实现:`~/.codequest/codequest.db`(WP15 会替换为设置驱动的实现)。 */
class DefaultDatabaseConfigProvider : DatabaseConfigProvider {
    override fun databaseFile(): Path = Paths.get(System.getProperty("user.home"), ".codequest", "codequest.db")
}
