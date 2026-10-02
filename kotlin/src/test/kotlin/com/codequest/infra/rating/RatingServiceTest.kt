package com.codequest.infra.rating

import com.codequest.core.model.OJException
import com.codequest.core.model.ProblemRating
import com.codequest.infra.db.ExposedRatingRepository
import com.codequest.infra.db.integrationTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] 1.7.0 F1(WP60):RatingService——加载优先级三态(db 非空 / db 空用内置 /
 * 双空)、refresh 成功 upsert+重载、refresh 失败不破坏 DB 与内存快照。
 */
class RatingServiceTest {
    @Test
    fun `load prefers db snapshot when db is non-empty`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        repo.upsertAll(listOf(ProblemRating("1", 1317.5, Instant.parse("2026-10-01T00:00:00Z"))))
        val service =
            RatingService(
                ratings = { repo },
                bundledResource = "rating/definitely-missing.json",
            )

        val snapshot = service.load()

        assertEquals(RatingService.SOURCE_DB, snapshot.source)
        assertEquals(mapOf("1" to 1317.5), snapshot.map)
        // DB 来源无刷新时间(进程重启后内存无刷新记录)
        assertNull(snapshot.updatedAt)
        assertEquals(1317.5, service.get("1"))
        assertNull(service.get("999"))
    }

    @Test
    fun `load falls back to bundled resource when db is empty`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        val service = RatingService(ratings = { repo })

        val snapshot = service.load()

        assertEquals(RatingService.SOURCE_BUNDLED, snapshot.source)
        // 真实内置资源(zerotrac 竞赛题快照):非空,题 1000 为竞赛题必有分
        assertTrue(snapshot.map.isNotEmpty())
        assertNotNull(snapshot.map["1000"])
        assertNull(snapshot.updatedAt)
    }

    @Test
    fun `load reports empty when both db and bundled resource are missing`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        val service =
            RatingService(
                ratings = { repo },
                bundledResource = "rating/definitely-missing.json",
            )

        val snapshot = service.load()

        assertEquals(RatingService.SOURCE_EMPTY, snapshot.source)
        assertTrue(snapshot.map.isEmpty())
        assertNull(service.get("1"))
    }

    @Test
    fun `refresh upserts db and reloads memory snapshot`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        var fetches = 0
        val service =
            RatingService(
                ratings = { repo },
                fetchRemote = {
                    fetches++
                    ZEROTRAC_SAMPLE
                },
                now = { Instant.parse("2026-10-02T12:00:00Z") },
            )

        val snapshot = service.refresh()

        assertEquals(RatingService.SOURCE_DB, snapshot.source)
        assertEquals(mapOf("1" to 1500.5, "2" to 2200.75), snapshot.map)
        assertEquals(Instant.parse("2026-10-02T12:00:00Z"), snapshot.updatedAt)
        // DB 落库与内存一致;get 命中新快照
        assertEquals(mapOf("1" to 1500.5, "2" to 2200.75), repo.loadAll())
        assertEquals(2L, repo.count())
        assertEquals(1500.5, service.get("1"))
        assertEquals(1, fetches)
    }

    @Test
    fun `refresh fetch failure keeps db and memory snapshot intact`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        repo.upsertAll(listOf(ProblemRating("1", 1317.5, Instant.EPOCH)))
        val service =
            RatingService(
                ratings = { repo },
                fetchRemote = { throw IllegalStateException("network down") },
            )
        service.load() // 先装载旧快照(db 态)

        val error = assertFailsWith<OJException> { service.refresh() }

        assertTrue(error.message!!.contains("network down"))
        // 失败不破坏 DB、不动内存快照
        assertEquals(1L, repo.count())
        assertEquals(mapOf("1" to 1317.5), repo.loadAll())
        assertEquals(RatingService.SOURCE_DB, service.status().source)
        assertEquals(1317.5, service.get("1"))
    }

    @Test
    fun `refresh parse failure keeps db intact`() = integrationTest { db ->
        val repo = ExposedRatingRepository(db)
        val service =
            RatingService(
                ratings = { repo },
                fetchRemote = { "<html>not json</html>" },
            )

        assertFailsWith<OJException> { service.refresh() }

        // upsert 在解析成功后才执行:DB 零写入
        assertEquals(0L, repo.count())
        assertNull(service.get("1"))
    }

    private companion object {
        /** zerotrac 原始形态样例(含未知字段,验证宽容解析)。 */
        private const val ZEROTRAC_SAMPLE =
            """[{"Rating": 1500.5, "ID": 1, "Title": "Two Sum", "TitleSlug": "two-sum",
               "ContestSlug": "weekly-contest-1", "ProblemIndex": "Q1"},
              {"Rating": 2200.75, "ID": 2, "Title": "Add Two Numbers", "TitleSlug": "add-two-numbers",
               "ContestSlug": "weekly-contest-2", "ProblemIndex": "Q2"}]"""
    }
}
