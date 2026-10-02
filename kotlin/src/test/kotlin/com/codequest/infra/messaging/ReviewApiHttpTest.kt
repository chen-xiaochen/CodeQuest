package com.codequest.infra.messaging

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.model.ReviewSchedule
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.ExposedReviewRepository
import com.codequest.infra.db.ExposedSettingsKvRepository
import com.codequest.infra.db.newTempDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [integration-ish unit] 1.7.0 F3(WP62):间隔复习四端点(today/add/complete/config)——
 * 真实 ResourceHttpServer + 真实 SQLite 仓库,固定 clock/UTC(模式照 SubmissionHistoryApiHttpTest)。
 */
class ReviewApiHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var reviews: ExposedReviewRepository
    private lateinit var settings: ExposedSettingsKvRepository
    private lateinit var problems: ExposedProblemRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** 固定 clock:today0 = 2026-10-02T00:00:00Z。 */
    private val now: Instant = Instant.parse("2026-10-02T12:00:00Z")
    private val zone = ZoneOffset.UTC
    private val day = 86_400L

    private val today0: Long = LocalDate.parse("2026-10-02").atStartOfDay(zone).toEpochSecond()

    @BeforeTest
    fun setUp() {
        val handle = newTempDatabase()
        db = handle.first
        reviews = ExposedReviewRepository(db)
        settings = ExposedSettingsKvRepository(db)
        problems = ExposedProblemRepository(db)
        server =
            ResourceHttpServer(
                reviewApi =
                    ReviewApi(
                        reviews = { reviews },
                        settings = { settings },
                        problems = { problems },
                        clock = { now },
                        zone = zone,
                    ),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        db.close()
    }

    // ------------------------------------------------------------------
    // GET /api/review/today
    // ------------------------------------------------------------------

    @Test
    fun `today groups three buckets with joined metadata and fallback`() {
        runBlocking {
            problems.save(problem("two-sum", title = "Two Sum", difficulty = "easy"))
            reviews.upsert(schedule("two-sum", plan = listOf(today0 - day)))                      // 过期且 problem 表命中
            reviews.upsert(schedule("unknown-problem", plan = listOf(today0)))                    // 今日且 problem 表缺行
            reviews.upsert(schedule("future-problem", plan = listOf(today0 + 7 * day)))           // 未来
            reviews.upsert(schedule("done-problem", plan = emptyList()))                          // 已完成 → 仅计数
        }

        val body = getJson("/api/review/today")

        assertEquals(4, body["totalTracked"]!!.jsonPrimitive.content.toInt())

        val overdue = body["overdue"]!!.jsonArray.toList().map { it.jsonObject }
        assertEquals(1, overdue.size)
        assertEquals("two-sum", overdue[0]["problemId"]!!.jsonPrimitive.content)
        assertEquals("Two Sum", overdue[0]["title"]!!.jsonPrimitive.content)   // 联查 problem 表
        assertEquals("leetcode", overdue[0]["platformId"]!!.jsonPrimitive.content)
        assertEquals("two-sum", overdue[0]["remoteId"]!!.jsonPrimitive.content) // WP64:前端开题面用
        assertEquals("easy", overdue[0]["difficulty"]!!.jsonPrimitive.content)
        assertEquals((today0 - day).toString(), overdue[0]["nextDate"]!!.jsonPrimitive.content)

        val today = body["today"]!!.jsonArray.toList().map { it.jsonObject }
        assertEquals(1, today.size)
        assertEquals("unknown-problem", today[0]["title"]!!.jsonPrimitive.content) // 查不到 → title 回退 problemId
        assertFalse(today[0].containsKey("difficulty"))                            // explicitNulls=false → 缺省不出现
        assertFalse(today[0].containsKey("platformId"))
        assertFalse(today[0].containsKey("remoteId"))

        assertEquals(listOf("future-problem"), body["upcoming"]!!.jsonArray.toList().map { it.jsonObject["problemId"]!!.jsonPrimitive.content })
    }

    // ------------------------------------------------------------------
    // POST /api/review/problems/{problemId}/add
    // ------------------------------------------------------------------

    @Test
    fun `add schedules from tomorrow and is idempotent`() {
        val first = postJson("/api/review/problems/two-sum/add", "")

        assertEquals("two-sum", first["problemId"]!!.jsonPrimitive.content)
        assertEquals(
            defaultIntervals.map { today0 + it * day }.map { it.toString() },
            first["planDates"]!!.jsonArray.toList().map { it.jsonPrimitive.content },
        )

        // 幂等:再次 add 返回现有 plan,不重排
        val second = postJson("/api/review/problems/two-sum/add", "")
        assertEquals(first["planDates"], second["planDates"])

        // 空白 problemId 段 → 400
        assertEquals(400, post("/api/review/problems//add", "").first)
    }

    // ------------------------------------------------------------------
    // POST /api/review/problems/{problemId}/complete
    // ------------------------------------------------------------------

    @Test
    fun `complete removes today and past returning remaining plan`() {
        runBlocking {
            reviews.upsert(schedule("two-sum", plan = listOf(today0 - day, today0, today0 + day)))
        }

        val body = postJson("/api/review/problems/two-sum/complete", "")

        assertEquals((today0 + day).toString(), body["planDates"]!!.jsonArray.toList().single().jsonPrimitive.content)
        assertEquals(today0.toString(), body["historyDates"]!!.jsonArray.toList().single().jsonPrimitive.content)
    }

    @Test
    fun `complete returns 404 when problem untracked`() {
        assertEquals(404, post("/api/review/problems/never-seen/complete", "").first)
        assertEquals("not-tracked", post("/api/review/problems/never-seen/complete", "").second["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // GET/PUT /api/review/config
    // ------------------------------------------------------------------

    @Test
    fun `config defaults then put valid persists and echoes`() {
        assertEquals(
            listOf("1", "4", "7", "14", "28", "60"),
            getJson("/api/review/config")["intervals"]!!.jsonArray.toList().map { it.jsonPrimitive.content },
        )

        val put = put("/api/review/config", """{"intervals":[1,3,9]}""")
        assertEquals(200, put.first)
        assertEquals(listOf("1", "3", "9"), put.second["intervals"]!!.jsonArray.toList().map { it.jsonPrimitive.content })

        // 落库可回读
        assertEquals(
            listOf("1", "3", "9"),
            getJson("/api/review/config")["intervals"]!!.jsonArray.toList().map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `config put rejects invalid body intervals and malformed json`() {
        assertEquals(400, put("/api/review/config", "not-json").first)
        assertEquals(400, put("/api/review/config", """{"intervals":[]}""").first)
        assertEquals(400, put("/api/review/config", """{"intervals":[9,3]}""").first)      // 非升序
        assertEquals(400, put("/api/review/config", """{"intervals":[0,3]}""").first)      // 非正
        assertEquals(400, put("/api/review/config", """{"intervals":[1,3,"x"]}""").first)  // 非数字
        assertEquals(
            "bad-request",
            put("/api/review/config", """{"intervals":[9,3]}""").second["error"]!!.jsonPrimitive.content,
        )
        // 非法写入未落库,GET 仍默认
        assertEquals(
            listOf("1", "4", "7", "14", "28", "60"),
            getJson("/api/review/config")["intervals"]!!.jsonArray.toList().map { it.jsonPrimitive.content },
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 与 [com.codequest.core.review.ReviewService.DEFAULT_INTERVALS] 一致(测试侧字面量,断言不自我循环)。 */
    private val defaultIntervals = listOf(1L, 4L, 7L, 14L, 28L, 60L)

    private fun schedule(
        problemId: String,
        plan: List<Long>,
    ) = ReviewSchedule(ProblemId(problemId), plan, emptyList(), Instant.parse("2026-10-01T00:00:00Z"))

    private fun problem(
        id: String,
        title: String,
        difficulty: String,
    ) = Problem(
        id = ProblemId(id),
        platformId = PlatformId("leetcode"),
        remoteId = id,
        title = title,
        difficulty = Difficulty(difficulty),
        url = "https://leetcode.com/problems/$id/",
        tags = emptyList(),
        fetchedAt = Instant.parse("2026-09-01T00:00:00Z"),
    )

    private fun request(
        path: String,
        method: String,
        body: String? = null,
    ): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): Pair<Int, JsonObject> {
        val response = request(path, "GET")
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun post(
        path: String,
        body: String,
    ): Pair<Int, JsonObject> {
        val response = request(path, "POST", body)
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun put(
        path: String,
        body: String,
    ): Pair<Int, JsonObject> {
        val response = request(path, "PUT", body)
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun getJson(path: String): JsonObject = get(path).second

    private fun postJson(
        path: String,
        body: String,
    ): JsonObject = post(path, body).second

    private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
        get() = this as kotlinx.serialization.json.JsonArray
}
