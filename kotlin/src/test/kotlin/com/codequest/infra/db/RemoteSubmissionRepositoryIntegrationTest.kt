package com.codequest.infra.db

import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [integration] ExposedRemoteSubmissionRepository 集成测试(WP59):
 * 往返 + UNIQUE(platform_id, remote_id) 冲突跳过 + 分页倒序/平台过滤。
 */
class RemoteSubmissionRepositoryIntegrationTest {
    private val leetcode = PlatformId("leetcode")

    private fun record(
        remoteId: String,
        platformId: PlatformId = leetcode,
        submittedAt: Instant = Instant.parse("2026-10-01T12:00:00Z"),
        runtimeMs: Int? = 40,
        memoryKb: Int? = 40960,
    ) = RemoteSubmissionRecord(
        platformId = platformId,
        remoteId = remoteId,
        titleSlug = "two-sum",
        title = "Two Sum",
        statusDisplay = "AC",
        lang = "kotlin",
        runtimeMs = runtimeMs,
        memoryKb = memoryKb,
        submittedAt = submittedAt,
    )

    @Test
    fun `upsert and listPage round trip with null metrics`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        val pending = record("r-1", runtimeMs = null, memoryKb = null)
        assertTrue(repo.upsert(pending))
        assertEquals(listOf(pending), repo.listPage(null, limit = 10, offset = 0))
    }

    @Test
    fun `duplicate platform and remote id is skipped`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        assertTrue(repo.upsert(record("r-1")))
        // 冲突跳过:返回 false、行数不增、既有值不被覆盖
        assertFalse(repo.upsert(record("r-1", submittedAt = Instant.parse("2020-01-01T00:00:00Z"))))
        assertEquals(1L, repo.countByPlatform(leetcode))
        assertEquals(
            Instant.parse("2026-10-01T12:00:00Z"),
            repo.listPage(leetcode, limit = 10, offset = 0).single().submittedAt,
        )
    }

    @Test
    fun `same remote id on different platform is kept`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        repo.upsert(record("r-1"))
        assertTrue(repo.upsert(record("r-1", platformId = PlatformId("leetcodecn"))))
        assertEquals(1L, repo.countByPlatform(leetcode))
        assertEquals(1L, repo.countByPlatform(PlatformId("leetcodecn")))
    }

    @Test
    fun `exists matches on platform and remote id pair`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        repo.upsert(record("r-1"))
        assertTrue(repo.exists(leetcode, "r-1"))
        assertFalse(repo.exists(leetcode, "r-2"))
        assertFalse(repo.exists(PlatformId("leetcodecn"), "r-1"))
    }

    @Test
    fun `listPage orders newest first with pagination`() = integrationTest { db ->
        val repo = ExposedRemoteSubmissionRepository(db)
        repo.upsert(record("r-old", submittedAt = Instant.parse("2026-10-01T10:00:00Z")))
        repo.upsert(record("r-new", submittedAt = Instant.parse("2026-10-01T12:00:00Z")))
        repo.upsert(record("r-mid", submittedAt = Instant.parse("2026-10-01T11:00:00Z")))
        // 跨平台行用更早时间戳,验证无过滤时混合倒序(r-other 与 r-new 同刻会按 id
        // tie-break 打乱期望序,故独立取 09:00)
        repo.upsert(record("r-other", platformId = PlatformId("codeforces"), submittedAt = Instant.parse("2026-10-01T09:00:00Z")))

        assertEquals(
            listOf("r-new", "r-mid", "r-old"),
            repo.listPage(leetcode, limit = 10, offset = 0).map { it.remoteId },
        )
        assertEquals(listOf("r-mid"), repo.listPage(leetcode, limit = 1, offset = 1).map { it.remoteId })
        // 无平台过滤 = 跨平台全部,倒序
        assertEquals(
            listOf("r-new", "r-mid", "r-old", "r-other"),
            repo.listPage(null, limit = 10, offset = 0).map { it.remoteId },
        )
    }
}
