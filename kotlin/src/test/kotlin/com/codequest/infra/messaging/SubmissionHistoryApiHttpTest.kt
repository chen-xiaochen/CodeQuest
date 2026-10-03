package com.codequest.infra.messaging

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedRemoteSubmissionRepository
import com.codequest.infra.db.ExposedSubmissionRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.infra.submission.SubmissionHistorySyncService
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
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
 * [integration-ish unit] 1.7.0 F2(WP61):站内提交历史三端点(sync/remote 列表)+
 * /api/stats 合并口径——真实 ResourceHttpServer + 真实 SQLite 仓库 +
 * LeetCodePlatform(FakeOJTransport,不上网络)。
 */
class SubmissionHistoryApiHttpTest {
    private val transport = FakeOJTransport()
    private lateinit var db: CodeQuestDatabase
    private lateinit var remoteRepo: ExposedRemoteSubmissionRepository
    private lateinit var submissionRepo: ExposedSubmissionRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** GraphQL operationName → 响应体(测试按需覆盖)。 */
    private val graphqlScripts = mutableMapOf<String, String>()

    @BeforeTest
    fun setUp() {
        db = newTempDatabase().first
        remoteRepo = ExposedRemoteSubmissionRepository(db)
        submissionRepo = ExposedSubmissionRepository(db)
        scriptLeetCode()
        val leetcode = LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, credentialStore = fakeCredentialStore())
        OJPlatformRegistryTestHelper.inject(leetcode, PlainPlatform())
        server =
            ResourceHttpServer(
                submissionHistoryApi =
                    SubmissionHistoryApi(
                        syncService =
                            SubmissionHistorySyncService(
                                platforms = { listOf<OJPlatform>(leetcode) },
                                remoteSubmissions = { remoteRepo },
                            ),
                        remoteSubmissions = { remoteRepo },
                        platforms = { listOf<OJPlatform>(leetcode, PlainPlatform()) },
                    ),
                statsApi =
                    StatisticsApi(
                        submissions = { submissionRepo },
                        remoteSubmissions = { remoteRepo },
                        clock = { Instant.parse("2026-08-18T12:00:00Z") },
                        zone = ZoneOffset.UTC,
                    ),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        OJPlatformRegistryTestHelper.reset()
        db.close()
    }

    /** 无站内历史能力的普通平台(400 对照)。 */
    private class PlainPlatform : OJPlatform {
        override val id = PlatformId("plain")
        override val domain = "plain.example.com"
        override val info = PlatformInfo(displayName = "Plain", templateStyle = TemplateStyle.ACM)
    }

    // ------------------------------------------------------------------
    // POST /api/submissions/sync
    // ------------------------------------------------------------------

    @Test
    fun `sync persists remote submissions and returns result`() {
        scriptSubmissionList(
            submissions =
                listOf(
                    """{"id":"1001","title":"Two Sum","titleSlug":"two-sum","statusDisplay":"Accepted",
                        "runtime":"64 ms","memory":"66.3 MB","lang":"kotlin","timestamp":"1634678400"}""",
                    """{"id":"1002","title":"Add Two Numbers","titleSlug":"add-two-numbers","statusDisplay":"Wrong Answer",
                        "runtime":"N/A","memory":"N/A","lang":"python3","timestamp":"1634764800"}""",
                ),
            lastKey = null,
        )

        val body = postJson("/api/submissions/sync", """{"platformId":"leetcode"}""")

        assertEquals("2", body["synced"]!!.jsonPrimitive.content)
        assertEquals("2", body["total"]!!.jsonPrimitive.content)
        assertEquals("END", body["stoppedBy"]!!.jsonPrimitive.content)

        // 入库可经 remote 列表端点观察到(runtime/memory 解析后的数值形态)
        val items = getJson("/api/submissions/remote?platform=leetcode")["items"]!!.jsonArray.toList()
        assertEquals(2, items.size)
        val first = items[0].jsonObject
        assertEquals("1002", first["remoteId"]!!.jsonPrimitive.content) // submitted_at 倒序
        assertEquals("AC", items[1].jsonObject["statusDisplay"]!!.jsonPrimitive.content) // "Accepted" 已归一
        assertEquals("67891", items[1].jsonObject["memoryKb"]!!.jsonPrimitive.content)
    }

