package com.codequest.infra.messaging

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.OJException
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedSubmissionRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionErrorCase
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
 * [integration-ish unit] WP36:GET /api/submission/{id}/detail——真实 ResourceHttpServer +
 * 真实 SQLite 仓库 + 假平台列表:本地字段合并平台详情(错误用例三件套/extras/viewUrl)、
 * problemRemoteId 反解透传、错误映射照 SolutionsApi 模式(auth-expired 401 / oj-error 502)、
 * 非 detail 能力平台缺省、404/400。
 */
class SubmissionsApiDetailHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var submissions: ExposedSubmissionRepository
    private lateinit var challenges: ExposedChallengeRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** 详情调用记录(断言 problemRemoteId 反解透传)。 */
    private val recordedRemoteIds = mutableListOf<Pair<String, String>>()

    private inner class FakeDetailPlatform(
        override val id: PlatformId,
        private val detail: SubmissionDetail?,
        private val viewUrl: String? = null,
        private val error: RuntimeException? = null,
    ) : OJPlatform,
        SubmissionDetailCapable {
        override val domain: String = "${id.value}.example.com"
        override val info: PlatformInfo = PlatformInfo(displayName = id.value)

        override suspend fun fetchSubmissionDetail(
            remoteSubmissionId: String,
            problemRemoteId: String,
        ): SubmissionDetail? {
            recordedRemoteIds += remoteSubmissionId to problemRemoteId
            error?.let { throw it }
            return detail
        }

        override fun submissionViewUrl(
            remoteSubmissionId: String,
            problemRemoteId: String,
        ): String? = viewUrl
    }

    private inner class PlainPlatform(
        override val id: PlatformId,
    ) : OJPlatform {
        override val domain: String = "${id.value}.example.com"
        override val info: PlatformInfo = PlatformInfo(displayName = id.value)
    }

    @BeforeTest
    fun setUp() {
        db = newTempDatabase().first
        submissions = ExposedSubmissionRepository(db)
        challenges = ExposedChallengeRepository(db)
    }

    @AfterTest
    fun tearDown() {
        if (this::server.isInitialized) server.stop()
        db.close()
        recordedRemoteIds.clear()
    }

    private fun startServer(platforms: List<OJPlatform>) {
        server =
            ResourceHttpServer(
                submissionsApi =
                    SubmissionsApi(
                        submissions = { submissions },
                        challenges = { challenges },
                        platforms = { platforms },
                    ),
            )
        server.start()
    }

    private fun seed() {
        runBlocking {
            challenges.save(Challenge(ChallengeId("c-1"), ProblemId("leetcode:two-sum"), "Two Sum", Instant.parse("2026-08-15T08:00:00Z")))
            submissions.save(
                Submission(
                    id = SubmissionId("s-1"),
                    challengeId = ChallengeId("c-1"),
                    platformId = PlatformId("leetcode"),
                    remoteSubmissionId = "42",
                    language = "Kotlin",
                    codeSnapshot = "class Solution",
                    verdict = SubmissionResult.WrongAnswer,
                    timeMs = 12,
                    memoryKb = 16384,
                    submittedAt = Instant.parse("2026-08-15T10:00:00Z"),
                ),
            )
        }
    }

    private val waDetail =
        SubmissionDetail(
            verdict = SubmissionResult.WrongAnswer,
            timeMs = 68,
            memoryKb = 4120000,
            language = "Kotlin",
            message = "Wrong Answer",
            errorCase = SubmissionErrorCase(input = "nums = [2,7,11,15]", expectedOutput = "[0,1]", actualOutput = "[2,2]"),
            extras = mapOf("testcases" to "12/14", "runtimePercentile" to "42.10%"),
        )

    @Test
    fun `merges local fields with platform detail error case extras and view url`() {
        seed()
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), waDetail, viewUrl = "https://leetcode.com/problems/two-sum/submissions/42/")))

        val body = getJson("/api/submission/s-1/detail")

        assertEquals("s-1", body["id"]!!.jsonPrimitive.content)
        assertEquals("Two Sum", body["challengeName"]!!.jsonPrimitive.content)
        assertEquals("leetcode", body["platformId"]!!.jsonPrimitive.content)
        assertEquals("42", body["remoteSubmissionId"]!!.jsonPrimitive.content)
        // 平台侧最新值覆盖本地快照(旧 12/16384 → 68/4120000)
        assertEquals("WrongAnswer", body["verdict"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("68", body["timeMs"]!!.jsonPrimitive.content)
        assertEquals("4120000", body["memoryKb"]!!.jsonPrimitive.content)
        assertEquals("Kotlin", body["language"]!!.jsonPrimitive.content)
        assertEquals("2026-08-15T10:00:00Z", body["submittedAt"]!!.jsonPrimitive.content)
        assertEquals("class Solution", body["code"]!!.jsonPrimitive.content)
        assertEquals("Wrong Answer", body["message"]!!.jsonPrimitive.content)
        val errorCase = body["errorCase"]!!.jsonObject
        assertEquals("nums = [2,7,11,15]", errorCase["input"]!!.jsonPrimitive.content)
        assertEquals("[0,1]", errorCase["expectedOutput"]!!.jsonPrimitive.content)
        assertEquals("[2,2]", errorCase["actualOutput"]!!.jsonPrimitive.content)
        val extras = body["extras"]!!.jsonObject
        assertEquals("12/14", extras["testcases"]!!.jsonPrimitive.content)
        assertEquals("42.10%", extras["runtimePercentile"]!!.jsonPrimitive.content)
        assertEquals("https://leetcode.com/problems/two-sum/submissions/42/", body["viewUrl"]!!.jsonPrimitive.content)
        // challenge.problemId("leetcode:two-sum") 反解出的 remoteId 透传给平台
        assertEquals(listOf("42" to "two-sum"), recordedRemoteIds)
    }

    @Test
    fun `falls back to local snapshot when platform reports no detail`() {
        seed()
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null)))

        val (status, body) = get("/api/submission/s-1/detail")

        assertEquals(200, status)
        assertEquals("WrongAnswer", body["verdict"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("12", body["timeMs"]!!.jsonPrimitive.content)
        assertEquals("16384", body["memoryKb"]!!.jsonPrimitive.content)
        assertNull(body["errorCase"])
        assertNull(body["message"])
        assertNull(body["extras"])
        assertNull(body["viewUrl"])
    }

    @Test
    fun `non detail capable platform still serves local snapshot`() {
        seed()
        startServer(listOf(PlainPlatform(PlatformId("leetcode"))))

        val (status, body) = get("/api/submission/s-1/detail")

        assertEquals(200, status)
        assertEquals("Kotlin", body["language"]!!.jsonPrimitive.content)
        assertEquals("class Solution", body["code"]!!.jsonPrimitive.content)
        assertNull(body["errorCase"])
        assertTrue(recordedRemoteIds.isEmpty())
    }

    @Test
    fun `auth expired from platform maps to 401`() {
        seed()
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null, error = AuthExpiredException("LeetCode 登录已过期"))))

        val (status, body) = get("/api/submission/s-1/detail")

        assertEquals(401, status)
        assertEquals("auth-expired", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generic oj error from platform maps to 502`() {
        seed()
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null, error = OJException("LeetCode 详情拉取失败"))))

        val (status, body) = get("/api/submission/s-1/detail")

        assertEquals(502, status)
        assertEquals("oj-error", body["error"]!!.jsonPrimitive.content)
        assertEquals("LeetCode 详情拉取失败", body["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing challenge row degrades to empty problem remote id`() {
        runBlocking {
            submissions.save(
                Submission(
                    SubmissionId("s-orphan"),
                    ChallengeId("c-gone"),
                    PlatformId("leetcode"),
                    "43",
                    "Kotlin",
                    "class Solution",
                    SubmissionResult.Accepted,
                    1,
                    2048,
                    Instant.parse("2026-08-15T10:00:00Z"),
                ),
            )
        }
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null)))

        val (status, body) = get("/api/submission/s-orphan/detail")

        assertEquals(200, status)
        assertEquals("43" to "", recordedRemoteIds.single())
        assertNull(body["challengeName"])
        assertNull(body["viewUrl"])
    }

    @Test
    fun `unknown submission id maps to 404`() {
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null)))

        val (status, body) = get("/api/submission/nope/detail")

        assertEquals(404, status)
        assertEquals("not-found", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty or malformed id segment maps to 400`() {
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null)))

        assertEquals(400, get("/api/submission//detail").first)
        assertEquals(400, get("/api/submission/a/b/detail").first)
    }

    @Test
    fun `detail route does not hijack submissions list`() {
        seed()
        startServer(listOf(FakeDetailPlatform(PlatformId("leetcode"), detail = null)))

        assertEquals(200, get("/api/submissions").first)
        assertEquals(404, get("/api/submission/s-1").first)
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
}
