package com.codequest.infra.db

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.util.messages.MessageBusOwner
import com.intellij.util.messages.impl.PluginListenerDescriptor
import com.intellij.util.messages.impl.RootBus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [integration] [ManagedDatabase] 切库重建 + 消息总线触发(WP15)。
 *
 * 覆盖三条链路:
 * 1. 切库:switchTo → close 旧 CodeQuestDatabase → 新路径重建,仓库 getter 随之指向新库;
 * 2. 消息总线:在独立 MessageBus 上按 [CodeQuestSettings.DATABASE_FOLDER_TOPIC] 订阅/发布,handler 收到生效路径;
 * 3. 重启模拟(acceptance 自动化部分):写数据 → 销毁实例 → 按新文件夹新建实例 → 数据从新位置读出。
 */
class ManagedDatabaseTest {
    private val fixedInstant = Instant.parse("2026-08-15T10:00:00Z")
    private val dirs = mutableListOf<Path>()
    private var managed: ManagedDatabase? = null

    private fun newDir(): Path = Files.createTempDirectory("cq-managed").also { dirs.add(it) }

    private fun problem(id: String, title: String) = Problem(
        id = ProblemId(id),
        platformId = PlatformId("codeforces"),
        remoteId = "1927A",
        title = title,
        difficulty = Difficulty("easy"),
        url = "https://codeforces.com/problemset/problem/1927/A",
        tags = emptyList(),
        fetchedAt = fixedInstant,
    )

    @AfterTest
    fun tearDown() {
        managed?.close()
    }

    @Test
    fun `opens database under configured folder`() = runBlockingTest {
        val dir = newDir()
        managed = ManagedDatabase(dir.toString()).also { db ->
            db.problemRepository.save(problem("p-1", "In folder A"))
        }
        assertTrue(Files.exists(dir.resolve(DatabaseFolders.DATABASE_FILE_NAME)))
    }

    @Test
    fun `switch closes old database and rebuilds at new folder`() = runBlockingTest {
        val dirA = newDir()
        val dirB = newDir()
        managed = ManagedDatabase(dirA.toString())
        val oldDatabase = managed!!.database
        managed!!.problemRepository.save(problem("p-1", "Written to A"))
        assertEquals(dirA, managed!!.databaseFolder)

        managed!!.switchTo(dirB.toString())

        assertNotEquals(oldDatabase, managed!!.database)
        assertEquals(dirB, managed!!.databaseFolder)
        // 新库经新仓库实例可读写(空库,旧数据不在)
        assertEquals(null, managed!!.problemRepository.findById(ProblemId("p-1")))
        managed!!.problemRepository.save(problem("p-2", "Written to B"))
        // 新旧两个库文件都在磁盘(切库不迁移数据)
        assertTrue(Files.exists(dirA.resolve(DatabaseFolders.DATABASE_FILE_NAME)))
        assertTrue(Files.exists(dirB.resolve(DatabaseFolders.DATABASE_FILE_NAME)))
    }

    @Test
    fun `message bus topic drives database switch`() = runBlockingTest {
        val dirA = newDir()
        val dirB = newDir()
        managed = ManagedDatabase(dirA.toString())

        // 纯 JVM 测试无法走 MessageBusFactory(其内部需要运行中的应用),
        // 直接以测试宿主构造 RootBus 作为独立消息总线
        val bus = RootBus(TestBusOwner)
        val connection = bus.connect(com.intellij.openapi.Disposable { })
        connection.subscribe(
            CodeQuestSettings.DATABASE_FOLDER_TOPIC,
            CodeQuestSettings.DatabaseFolderListener { folder -> managed?.switchTo(folder.toString()) },
        )

        bus.syncPublisher(CodeQuestSettings.DATABASE_FOLDER_TOPIC)
            .databaseFolderChanged(dirB)

        assertEquals(dirB, managed!!.databaseFolder)
        connection.disconnect()
    }

    @Test
    fun `restart simulation reads data back from new location`() = runBlockingTest {
        // 第一次运行:文件夹 A(旧设置)写入数据
        val dirA = newDir()
        val first = ManagedDatabase(dirA.toString())
        try {
            first.problemRepository.save(problem("p-keep", "Persisted in old folder"))
        } finally {
            first.close() // 模拟 IDE 关闭(实例销毁)
        }

        // 用户改数据库文件夹为 B 并 apply → 重启后 DatabaseManager 依据持久化设置(B)新建实例
        val dirB = newDir()
        val second = ManagedDatabase(dirB.toString())
        managed = second
        second.problemRepository.save(problem("p-new", "Written after restart"))
        assertEquals("Written after restart", second.problemRepository.findById(ProblemId("p-new"))?.title)
        assertTrue(Files.exists(dirB.resolve(DatabaseFolders.DATABASE_FILE_NAME)))

        // 旧文件夹数据不受影响,仍可从旧位置打开读出
        ManagedDatabase(dirA.toString()).use { legacy ->
            assertEquals("Persisted in old folder", legacy.problemRepository.findById(ProblemId("p-keep"))?.title)
        }
    }

    private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    /** 独立 MessageBus 的宿主:不注册插件级懒监听,仅满足 [RootBus] 构造契约。 */
    private object TestBusOwner : MessageBusOwner {
        override fun createListener(descriptor: PluginListenerDescriptor): Any = Any()
        override fun isDisposed(): Boolean = false
    }
}
