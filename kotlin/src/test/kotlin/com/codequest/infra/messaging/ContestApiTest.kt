package com.codequest.infra.messaging

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestPhase
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.PlatformId
import com.codequest.core.model.RateLimitedException
import com.codequest.core.repository.ContestReminderRepository
import com.codequest.spi.ContestCapable
import com.codequest.spi.ContestDetail
import com.codequest.spi.ContestProblemRef
import com.codequest.spi.ContestSummary
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.PlatformInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] ContestApi(WP23):/api/contests 系列端点——经 ContestCapable 拉取、
 * 错误映射照抄 ProblemApi(401/429+Retry-After/403/502/404/400)、提醒订阅落库。
 * 假平台 + 内存假仓库,不依赖 IntelliJ/网络。
 */
class ContestApiTest {
    private class FakeContestPlatform(
        val pid: PlatformId,
        val summaries: List<ContestSummary> = emptyList(),
        val detailByRemoteId: Map<String, ContestDetail> = emptyMap(),
        val listError: Throwable? = null,
        val detailError: Throwable? = null,
    ) : OJPlatform, ContestCapable {
        override val id = pid
        override val domain = "${pid.value}.example.com"
        override val info = PlatformInfo(displayName = pid.value.uppercase())

        override fun contests(): Flow<ContestSummary> =
            listError?.let { error -> flow<ContestSummary> { throw error } } ?: flowOf(*summaries.toTypedArray())

        override suspend fun contestDetail(remoteId: String): ContestDetail =
            detailError?.let { throw it } ?: detailByRemoteId.getValue(remoteId)
    }

    private class FakeContestProvider(
        private val platform: OJPlatform,
    ) : com.codequest.spi.OJPlatformProvider {
        override fun create() = platform
    }

    private class FakeReminderRepository : ContestReminderRepository {
        val store = LinkedHashMap<String, ContestReminder>()

        override suspend fun save(reminder: ContestReminder) {
            store[reminder.contestId.value] = reminder
        }

        override suspend fun findByContest(contestId: ContestId): ContestReminder? = store[contestId.value]

        override suspend fun findByPlatform(platformId: PlatformId): List<ContestReminder> =
            store.values.filter { it.contestId.value.startsWith("${platformId.value}:") }

        override suspend fun findDue(now: Instant): List<ContestReminder> = emptyList()

        override suspend fun markNotified(contestId: ContestId) {
            store[contestId.value] = requireNotNull(store[contestId.value]).copy(notified = true)
        }

        override suspend fun delete(contestId: ContestId): Boolean = store.remove(contestId.value) != null
    }

    private val start = Instant.parse("2026-08-20T17:35:00Z")

    private val summary =
        ContestSummary(
            remoteId = "1900",
            title = "Codeforces Round 950",
            startTime = start,
            durationMinutes = 120,
            phase = ContestPhase.BEFORE,
            url = "https://codeforces.com/contests/1900",
        )

    private val detail =
        ContestDetail(
            summary = summary,
            problems =
                listOf(
                    ContestProblemRef(remoteId = "1900/A", index = "A", title = "Simple Math"),
                    ContestProblemRef(remoteId = "1900/B", index = "B", title = "Two Arrays"),
                ),
            joinable = true,
        )

    private val contestPlatform = FakeContestPlatform(PlatformId("codeforces"), listOf(summary), mapOf("1900" to detail))
    private val plainPlatform =
        object : OJPlatform {
            override val id = PlatformId("leetcode")
            override val domain = "leetcode.com"
            override val info = PlatformInfo(displayName = "LeetCode")
        }

    private lateinit var reminders: FakeReminderRepository
    private lateinit var api: ContestApi
    private val json = Json { encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        reminders = FakeReminderRepository()
        api = ContestApi(platforms = { listOf(contestPlatform, plainPlatform) }, reminders = { reminders }, json = json)
        OJPlatformRegistry.providerSource = { listOf(FakeContestProvider(contestPlatform), FakeContestProvider(plainPlatform)) }
    }

