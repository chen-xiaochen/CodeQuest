package com.codequest.infra.messaging

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.ExposedProblemStatusStore
import com.codequest.infra.db.ExposedSearchStore
import com.codequest.infra.db.ExposedSubmissionRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertTrue

/**
 * [integration] WP37:列表端点状态列——/api/problems 与 /api/search 每项带
 * status(本地聚合:AC / TRIED / UNSOLVED,多 challenge 取最高、跨平台隔离)与
 * paidOnly(恒 false,见 DTO KDoc 的 SPI 透传边界)。真实 ResourceHttpServer +
 * 真实 SQLite 库;题目列表源用受控假平台(不经注册表)。
 */
class ProblemStatusHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var problems: ExposedProblemRepository
    private lateinit var challenges: ExposedChallengeRepository
    private lateinit var submissions: ExposedSubmissionRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** 受控假平台:固定四题,remoteId 与预置提交一一对应。 */
    private object FakePlatform : OJPlatform, ProblemQueryCapable {
        override val id = PlatformId("fakeoj")
        override val domain = "fakeoj.example.com"
        override val info = PlatformInfo(displayName = "FakeOJ", templateStyle = TemplateStyle.ACM)

        val remoteIds = listOf("ac-problem", "tried-problem", "fresh-problem", "multi-problem")

        override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
            flow {
                remoteIds.forEach { remote ->
                    emit(ProblemSummary(remoteId = remote, title = "Title $remote", difficulty = Difficulty("easy"), url = "https://fakeoj/$remote"))
                }
            }

        override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement =
            ProblemStatement(remoteId, "statement", com.codequest.core.model.ContentFormat.MARKDOWN)
    }

    @BeforeTest
    fun setUp() {
        val (database, _) = newTempDatabase()
        db = database
        problems = ExposedProblemRepository(db)
        challenges = ExposedChallengeRepository(db)
        submissions = ExposedSubmissionRepository(db)
        server =
            ResourceHttpServer(
                problemApi =
                    ProblemApi(
                        store = { problems },
                        statuses = { ExposedProblemStatusStore(db) },
                        platforms = { listOf(FakePlatform) },
                    ),
                searchApi =
                    SearchApi(
                        store = { ExposedSearchStore(db) },
                        statuses = { ExposedProblemStatusStore(db) },
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
    // /api/problems:每项带 status + paidOnly
    // ------------------------------------------------------------------

    @Test
    fun `problems items carry aggregated status and paidOnly flag`() {
        seedFakePlatformHistory()

        val items = itemsOf("/api/problems?platform=fakeoj")

        assertEquals(
            mapOf(
                "ac-problem" to "AC",
                "tried-problem" to "TRIED",
                "fresh-problem" to "UNSOLVED",
                "multi-problem" to "AC",
            ),
            items.associate { it["remoteId"]!!.jsonPrimitive.content to it["status"]!!.jsonPrimitive.content },
        )
        // SPI ProblemSummary 未透传会员标记(见 ProblemItemDto.paidOnly KDoc):字段恒在且为 false
        assertTrue(items.all { it["paidOnly"]!!.jsonPrimitive.content == "false" })
    }

    @Test
    fun `problems status reflects history saved before or after list fetch`() {
        // 先拉一次列表(落库主档)再补提交历史,再拉一次:状态即时跟进(聚合按请求读库)
        getJson("/api/problems?platform=fakeoj")
        seedFakePlatformHistory()
        val items = itemsOf("/api/problems?platform=fakeoj")

        assertEquals(
            "TRIED",
            items.first { it["remoteId"]!!.jsonPrimitive.content == "tried-problem" }["status"]!!.jsonPrimitive.content,
        )
    }

    // ------------------------------------------------------------------
    // /api/search:本地题目带 status + paidOnly,跨平台隔离
    // ------------------------------------------------------------------

    @Test
    fun `search items carry aggregated status`() {
        seedSearchCorpus()

        val items = itemsOf("/api/search")

        assertEquals(
            mapOf(
                "leetcode:solved" to "AC",
                "leetcode:attempted" to "TRIED",
                "leetcode:untouched" to "UNSOLVED",
            ),
            items.associate { it["platform"]!!.jsonPrimitive.content + ":" + it["remoteId"]!!.jsonPrimitive.content to it["status"]!!.jsonPrimitive.content },
        )
        assertTrue(items.all { it["paidOnly"]!!.jsonPrimitive.content == "false" })
    }

    @Test
    fun `search status stays isolated across platforms with same remoteId`() {
        // 同 remoteId 两平台:仅 leetcode 一侧有 AC 提交,codeforces 侧保持 UNSOLVED
        runBlocking {
            problems.save(problem("leetcode", "dup", "2026-08-01T10:00:00Z"))
            problems.save(problem("codeforces", "dup", "2026-08-02T10:00:00Z"))
            seedHistory("leetcode:dup", "c-dup", SubmissionResult.Accepted)
        }

        val items = itemsOf("/api/search")
        val byPlatform =
            items.associate { it["platform"]!!.jsonPrimitive.content to it["status"]!!.jsonPrimitive.content }

        assertEquals("AC", byPlatform["leetcode"])
        assertEquals("UNSOLVED", byPlatform["codeforces"])
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** fakeoj 四题的提交历史:AC / WA / 无 / 双工作区(WA+AC→AC)。 */
    private fun seedFakePlatformHistory() =
        runBlocking {
            seedHistory("fakeoj:ac-problem", "c-fake-1", SubmissionResult.Accepted)
            seedHistory("fakeoj:tried-problem", "c-fake-2", SubmissionResult.WrongAnswer)
            seedHistory("fakeoj:multi-problem", "c-fake-3", SubmissionResult.WrongAnswer)
            seedHistory("fakeoj:multi-problem", "c-fake-4", SubmissionResult.Accepted)
        }

    /** /api/search 语料:三题(AC / TRIED / 未做)。 */
    private fun seedSearchCorpus() =
        runBlocking {
            problems.save(problem("leetcode", "solved", "2026-08-01T10:00:00Z"))
            problems.save(problem("leetcode", "attempted", "2026-08-02T10:00:00Z"))
            problems.save(problem("leetcode", "untouched", "2026-08-03T10:00:00Z"))
            seedHistory("leetcode:solved", "c-s-1", SubmissionResult.Accepted)
            seedHistory("leetcode:attempted", "c-s-2", SubmissionResult.TimeLimitExceeded)
        }

    private fun problem(
        platform: String,
        remoteId: String,
        fetchedAt: String,
    ) = Problem(
        id = ProblemId("$platform:$remoteId"),
        platformId = PlatformId(platform),
        remoteId = remoteId,
        title = "Title $remoteId",
        difficulty = Difficulty("easy"),
        url = "https://x/$remoteId",
        tags = listOf(Tag(TagId("tag:dp"), "dp")),
        fetchedAt = Instant.parse(fetchedAt),
    )

    private suspend fun seedHistory(
        problemId: String,
        challengeId: String,
        verdict: SubmissionResult,
    ) {
        challenges.save(Challenge(ChallengeId(challengeId), ProblemId(problemId), "ws-$challengeId", Instant.EPOCH))
        submissions.save(
            Submission(
                id = SubmissionId("s-$challengeId"),
                challengeId = ChallengeId(challengeId),
                platformId = PlatformId(problemId.substringBefore(':')),
                remoteSubmissionId = "remote-$challengeId",
                language = "Kotlin",
                codeSnapshot = "class Solution",
                verdict = verdict,
                timeMs = null,
                memoryKb = null,
                submittedAt = Instant.EPOCH,
            ),
        )
    }

    // ------------------------------------------------------------------
    // HTTP 工具(形态同 SearchApiHttpTest;jsonArray 用显式转换,避开
    // 同包测试文件的 file-private 同名扩展可见性冲突)
    // ------------------------------------------------------------------

    /** items 数组 → JsonObject 列表(两个端点响应形状一致)。 */
    private fun itemsOf(path: String): List<JsonObject> =
        (getJson(path)["items"] as JsonArray).map { it.jsonObject }

    private fun getJson(path: String): JsonObject {
        val response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode(), response.body())
        return Json.parseToJsonElement(response.body()).jsonObject
    }
}
