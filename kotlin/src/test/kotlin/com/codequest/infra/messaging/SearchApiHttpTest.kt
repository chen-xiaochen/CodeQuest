package com.codequest.infra.messaging

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Solution
import com.codequest.core.model.SolutionId
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.ExposedSolutionRepository
import com.codequest.infra.db.ExposedSearchStore
import com.codequest.infra.db.newTempDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration] WP30:/api/search 与 /api/tags 端点——真实 ResourceHttpServer +
 * 真实 SQLite 库,覆盖三源命中与来源标记(title/content/note)、题解笔记经
 * challenge 映射回题目、标签过滤(单/多 AND)、平台过滤、浏览模式、分页
 * total/hasMore、空结果、短关键字 LIKE 降级、参数校验 400。
 */
class SearchApiHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var problems: ExposedProblemRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        db = newTempDatabase().first
        problems = ExposedProblemRepository(db)
        server =
            ResourceHttpServer(
                searchApi = SearchApi(store = { ExposedSearchStore(db) }),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        db.close()
    }

    // ------------------------------------------------------------------
    // 夹具:三题三源数据
    // ------------------------------------------------------------------

    private fun problem(
        id: String,
        platform: String,
        title: String,
        tags: List<String>,
        fetchedAt: String,
    ) = Problem(
        id = ProblemId(id),
        platformId = PlatformId(platform),
        remoteId = "R-$id",
        title = title,
        difficulty = Difficulty("easy"),
        url = "u",
        tags = tags.map { Tag(TagId("tag:$it"), it) },
        fetchedAt = Instant.parse(fetchedAt),
    )

    private fun seedBase() =
        runBlocking {
            // A(codeforces):标题「花火矩阵」;题面含 动态规划/矩阵;标签 dp+graph;笔记含 单调栈
            problems.save(problem("p-a", "codeforces", "花火矩阵", listOf("dp", "graph"), "2026-08-01T10:00:00Z"))
            problems.saveDetail(
                ProblemDetail(ProblemId("p-a"), "本题考查动态规划与矩阵旋转的建模。", ContentFormat.MARKDOWN),
            )
            // B(leetcode):标题 Two Towers;题面 greedy;标签 greedy
            problems.save(problem("p-b", "leetcode", "Two Towers", listOf("greedy"), "2026-08-02T10:00:00Z"))
            problems.saveDetail(ProblemDetail(ProblemId("p-b"), "solve with greedy strategy", ContentFormat.MARKDOWN))
            // C(codeforces):无题面;标签 dp
            problems.save(problem("p-c", "codeforces", "最短路口", listOf("dp"), "2026-08-03T10:00:00Z"))
            // A 的题解笔记(经 challenge 映射回 p-a)
            val challenges = ExposedChallengeRepository(db)
            challenges.save(Challenge(ChallengeId("c-a"), ProblemId("p-a"), "ws-a", Instant.parse("2026-08-01T11:00:00Z")))
            ExposedSolutionRepository(db).save(
                Solution(
                    id = SolutionId("s-1"),
                    challengeId = ChallengeId("c-a"),
                    name = "解法一",
                    noteMd = "核心思路:单调栈维护区间最小值。",
                    filePath = "f.kt",
                    createdAt = Instant.EPOCH,
                ),
            )
            Unit
        }

    private fun itemIds(body: JsonObject): List<String> =
        body["items"]!!
            .jsonArray
            .map { (it.jsonObject)["remoteId"]!!.jsonPrimitive.content }

    private fun sourcesOf(
        body: JsonObject,
        remoteId: String,
    ): List<String> =
        body["items"]!!
            .jsonArray
            .map { it.jsonObject }
            .first { it["remoteId"]!!.jsonPrimitive.content == remoteId }["matchedSources"]!!
            .jsonArray
            .map { it.jsonPrimitive.content }

    // ------------------------------------------------------------------
    // 三源命中与来源标记
    // ------------------------------------------------------------------

    @Test
    fun `chinese keyword hits problem content with content source`() {
        seedBase()
        val body = getJson("/api/search?keyword=动态规划")

        assertEquals(listOf("R-p-a"), itemIds(body))
        assertEquals(listOf("content"), sourcesOf(body, "R-p-a"))
        assertEquals("1", body["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `note keyword surfaces problem with note source`() {
        seedBase()
        val body = getJson("/api/search?keyword=单调栈")

        assertEquals(listOf("R-p-a"), itemIds(body))
        assertEquals(listOf("note"), sourcesOf(body, "R-p-a"))
    }

    @Test
    fun `title keyword hits with title source`() {
        seedBase()
        val body = getJson("/api/search?keyword=花火矩阵")

        assertEquals(listOf("R-p-a"), itemIds(body))
        assertEquals(listOf("title"), sourcesOf(body, "R-p-a"))
    }

    @Test
    fun `keyword matching title and content aggregates both sources`() {
        seedBase()
        // 「矩阵」同时命中 p-a 标题(花火矩阵)与题面(矩阵旋转)
        val body = getJson("/api/search?keyword=矩阵")

        assertEquals(listOf("R-p-a"), itemIds(body))
        assertEquals(setOf("title", "content"), sourcesOf(body, "R-p-a").toSet())
    }

    @Test
    fun `short keyword falls back to like and still matches content`() {
        seedBase()
        // 「花火」2 字符 < trigram 最小 3 字符:题面/笔记源走 LIKE 降级,标题源本就 LIKE
        val body = getJson("/api/search?keyword=花火")

        assertEquals(listOf("R-p-a"), itemIds(body))
        assertEquals(listOf("title"), sourcesOf(body, "R-p-a"))
    }

    @Test
    fun `fts missing falls back to like on content and note sources`() {
        seedBase()
        // 模拟 FTS5 不可用:删除两张虚拟表(触发器此后不再命中,本用例只读)
        runBlocking {
            db.write {
                exec("DROP TABLE problem_content_fts")
                exec("DROP TABLE solution_note_fts")
            }
        }

        val contentBody = getJson("/api/search?keyword=动态规划")
        assertEquals(listOf("R-p-a"), itemIds(contentBody))
        assertEquals(listOf("content"), sourcesOf(contentBody, "R-p-a"))

        val noteBody = getJson("/api/search?keyword=单调栈")
        assertEquals(listOf("R-p-a"), itemIds(noteBody))
        assertEquals(listOf("note"), sourcesOf(noteBody, "R-p-a"))
    }

    // ------------------------------------------------------------------
    // 标签过滤(AND 语义)与平台过滤
    // ------------------------------------------------------------------

    @Test
    fun `single tag filter scopes results`() {
        seedBase()
        val body = getJson("/api/search?tags=dp")

        assertEquals(setOf("R-p-a", "R-p-c"), itemIds(body).toSet())
        assertEquals("2", body["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multi tag filter requires all tags`() {
        seedBase()
        // dp+graph 仅 p-a 同时具备;dp+greedy 无题目同时具备
        assertEquals(listOf("R-p-a"), itemIds(getJson("/api/search?tags=dp,graph")))
        assertEquals(0, itemIds(getJson("/api/search?tags=dp,greedy")).size)
    }

    @Test
    fun `tag filter combines with keyword`() {
        seedBase()
        // 「矩阵」命中 p-a,再要求标签 greedy → 空结果
        assertEquals(0, itemIds(getJson("/api/search?keyword=矩阵&tags=greedy")).size)
    }

    @Test
    fun `platform filter scopes browse results`() {
        seedBase()
        val body = getJson("/api/search?platform=leetcode")

        assertEquals(listOf("R-p-b"), itemIds(body))
        assertEquals("1", body["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown platform or tag yields empty result without error`() {
        seedBase()
        assertEquals(200, get("/api/search?platform=atcoder").first)
        assertEquals(0, itemIds(getJson("/api/search?platform=atcoder")).size)
        assertEquals(0, itemIds(getJson("/api/search?tags=nope")).size)
    }

    // ------------------------------------------------------------------
    // 浏览模式、排序、分页与空结果
    // ------------------------------------------------------------------

    @Test
    fun `browse mode without keyword returns recent problems with empty sources`() {
        seedBase()
        val body = getJson("/api/search")

        // fetched_at 倒序:p-c(08-03)> p-b(08-02)> p-a(08-01)
        assertEquals(listOf("R-p-c", "R-p-b", "R-p-a"), itemIds(body))
        assertEquals("3", body["total"]!!.jsonPrimitive.content)
        assertTrue(body["items"]!!.jsonArray.all { it.jsonObject["matchedSources"]!!.jsonArray.isEmpty() })
    }

    @Test
    fun `pagination slices items and reports total and hasMore`() {
        seedBase()
        val first = getJson("/api/search?limit=2")
        assertEquals(listOf("R-p-c", "R-p-b"), itemIds(first))
        assertEquals("3", first["total"]!!.jsonPrimitive.content)
        assertEquals("true", first["hasMore"]!!.jsonPrimitive.content)

        val second = getJson("/api/search?limit=2&offset=2")
        assertEquals(listOf("R-p-a"), itemIds(second))
        assertEquals("false", second["hasMore"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown keyword yields empty items with zero total`() {
        seedBase()
        val body = getJson("/api/search?keyword=完全不存在的关键词")

        assertEquals("0", body["total"]!!.jsonPrimitive.content)
        assertTrue(itemIds(body).isEmpty())
    }

    @Test
    fun `empty database yields empty browse list`() {
        val body = getJson("/api/search")
        assertEquals("0", body["total"]!!.jsonPrimitive.content)
        assertTrue(itemIds(body).isEmpty())
    }

    @Test
    fun `invalid limit or offset maps to 400 with bad-request code`() {
        assertEquals(400, get("/api/search?limit=0").first)
        assertEquals(400, get("/api/search?limit=101").first)
        assertEquals(400, get("/api/search?limit=abc").first)
        assertEquals(400, get("/api/search?offset=-1").first)
        assertEquals("bad-request", get("/api/search?limit=0").second["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // /api/tags
    // ------------------------------------------------------------------

    @Test
    fun `tags endpoint lists names with counts ordered by count desc`() {
        seedBase()
        val body = getJson("/api/tags")

        val tags = body["tags"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("dp" to "2", "graph" to "1", "greedy" to "1"), tags.map { it["name"]!!.jsonPrimitive.content to it["count"]!!.jsonPrimitive.content })
    }

    @Test
    fun `tags endpoint filters by platform`() {
        seedBase()
        val tags = getJson("/api/tags?platform=leetcode")["tags"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("greedy" to "1"), tags.map { it["name"]!!.jsonPrimitive.content to it["count"]!!.jsonPrimitive.content })
    }

    // ------------------------------------------------------------------
    // HTTP 工具(形态同 StatisticsApiHttpTest)
    // ------------------------------------------------------------------

    private fun getRaw(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): Pair<Int, JsonObject> {
        val response = getRaw(path)
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun getJson(path: String): JsonObject = get(path).second
}