    @AfterTest
    fun tearDown() {
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun body(response: ProblemApiResponse) = json.parseToJsonElement(response.json).jsonObject

    @Test
    fun `list platforms returns only ContestCapable ones`() {
        val response = api.listContestPlatforms()

        assertEquals(200, response.status)
        val platforms = body(response)["platforms"]!!.jsonArray
        assertEquals(1, platforms.size)
        assertEquals("codeforces", platforms[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("CODEFORCES", platforms[0].jsonObject["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list contests maps summaries to dto`() {
        val response = runBlocking { api.listContests(mapOf("platform" to "codeforces")) }

        assertEquals(200, response.status)
        val root = body(response)
        assertEquals("codeforces", root["platform"]!!.jsonPrimitive.content)
        val items = root["items"]!!.jsonArray
        assertEquals(1, items.size)
        val item = items[0].jsonObject
        assertEquals("1900", item["remoteId"]!!.jsonPrimitive.content)
        assertEquals("Codeforces Round 950", item["title"]!!.jsonPrimitive.content)
        assertEquals("2026-08-20T17:35:00Z", item["startTime"]!!.jsonPrimitive.content)
        assertEquals(120, item["durationMinutes"]!!.jsonPrimitive.content.toInt())
        assertEquals("BEFORE", item["phase"]!!.jsonPrimitive.content)
        assertEquals("https://codeforces.com/contests/1900", item["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list contests propagates error mapping like ProblemApi`() {
        val rateLimited =
            FakeContestPlatform(PlatformId("ratelimited"), listError = RateLimitedException("slow down", retryAfterMs = 30_000))
        val antiBot = FakeContestPlatform(PlatformId("antibot"), listError = AntiBotChallengeException("challenge", "cloudflare"))
        val localApi = ContestApi(platforms = { listOf(rateLimited, antiBot) }, reminders = { reminders }, json = json)

        val limited = runBlocking { localApi.listContests(mapOf("platform" to "ratelimited")) }
        assertEquals(429, limited.status)
        assertEquals("rate-limited", body(limited)["error"]!!.jsonPrimitive.content)
        assertEquals("30", limited.headers["Retry-After"])

        val challenged = runBlocking { localApi.listContests(mapOf("platform" to "antibot")) }
        assertEquals(403, challenged.status)
        assertEquals("anti-bot", body(challenged)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown or missing platform yields 404 and 400`() {
        assertEquals(404, runBlocking { api.listContests(mapOf("platform" to "nope")) }.status)
        assertEquals(400, runBlocking { api.listContests(emptyMap()) }.status)
    }

    @Test
    fun `contest detail includes problems and joinable`() {
        val response = runBlocking { api.contestDetail("1900", mapOf("platform" to "codeforces")) }

        assertEquals(200, response.status)
        val root = body(response)
        assertEquals("1900", root["remoteId"]!!.jsonPrimitive.content)
        assertEquals(true, root["joinable"]!!.jsonPrimitive.content.toBoolean())
        val problems = root["problems"]!!.jsonArray
        assertEquals(2, problems.size)
        assertEquals("A", problems[0].jsonObject["index"]!!.jsonPrimitive.content)
        assertEquals("1900/A", problems[0].jsonObject["remoteId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscribe parses body stores reminder with default lead time`() {
        val response =
            runBlocking {
                api.subscribeReminder(
                    """
                    {"platform":"codeforces","remoteId":"1900","startTime":"2026-08-20T17:35:00Z"}
                    """.trimIndent(),
                )
            }

        assertEquals(200, response.status)
        val stored = reminders.store["codeforces:1900"]
        assertEquals("2026-08-19T17:35:00Z", stored?.remindAt?.toString())
        assertEquals(false, stored?.notified)
        assertEquals("2026-08-19T17:35:00Z", body(response)["remindAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscribe accepts custom lead time and rejects malformed bodies`() {
        runBlocking { api.subscribeReminder("""{"platform":"codeforces","remoteId":"1900","startTime":"2026-08-20T17:35:00Z","leadTimeMinutes":60}""") }
        assertEquals("2026-08-20T16:35:00Z", reminders.store["codeforces:1900"]?.remindAt.toString())

        assertEquals(400, runBlocking { api.subscribeReminder("not-json") }.status)
        assertEquals(400, runBlocking { api.subscribeReminder("""{"platform":"codeforces"}""") }.status)
        // 未注册 /api/contests 数据源的平台
        assertEquals(
            404,
            runBlocking { api.subscribeReminder("""{"platform":"leetcode","remoteId":"x","startTime":"2026-08-20T17:35:00Z"}""") }.status,
        )
    }

    @Test
    fun `list reminders echoes stored subscriptions for platform`() {
        runBlocking { api.subscribeReminder("""{"platform":"codeforces","remoteId":"1900","startTime":"2026-08-20T17:35:00Z"}""") }

        val response = runBlocking { api.listReminders(mapOf("platform" to "codeforces")) }

        assertEquals(200, response.status)
        val items = body(response)["items"]!!.jsonArray
        assertEquals(1, items.size)
        val item = items[0].jsonObject
        assertEquals("1900", item["remoteId"]!!.jsonPrimitive.content)
        assertEquals("2026-08-19T17:35:00Z", item["remindAt"]!!.jsonPrimitive.content)
        assertEquals(false, item["notified"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `unsubscribe deletes reminder and reports missing`() {
        runBlocking { api.subscribeReminder("""{"platform":"codeforces","remoteId":"1900","startTime":"2026-08-20T17:35:00Z"}""") }

        val removed = runBlocking { api.unsubscribeReminder(mapOf("platform" to "codeforces", "remoteId" to "1900")) }
        assertEquals(200, removed.status)
        assertEquals(true, body(removed)["deleted"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(reminders.store.isEmpty())

        assertEquals(false, body(runBlocking { api.unsubscribeReminder(mapOf("platform" to "codeforces", "remoteId" to "1900")) })["deleted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(400, runBlocking { api.unsubscribeReminder(mapOf("platform" to "codeforces")) }.status)
    }
}
