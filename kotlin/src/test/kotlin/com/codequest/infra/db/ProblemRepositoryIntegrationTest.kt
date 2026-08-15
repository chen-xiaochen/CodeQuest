package com.codequest.infra.db

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [integration] ProblemRepository / ProblemDetail CRUD 集成测试(WP14)。 */
class ProblemRepositoryIntegrationTest {
    private val fixedInstant = Instant.parse("2026-08-14T12:34:56.789123456Z")

    private fun problem(
        id: String,
        title: String,
        tags: List<Tag> = emptyList(),
        platform: String = "codeforces",
        remoteId: String = "1927A",
    ) = Problem(
        id = ProblemId(id),
        platformId = PlatformId(platform),
        remoteId = remoteId,
        title = title,
        difficulty = Difficulty("easy"),
        url = "https://codeforces.com/problemset/problem/1927/A",
        tags = tags,
        fetchedAt = fixedInstant,
    )

    @Test
    fun `save and find problem with tags`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        val problem = problem(
            "p-1",
            "Make it White",
            listOf(Tag(TagId("t-1"), "greedy"), Tag(TagId("t-2"), "implementation")),
        )
        repo.save(problem)

        val loaded = repo.findById(ProblemId("p-1"))
        assertEquals(problem, loaded)
    }

    @Test
    fun `save upserts and replaces tags`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "Old Title", listOf(Tag(TagId("t-1"), "greedy"))))
        repo.save(
            problem(
                "p-1",
                "New Title",
                listOf(Tag(TagId("t-2"), "dp"), Tag(TagId("t-3"), "math")),
            ),
        )

        val loaded = repo.findById(ProblemId("p-1"))
        assertEquals("New Title", loaded?.title)
        assertEquals(setOf("dp", "math"), loaded?.tags?.map { it.name }?.toSet())
    }

    @Test
    fun `find by id returns null when missing`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        assertNull(repo.findById(ProblemId("nope")))
    }

    @Test
    fun `find by remote id matches exactly within platform and loads tags`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "Two Sum", listOf(Tag(TagId("t-1"), "array")), platform = "leetcode", remoteId = "two-sum"))
        repo.save(problem("p-2", "Same Slug Other Platform", platform = "codeforces", remoteId = "two-sum"))

        val hit = repo.findByRemoteId(PlatformId("leetcode"), "two-sum")
        assertEquals("p-1", hit?.id?.value)
        assertEquals(listOf("array"), hit?.tags?.map { it.name })
        assertNull(repo.findByRemoteId(PlatformId("leetcode"), "no-such-slug"))
    }

    @Test
    fun `search matches title keyword within platform`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "Binary Search Tree"))
        repo.save(problem("p-2", "Make it White"))
        repo.save(problem("p-3", "Binary Tree Traversal"))
        repo.save(problem("p-4", "Binary Search on LeetCode", platform = "leetcode"))

        val hits = repo.search(PlatformId("codeforces"), "Binary Search")
        assertEquals(listOf("p-1"), hits.map { it.id.value })
    }

    @Test
    fun `search matches remote id`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "Unrelated Title"))
        val hits = repo.search(PlatformId("codeforces"), "1927")
        assertEquals(listOf("p-1"), hits.map { it.id.value })
    }

    @Test
    fun `find by tag returns associated problems`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "A", listOf(Tag(TagId("t-1"), "greedy"))))
        repo.save(problem("p-2", "B", listOf(Tag(TagId("t-1"), "greedy"))))
        repo.save(problem("p-3", "C", listOf(Tag(TagId("t-2"), "dp"))))

        val hits = repo.findByTag(TagId("t-1"))
        assertEquals(setOf("p-1", "p-2"), hits.map { it.id.value }.toSet())
    }

    @Test
    fun `detail save find and upsert`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "Title"))
        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "# 原题面", ContentFormat.MARKDOWN))

        val loaded = repo.findDetail(ProblemId("p-1"))
        assertEquals(ProblemDetail(ProblemId("p-1"), "# 原题面", ContentFormat.MARKDOWN), loaded)

        repo.saveDetail(ProblemDetail(ProblemId("p-1"), "<p>HTML 题面</p>", ContentFormat.HTML))
        assertEquals(
            ProblemDetail(ProblemId("p-1"), "<p>HTML 题面</p>", ContentFormat.HTML),
            repo.findDetail(ProblemId("p-1")),
        )
        assertNull(repo.findDetail(ProblemId("missing")))
    }

    @Test
    fun `loaded problem round trips nanosecond instant`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        repo.save(problem("p-1", "Title"))
        assertEquals(fixedInstant, repo.findById(ProblemId("p-1"))?.fetchedAt)
    }

    @Test
    fun `problem equality survives full save-load cycle`() = integrationTest { db ->
        val repo = ExposedProblemRepository(db)
        val original = problem(
            "p-9",
            "Complex Problem",
            listOf(Tag(TagId("t-1"), "greedy"), Tag(TagId("t-2"), "dp")),
        )
        repo.save(original)
        assertTrue(original == repo.findById(ProblemId("p-9")))
    }
}
