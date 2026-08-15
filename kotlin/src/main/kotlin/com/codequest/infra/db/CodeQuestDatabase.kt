package com.codequest.infra.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.PrintWriter
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * SQLite 数据库入口(WP14)。
 *
 * 并发模型(spec §3):
 * - 写操作经 [write],由进程内单写锁(Mutex)串行化——SQLite 同库多连接并发写会
 *   直接 SQLITE_BUSY/丢更新,串行化后写路径安全;
 * - 读操作经 [read] 并发放开:WAL 模式下读写互不阻塞,每事务独立连接 + busy_timeout
 *   兜住 checkpoint 窗口;
 * - WAL 为库级持久属性,建库时在事务外一次性开启(见 init)。
 *
 * 每个事务从 [SqliteDataSource] 取新连接(执行 per-connection PRAGMA),事务结束归还关闭。
 */
class CodeQuestDatabase(
    configProvider: DatabaseConfigProvider,
) : AutoCloseable {
    private val writeMutex = Mutex()
    private val dataSource: DataSource
    val database: Database

    init {
        val dbFile = configProvider.databaseFile()
        dbFile.parent?.let { Files.createDirectories(it) }
        dataSource = SqliteDataSource("jdbc:sqlite:$dbFile")
        // WAL 必须在事务外设置(库级持久属性,一次性开启)
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
        }
        database = Database.connect(dataSource)
        SchemaManager.createAll(database)
    }

    /** 并发读事务:不持写锁,跑在 IO 线程池。 */
    suspend fun <T> read(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }

    /** 串行化写事务:全插件唯一写入口。 */
    suspend fun <T> write(block: JdbcTransaction.() -> T): T =
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                transaction(database) { block() }
            }
        }

    /**
     * 参数化只读查询(FTS5/LIKE/PRAGMA 检索走这里):独立短连接,WAL 下与其他事务
     * 并发安全,不依赖 Exposed 的 ResultSet 包装行为。
     */
    suspend fun <T> rawQuery(
        sql: String,
        vararg args: String,
        mapRow: (ResultSet) -> T,
    ): List<T> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { index, arg -> statement.setString(index + 1, arg) }
                    statement.executeQuery().use { rs ->
                        val rows = ArrayList<T>()
                        while (rs.next()) rows += mapRow(rs)
                        rows
                    }
                }
            }
        }

    override fun close() {
        TransactionManager.closeAndUnregister(database)
    }
}

/**
 * 每连接执行 PRAGMA 的极简 DataSource:busy_timeout 防读事务撞上写/checkpoint 报
 * SQLITE_BUSY,foreign_keys 统一开(删除工作区时由实现显式级联,外键仅兜底一致性)。
 */
private class SqliteDataSource(
    private val url: String,
) : DataSource {
    private var logWriter: PrintWriter? = null
    private var loginTimeoutSeconds: Int = 0

    override fun getConnection(): Connection = open()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = open()

    override fun setLogWriter(out: PrintWriter?) {
        logWriter = out
    }

    override fun getLogWriter(): PrintWriter? = logWriter

    override fun setLoginTimeout(seconds: Int) {
        loginTimeoutSeconds = seconds
    }

    override fun getLoginTimeout(): Int = loginTimeoutSeconds

    override fun getParentLogger(): Logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME)

    override fun <T : Any> unwrap(iface: Class<T>): T {
        check(iface.isInstance(this)) { "$iface is not a wrapper target of SqliteDataSource" }
        return iface.cast(this)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

    private fun open(): Connection {
        // 显式加载驱动:IntelliJ 插件类加载器下 JDBC4 的 ServiceLoader 自动注册不可靠
        // (沙箱实测 DriverManager.getConnection 报 "No suitable driver",WP16 修复)
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection(url)
        connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return connection
    }
}
