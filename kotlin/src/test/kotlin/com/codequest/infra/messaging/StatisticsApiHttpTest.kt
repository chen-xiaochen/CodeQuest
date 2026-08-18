package com.codequest.infra.messaging

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedSubmissionRepository
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
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP29:/api/stats 端点——真实 ResourceHttpServer + 真实
 * SQLite 仓库,覆盖 summary/heatmap/trend 三段结构、平台过滤、days 窗口、
 * 参数校验 400、空库零值。
 */
class StatisticsApiHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var submissions: ExposedSubmissionRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** 固定时钟:today = 2026-08-18(UTC),窗口断言确定。 */
    private var now: Instant = Instant.parse("2026-08-18T12:00:00Z")

    @BeforeTest
    fun setUp() {
        db = newTempDatabase().first
        submissions = ExposedSubmissionRepository(db)
        server =
            ResourceHttpServer(
                statsApi =
                    StatisticsApi(
                        submissions = { submissions },
                        clock = { now },
                        zone = ZoneOffset.UTC,
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
    // 夹具
    // ------------------------------------------------------------------

    private fun submission(
        id: String,
        day: String,
        platform: String = "leetcode",
        verdict: SubmissionResult = SubmissionResult.Accepted,
    ) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId("c-1"),
        platformId = PlatformId(platform),
        remoteSubmissionId = "remote-$id",
        language = "Kotlin",
        codeSnapshot = "class Solution",
        verdict = verdict,
        timeMs = null,
        memoryKb = null,
        submittedAt = Instant.parse("${day}T10:00:00Z"),
    )

    private fun seed(vararg rows: Submission) {
        runBlocking { rows.forEach { submissions.save(it) } }
    }

    // ------------------------------------------------------------------
    // 三段结构与口径
    // ------------------------------------------------------------------

    @Test
    fun `serves summary heatmap and trend with day window`() {
        seed(
            submission("s-1", "2026-08-18"),
            submission("s-2", "2026-08-18", verdict = SubmissionResult.WrongAnswer),
            submission("s-3", "2026-08-17"),
            submission("s-4", "2026-08-10"),
        )

        val body = getJson("/api/stats?days=8")

        assertEquals("8", body["days"]!!.jsonPrimitive.content)
        val summary = body["summary"]!!.jsonObject
        assertEquals("4", summary["totalSubmissions"]!!.jsonPrimitive.content)
        assertEquals("3", summary["acceptedSubmissions"]!!.jsonPrimitive.content)
        assertEquals("0.75", summary["acceptanceRate"]!!.jsonPrimitive.content)
        assertEquals("2", summary["currentStreakDays"]!!.jsonPrimitive.content)
        assertEquals("2", summary["longestStreakDays"]!!.jsonPrimitive.content)

        val heatmap = body.getArray("heatmap")
        assertEquals(8, heatmap.size)
        // 升序,末位=今天;08-18 计 2、08-17 计 1、08-10 在窗外(窗口起点 08-11)
        assertEquals("2026-08-11", heatmap[0].jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("0", heatmap[0].jsonObject["count"]!!.jsonPrimitive.content)
        assertEquals("2026-08-18", heatmap[7].jsonObject["date"]!!.jsonPrimitive.content)
        assertEquals("2", heatmap[7].jsonObject["count"]!!.jsonPrimitive.content)
        assertEquals("1", heatmap[6].jsonObject["count"]!!.jsonPrimitive.content)

        // trend 窗口 = min(days, 30) = 8,与 heatmap 同窗口
        val trend = body.getArray("trend")
        assertEquals(8, trend.size)
        assertEquals("2", trend[7].jsonObject["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trend window caps at 30 days when heatmap window is a year`() {
        seed(submission("s-1", "2026-08-18"))

        val body = getJson("/api/stats")

        assertEquals("365", body["days"]!!.jsonPrimitive.content)
        assertEquals(365, body.getArray("heatmap").size)
        assertEquals(30, body.getArray("trend").size)
        assertEquals(
            "2026-08-18",
            body.getArray("heatmap").last().jsonObject["date"]!!.jsonPrimitive.content,
        )
    }

    // ------------------------------------------------------------------
    // 平台过滤
    // ------------------------------------------------------------------

    @Test
    fun `platform filter scopes the aggregation`() {
        seed(
            submission("s-1", "2026-08-18", platform = "leetcode"),
            submission("s-2", "2026-08-17", platform = "codeforces"),
        )

        val body = getJson("/api/stats?platform=codeforces&days=4")

        assertEquals("codeforces", body["platform"]!!.jsonPrimitive.content)
        assertEquals("1", body["summary"]!!.jsonObject["totalSubmissions"]!!.jsonPrimitive.content)
        val heatmap = body.getArray("heatmap")
        // 窗口 08-15..08-18:codeforces 提交在 08-17 = 第 3 格
        assertEquals("1", heatmap[2].jsonObject["count"]!!.jsonPrimitive.content)
        assertEquals("0", heatmap[3].jsonObject["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown platform filter yields zeros without error`() {
        seed(submission("s-1", "2026-08-18"))

        val body = getJson("/api/stats?platform=atcoder&days=4")

        assertEquals("0", body["summary"]!!.jsonObject["totalSubmissions"]!!.jsonPrimitive.content)
        assertEquals(0, body.getArray("heatmap").sumOf { it.jsonObject["count"]!!.jsonPrimitive.content.toInt() })
    }

    // ------------------------------------------------------------------
    // 空库与参数校验
    // ------------------------------------------------------------------

    @Test
    fun `empty database yields zero summary with full window`() {
        val body = getJson("/api/stats?days=5")

        val summary = body["summary"]!!.jsonObject
        assertEquals("0", summary["totalSubmissions"]!!.jsonPrimitive.content)
        assertEquals("0.0", summary["acceptanceRate"]!!.jsonPrimitive.content)
        assertEquals("0", summary["currentStreakDays"]!!.jsonPrimitive.content)
        assertEquals(5, body.getArray("heatmap").size)
        assertTrue(body.getArray("heatmap").all { it.jsonObject["count"]!!.jsonPrimitive.content == "0" })
    }

    @Test
    fun `invalid days maps to 400 with bad-request code`() {
        assertEquals(400, get("/api/stats?days=0").first)
        assertEquals(400, get("/api/stats?days=-3").first)
        assertEquals(400, get("/api/stats?days=abc").first)
        assertEquals(400, get("/api/stats?days=9999").first)
        assertEquals("bad-request", get("/api/stats?days=0").second["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // HTTP 工具(形态同 SubmissionsApiHttpTest)
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

    private fun JsonObject.getArray(name: String): List<kotlinx.serialization.json.JsonElement> =
        this[name]!!.let { it as kotlinx.serialization.json.JsonArray }.toList()
}