    @Test
    fun `second sync stops at existing entries with zero synced`() {
        scriptSubmissionList(
            submissions = listOf(entryJson("1001")),
            lastKey = null,
        )
        postJson("/api/submissions/sync", """{"platformId":"leetcode"}""")

        val body = postJson("/api/submissions/sync", """{"platformId":"leetcode"}""")

        assertEquals("0", body["synced"]!!.jsonPrimitive.content)
        assertEquals("1", body["total"]!!.jsonPrimitive.content)
        assertEquals("EXISTING", body["stoppedBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sync rejects bad body incapable and unknown platform`() {
        assertEquals(400, post("/api/submissions/sync", "not-json").first)
        assertEquals(400, post("/api/submissions/sync", """{"platformId":""}""").first)
        assertEquals(400, post("/api/submissions/sync", """{"platformId":"plain"}""").first)
        assertEquals("unsupported-platform", post("/api/submissions/sync", """{"platformId":"plain"}""").second["error"]!!.jsonPrimitive.content)
        assertEquals(404, post("/api/submissions/sync", """{"platformId":"nope"}""").first)
    }

    // ------------------------------------------------------------------
    // GET /api/submissions/remote
    // ------------------------------------------------------------------

    @Test
    fun `remote list paginates with platform filter`() {
        runBlocking {
            remoteRepo.upsert(record("r-1", Instant.parse("2026-10-01T12:00:00Z")))
            remoteRepo.upsert(record("r-2", Instant.parse("2026-10-02T12:00:00Z")))
            remoteRepo.upsert(record("r-3", platformId = PlatformId("leetcodecn")))
        }

        val page1 = getJson("/api/submissions/remote?platform=leetcode&limit=1&offset=0")
        assertEquals("2", page1["total"]!!.jsonPrimitive.content) // total 为该平台全量计数(r-3 属 leetcodecn)
        assertEquals(1, page1["items"]!!.jsonArray.toList().size)
        assertEquals("r-2", page1["items"]!!.jsonArray.toList()[0].jsonObject["remoteId"]!!.jsonPrimitive.content) // 倒序

        val page2 = getJson("/api/submissions/remote?platform=leetcode&limit=1&offset=1")
        assertEquals("r-1", page2["items"]!!.jsonArray.toList()[0].jsonObject["remoteId"]!!.jsonPrimitive.content)

        // platform 缺省=跨平台全量(混合展示用):3 条跨两平台、total=3、platform 字段缺省
        val cross = getJson("/api/submissions/remote?limit=10")
        assertEquals("3", cross["total"]!!.jsonPrimitive.content)
        assertEquals(3, cross["items"]!!.jsonArray.toList().size)

        // 参数非法 400
        assertEquals(400, get("/api/submissions/remote?platform=leetcode&limit=0").first)
        assertEquals(400, get("/api/submissions/remote?platform=leetcode&offset=-1").first)
    }

    // ------------------------------------------------------------------
    // GET /api/stats 合并口径(1.7.0 F2)
    // ------------------------------------------------------------------

    @Test
    fun `stats merges local and remote with remoteSyncedCount`() {
        runBlocking {
            submissionRepo.save(localSubmission("s-1", "2026-08-18"))
            remoteRepo.upsert(record("r-1", Instant.parse("2026-08-18T10:00:00Z")))
            remoteRepo.upsert(record("r-2", Instant.parse("2026-08-17T10:00:00Z"), statusDisplay = "Wrong Answer"))
        }

        val stats = getJson("/api/stats?days=3")

        val summary = stats["summary"]!!.jsonObject
        assertEquals("3", summary["totalSubmissions"]!!.jsonPrimitive.content) // 1 本地 + 2 站内并集
        assertEquals("2", summary["acceptedSubmissions"]!!.jsonPrimitive.content) // 本地 AC + 站内 r-1 "AC"(r-2 WA 不计)
        assertEquals("2", stats["remoteSyncedCount"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(0, 1, 2),
            stats["heatmap"]!!.jsonArray.toList().map { it.jsonObject["count"]!!.jsonPrimitive.content.toInt() },
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun scriptLeetCode() {
        transport.handler = { request ->
            if (request.method != "POST_GRAPHQL") {
                "{}"
            } else {
                val operation = Json.parseToJsonElement(request.body!!).jsonObject["operationName"]!!.jsonPrimitive.content
                graphqlScripts[operation] ?: "{}"
            }
        }
    }

    /** 脚本化 submissionList 响应(lastKey null = 无更多)。 */
    private fun scriptSubmissionList(
        submissions: List<String>,
        lastKey: String?,
    ) {
        graphqlScripts["submissionList"] =
            """{"data":{"submissionList":{"lastKey":${lastKey?.let { "\"$it\"" } ?: "null"},"submissions":[${submissions.joinToString(",")}]}}}"""
    }

    private fun entryJson(id: String) =
        """{"id":"$id","title":"Two Sum","titleSlug":"two-sum","statusDisplay":"Accepted",
            "runtime":"64 ms","memory":"66.3 MB","lang":"kotlin","timestamp":"1634678400"}"""

    private fun record(
        remoteId: String,
        submittedAt: Instant = Instant.parse("2026-10-01T12:00:00Z"),
        platformId: PlatformId = PlatformId("leetcode"),
        statusDisplay: String = "AC",
    ) = RemoteSubmissionRecord(
        platformId = platformId,
        remoteId = remoteId,
        titleSlug = "two-sum",
        title = "Two Sum",
        statusDisplay = statusDisplay,
        lang = "kotlin",
        runtimeMs = 64,
        memoryKb = 67891,
        submittedAt = submittedAt,
    )

    private fun localSubmission(
        id: String,
        day: String,
    ) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId("c-1"),
        platformId = PlatformId("leetcode"),
        remoteSubmissionId = "remote-$id",
        language = "Kotlin",
        codeSnapshot = "class Solution",
        verdict = SubmissionResult.Accepted,
        timeMs = null,
        memoryKb = null,
        submittedAt = Instant.parse("${day}T10:00:00Z"),
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

    private fun getJson(path: String): JsonObject = get(path).second

    private fun postJson(
        path: String,
        body: String,
    ): JsonObject = post(path, body).second

    private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
        get() = this as kotlinx.serialization.json.JsonArray

    private fun fakeCredentialStore(): com.codequest.spi.OJCredentialStore =
        object : com.codequest.spi.OJCredentialStore {
            override fun save(
                platformId: PlatformId,
                secret: String,
            ) = Unit

            override fun load(platformId: PlatformId): String? = null

            override fun remove(platformId: PlatformId) = Unit
        }
}
