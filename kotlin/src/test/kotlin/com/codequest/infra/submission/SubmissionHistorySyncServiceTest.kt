package com.codequest.infra.submission

import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.ExposedRemoteSubmissionRepository
import com.codequest.infra.db.integrationTest
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import com.codequest.spi.RemoteSubmission
import com.codequest.spi.RemoteSubmissionPage
import com.codequest.spi.SubmissionHistoryCapable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [integration] 1.7.0 F2(WP61):同步增量停止语义(遇已存即停/页内混排先入库新条)、
 * 页数上限保护、自然到底、平台能力兜底——真实 SQLite 仓库 + 脚本化假平台。
 */
class SubmissionHistorySyncServiceTest {
    private val leetcode = PlatformId("leetcode")

    private fun entry(
        remoteId: String,
        submittedAt: Instant = Instant.parse("2026-10-01T12:00:00Z"),
    ) = RemoteSubmission(
        remoteId = remoteId,
        titleSlug = "two-sum",
        title = "Two Sum",
        statusDisplay = "AC",
        lang = "kotlin",
        runtimeMs = 64,
        memoryKb = 67891,
        submittedAt = submittedAt,
    )

    /** 脚本化假平台:每次调用弹出一页;pages 耗尽返回空页 hasMore=false。 */
    private class FakeHistoryPlatform(
        val pages: MutableList<RemoteSubmissionPage>,
    ) : OJPlatform, SubmissionHistoryCapable {
        override val id = PlatformId("leetcode")
        override val domain = "leetcode.example.com"
        override val info = PlatformInfo(displayName = "Fake LeetCode", templateStyle = TemplateStyle.ACM)
        val calls = mutableListOf<Pair<Int, Int>>()

        override suspend fun fetchSubmissionHistory(
            offset: Int,
            limit: Int,
        ): RemoteSubmissionPage {
            calls += offset to limit
            return pages.removeFirstOrNull() ?: RemoteSubmissionPage(emptyList(), hasMore = false)
        }
    }

    private fun service(
        platform: FakeHistoryPlatform,
        repo: ExposedRemoteSubmissionRepository,
        pageSize: Int = 2,
        maxPages: Int = 200,
    ) = SubmissionHistorySyncService(
        platforms = { listOf(platform) },
        remoteSubmissions = { repo },
        pageSize = pageSize,
        maxPages = maxPages,
    )

    @Test
    fun `first page fully existing stops with zero synced`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        repo.upsert(record("r-1"))
        repo.upsert(record("r-2"))
        val platform =
            FakeHistoryPlatform(
                mutableListOf(
                    RemoteSubmissionPage(listOf(entry("r-1"), entry("r-2")), hasMore = true),
                    RemoteSubmissionPage(listOf(entry("r-9")), hasMore = true), // 不应被拉取
                ),
            )

        val result = service(platform, repo).sync(leetcode)

        assertEquals(0, result.synced)
        assertEquals(2, result.total)
        assertEquals(SubmissionHistorySyncService.StoppedBy.EXISTING, result.stoppedBy)
        assertEquals(1, platform.calls.size) // 首页即停,第二页未拉
    }

    @Test
    fun `mixed page persists all new entries before stopping`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        repo.upsert(record("r-old"))
        // 页内新旧混排(remoteId 非严格单调):新条 r-new-1/r-new-2 都要入库后再停
        val platform =
            FakeHistoryPlatform(
                mutableListOf(
                    RemoteSubmissionPage(
                        listOf(entry("r-new-1"), entry("r-old"), entry("r-new-2")),
                        hasMore = true,
                    ),
                    RemoteSubmissionPage(listOf(entry("r-9")), hasMore = true), // 不应被拉取
                ),
            )

        val result = service(platform, repo, pageSize = 3).sync(leetcode)

        assertEquals(2, result.synced)
        assertEquals(3, result.total) // r-old + 两条新
        assertEquals(SubmissionHistorySyncService.StoppedBy.EXISTING, result.stoppedBy)
        assertTrue(repo.exists(leetcode, "r-new-1"))
        assertTrue(repo.exists(leetcode, "r-new-2"))
        assertEquals(1, platform.calls.size)
    }

    @Test
    fun `page limit protection caps sync and reports LIMIT`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        // 3 页(pageSize=2 × maxPages=3)全部新条且 hasMore=true → 6 条后触发保护
        val platform =
            FakeHistoryPlatform(
                mutableListOf(
                    RemoteSubmissionPage(listOf(entry("r-1"), entry("r-2")), hasMore = true),
                    RemoteSubmissionPage(listOf(entry("r-3"), entry("r-4")), hasMore = true),
                    RemoteSubmissionPage(listOf(entry("r-5"), entry("r-6")), hasMore = true),
                    RemoteSubmissionPage(listOf(entry("r-7")), hasMore = true), // 超限不拉
                ),
            )

        val result = service(platform, repo, pageSize = 2, maxPages = 3).sync(leetcode)

        assertEquals(6, result.synced)
        assertEquals(6, result.total)
        assertEquals(SubmissionHistorySyncService.StoppedBy.LIMIT, result.stoppedBy)
        assertEquals(3, platform.calls.size)
        assertEquals(0 to 2, platform.calls[0])
        assertEquals(2 to 2, platform.calls[1]) // offset 按 pageIndex × pageSize 推进
        assertEquals(4 to 2, platform.calls[2])
    }

    @Test
    fun `last page hasMore false ends normally`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        val platform =
            FakeHistoryPlatform(
                mutableListOf(
                    RemoteSubmissionPage(listOf(entry("r-1"), entry("r-2")), hasMore = true),
                    RemoteSubmissionPage(listOf(entry("r-3")), hasMore = false),
                ),
            )

        val result = service(platform, repo).sync(leetcode)

        assertEquals(3, result.synced)
        assertEquals(3, result.total)
        assertEquals(SubmissionHistorySyncService.StoppedBy.END, result.stoppedBy)
        assertEquals(2, platform.calls.size)
    }

    @Test
    fun `unknown or incapable platform surfaces IllegalArgumentException`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        val service =
            SubmissionHistorySyncService(
                platforms = { emptyList() },
                remoteSubmissions = { repo },
            )

        assertFailsWith<IllegalArgumentException> { service.sync(leetcode) }
    }

    private fun record(remoteId: String) =
        RemoteSubmissionRecord(
            platformId = leetcode,
            remoteId = remoteId,
            titleSlug = "two-sum",
            title = "Two Sum",
            statusDisplay = "AC",
            lang = "kotlin",
            runtimeMs = 64,
            memoryKb = 67891,
            submittedAt = Instant.parse("2026-09-01T12:00:00Z"),
        )
}
