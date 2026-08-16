package com.codequest.infra.messaging

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedChallengeRepository
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP21:submissions 数据端点——真实 ResourceHttpServer + 真实
 * SQLite 仓库,覆盖列表/按 challenge 过滤/分页 hasMore/错误参数/verdict 多态序列化。
 */
class SubmissionsApiHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var submissions: ExposedSubmissionRepository
    private lateinit var challenges: ExposedChallengeRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        db = newTempDatabase().first
        submissions = ExposedSubmissionRepository(db)
        challenges = ExposedChallengeRepository(db)
        server =
            ResourceHttpServer(
                submissionsApi =
                    SubmissionsApi(
                        submissions = { submissions },
                        challenges = { challenges },
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

    private fun challenge(
        id: String,
        name: String,
    ) = Challenge(ChallengeId(id), ProblemId("p-$id"), name, Instant.parse("2026-08-15T08:00:00Z"))

    private fun submission(
        id: String,
        challengeId: String,
        verdict: SubmissionResult = SubmissionResult.Accepted,
        submittedAt: Instant,
        timeMs: Int? = 12,
        memoryKb: Int? = 16384,
    ) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId(challengeId),
        platformId = PlatformId("leetcode"),
        remoteSubmissionId = "remote-$id",
        language = "Kotlin",
        codeSnapshot = "class Solution",
        verdict = verdict,
        timeMs = timeMs,
        memoryKb = memoryKb,
        submittedAt = submittedAt,
    )

    private fun seed(vararg rows: Submission) {
        runBlocking {
            challenges.save(challenge("c-1", "Two Sum"))
            challenges.save(challenge("c-2", "Add Two Numbers"))
            rows.forEach { submissions.save(it) }
        }
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    @Test
    fun `lists submissions newest first with challenge names`() {
        seed(
            submission("s-1", "c-1", submittedAt = Instant.parse("2026-08-15T08:00:00Z")),
            submission("s-2", "c-2", verdict = SubmissionResult.WrongAnswer, submittedAt = Instant.parse("2026-08-15T09:00:00Z")),
            submission("s-3", "c-1", verdict = SubmissionResult.Judging, submittedAt = Instant.parse("2026-08-15T10:00:00Z")),
        )

        val body = getJson("/api/submissions")

        val items = body.getAsJsonArray("items")
        assertEquals(3, items.size)
        assertEquals(listOf("s-3", "s-2", "s-1"), items.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val first = items[0].jsonObject
        assertEquals("c-1", first["challengeId"]!!.jsonPrimitive.content)
        assertEquals("Two Sum", first["challengeName"]!!.jsonPrimitive.content)
        assertEquals("leetcode", first["platformId"]!!.jsonPrimitive.content)
        assertEquals("remote-s-3", first["remoteSubmissionId"]!!.jsonPrimitive.content)
        assertEquals("Kotlin", first["language"]!!.jsonPrimitive.content)
        assertEquals("Judging", first["verdict"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("12", first["timeMs"]!!.jsonPrimitive.content)
        assertEquals("16384", first["memoryKb"]!!.jsonPrimitive.content)
        assertEquals("2026-08-15T10:00:00Z", first["submittedAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `challenge name is null when the challenge row is gone`() {
        runBlocking { challenges.save(challenge("c-1", "Two Sum")) }
        runBlocking {
            submissions.save(
                submission("s-1", "c-ghost", submittedAt = Instant.parse("2026-08-15T08:00:00Z")),
            )
        }

        val body = getJson("/api/submissions")

        val first = body.getAsJsonArray("items")[0].jsonObject
        assertEquals("c-ghost", first["challengeId"]!!.jsonPrimitive.content)
        assertNull(first["challengeName"])
    }

    @Test
    fun `polymorphic verdict keeps payload fields`() {
        seed(submission("s-1", "c-1", verdict = SubmissionResult.Unknown("Time Limit"), submittedAt = Instant.parse("2026-08-15T08:00:00Z")))

        val first = getJson("/api/submissions").getAsJsonArray("items")[0].jsonObject

        assertEquals("Unknown", first["verdict"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Time Limit", first["verdict"]!!.jsonObject["raw"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 过滤与分页
    // ------------------------------------------------------------------

    @Test
    fun `filters by challenge id`() {
        seed(
            submission("s-1", "c-1", submittedAt = Instant.parse("2026-08-15T08:00:00Z")),
            submission("s-2", "c-2", submittedAt = Instant.parse("2026-08-15T09:00:00Z")),
        )

        val body = getJson("/api/submissions?challengeId=c-2")

        assertEquals("c-2", body["challengeId"]!!.jsonPrimitive.content)
        val items = body.getAsJsonArray("items")
        assertEquals(1, items.size)
        assertEquals("s-2", items[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `paginates with limit and offset and reports hasMore`() {
        seed(
            submission("s-1", "c-1", submittedAt = Instant.parse("2026-08-15T08:00:00Z")),
            submission("s-2", "c-1", submittedAt = Instant.parse("2026-08-15T08:01:00Z")),
            submission("s-3", "c-1", submittedAt = Instant.parse("2026-08-15T08:02:00Z")),
        )

        val page1 = getJson("/api/submissions?limit=2")
        assertEquals(listOf("s-3", "s-2"), page1.getAsJsonArray("items").map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(true, page1["hasMore"]!!.jsonPrimitive.content.toBoolean())

        val page2 = getJson("/api/submissions?limit=2&offset=2")
        assertEquals(listOf("s-1"), page2.getAsJsonArray("items").map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(false, page2["hasMore"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `same instant submissions keep stable id order`() {
        val same = Instant.parse("2026-08-15T08:00:00Z")
        seed(
            submission("s-b", "c-1", submittedAt = same),
            submission("s-a", "c-1", submittedAt = same),
        )

        val items = getJson("/api/submissions").getAsJsonArray("items")

        assertEquals(listOf("s-a", "s-b"), items.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    // ------------------------------------------------------------------
    // 错误映射
    // ------------------------------------------------------------------

    @Test
    fun `invalid limit or offset maps to 400`() {
        assertEquals(400, get("/api/submissions?limit=0").first)
        assertEquals(400, get("/api/submissions?limit=abc").first)
        assertEquals(400, get("/api/submissions?offset=-1").first)
        assertEquals("bad-request", get("/api/submissions?limit=0").second["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown api path still maps to 404`() {
        assertEquals(404, get("/api/nope").first)
    }

    @Test
    fun `empty result yields empty items without error`() {
        val body = getJson("/api/submissions")

        assertEquals(0, body.getAsJsonArray("items").size)
        assertEquals(false, body["hasMore"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(body["challengeId"] == null || body["challengeId"]!!.jsonPrimitive.content == "null")
    }

    // ------------------------------------------------------------------
    // HTTP 工具
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

    private fun JsonObject.getAsJsonArray(name: String): List<kotlinx.serialization.json.JsonElement> =
        this[name]!!.jsonArray.toList()

    private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
        get() = this as kotlinx.serialization.json.JsonArray
}
