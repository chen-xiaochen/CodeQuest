package com.codequest.infra.db

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Solution
import com.codequest.core.model.SolutionId
import com.codequest.core.model.ChallengeId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration] FTS5 全文检索集成测试(WP14)。
 *
 * 覆盖:中文内容经 problem_content FTS5 命中、短关键字 LIKE 降级、
 * FTS 虚拟表被删后的完整降级路径、solution.note_md 检索、更新/删除后索引同步。
 */
class FtsSearchIntegrationTest {
    private fun problem(id: String, title: String) = Problem(
        id = ProblemId(id),
        platformId = PlatformId("codeforces"),
        remoteId = "R-$id",
        title = title,
        difficulty = Difficulty("easy"),
        url = "u",
        tags = emptyList(),
        fetchedAt = Instant.EPOCH,
    )

    @Test
    fun `search matches problem content via fts5 chinese`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "完全不相关的标题"))
        repo.save(problem("p-2", "同样无关"))
        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "本题考查动态规划与最长公共子序列的建模。", ContentFormat.MARKDOWN))
        repo.saveDetail(ProblemDetail(ProblemId("p-2"), "本题考查贪心策略。", ContentFormat.MARKDOWN))

        val hits = repo.search(PlatformId("codeforces"), "动态规划")
        assertEquals(listOf("p-1"), hits.map { it.id.value })
    }

    @Test
    fun `fts index follows content update and delete`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "标题"))
        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "内容包含动态规划", ContentFormat.MARKDOWN))
        assertEquals(1, repo.search(PlatformId("codeforces"), "动态规划").size)

        // 更新后旧词不再命中、新词命中
        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "内容改成网络流", ContentFormat.MARKDOWN))
        assertTrue(repo.search(PlatformId("codeforces"), "动态规划").isEmpty())
        assertEquals(1, repo.search(PlatformId("codeforces"), "网络流").size)

        // 删除题面后不再命中且不报错
        db.write {
            exec("DELETE FROM problem_content WHERE problem_id = 'p-1'")
        }
        assertTrue(repo.search(PlatformId("codeforces"), "网络流").isEmpty())
    }

    @Test
    fun `short keyword falls back to like and still matches`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "标题"))
        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "求图的最短路径,使用 Dijkstra 算法", ContentFormat.MARKDOWN))

        // 2 个汉字(<trigram 最小 3 字符)走 LIKE 子串降级路径
        val hits = repo.search(PlatformId("codeforces"), "最短路")
        assertEquals(listOf("p-1"), hits.map { it.id.value })
    }

    @Test
    fun `search degrades to like when fts table missing`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "标题甲"))
        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "这道题需要动态规划求解", ContentFormat.MARKDOWN))

        // 模拟 FTS5 不可用:直接删除虚拟表(触发器此后不再被命中,本用例只读)
        db.write { exec("DROP TABLE problem_content_fts") }

        val hits = repo.search(PlatformId("codeforces"), "动态规划")
        assertEquals(listOf("p-1"), hits.map { it.id.value })
    }

    @Test
    fun `solution note full text search hits chinese notes`() = integrationTest { db ->
        ExposedSolutionRepository(db).save(
            Solution(
                id = SolutionId("s-1"),
                challengeId = ChallengeId("c-1"),
                name = "解法",
                noteMd = "核心思路:单调栈维护区间最小值,线段树维护区间或。",
                filePath = "f.kt",
                createdAt = Instant.EPOCH,
            ),
        )
        val hits = SolutionNoteSearch.searchByNote(db, "单调栈")
        assertEquals(listOf(SolutionId("s-1")), hits)
        assertTrue(SolutionNoteSearch.searchByNote(db, "完全不存在于笔记的关键词").isEmpty())
    }
}
