package com.codequest.infra.messaging

import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.PlatformId
import com.codequest.core.repository.ContestReminderRepository
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.platform.leetcode.LeetCodeCnPlatform
import com.codequest.platform.leetcode.LeetCodePlatform
import com.codequest.platform.leetcode.LeetCodeVariant
import kotlinx.serialization.json.Json
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
 * [integration] 1.8.0 F1/F2(WP67):/api/daily-question(200 字段 / 同日缓存
 * 命中只拉一次 / 无能力 404 / 降级全 null)+ /api/contests 能力探测
 * (CN 在平台列表、COM 不在)——真实 ResourceHttpServer + 真实 LeetCode
 * 双平台(FakeOJTransport,不上网络)。
 */
class DailyQuestionApiHttpTest {
    private val transport = FakeOJTransport()
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    /** questionOfToday 拉取计数(缓存命中断言)。 */
    private var dailyFetches = 0

    @BeforeTest
    fun setUp() {
        dailyFetches = 0
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                when (operationOf(request.body!!)) {
                    "questionOfToday" -> {
                        dailyFetches++
                        TODAY_SAMPLE
                    }

                    "contestHistory" -> CONTESTS_SAMPLE
                    else -> "{}"
                }
            } else if (request.method == "GET" && request.url.contains("/contest/api/info/")) {
                if (request.url.contains("/contest/api/info/not-exist/")) """{"error":"竞赛不存在"}""" else CONTEST_INFO_SAMPLE
            } else {
                "{}"
            }
        }
        OJPlatformRegistryTestHelper.inject(
            LeetCodeCnPlatform(transport, InMemoryCredentialStore()),
            LeetCodePlatform(LeetCodeVariant.LEETCODE, transport, InMemoryCredentialStore()),
        )
        server =
            ResourceHttpServer(
                contestApi = ContestApi(reminders = { NoopReminderRepository() }),
                dailyQuestionApi = DailyQuestionApi(),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        OJPlatformRegistryTestHelper.reset()
    }

    // ------------------------------------------------------------------
    // GET /api/daily-question
    // ------------------------------------------------------------------

    @Test
    fun `daily question returns fields for cn platform`() {
        val (status, body) = get("/api/daily-question?platform=leetcodecn")

        assertEquals(200, status)
        assertEquals("2026-10-03", body["date"]!!.jsonPrimitive.content)
        assertEquals("32", body["frontendQuestionId"]!!.jsonPrimitive.content)
        assertEquals("最长有效括号", body["title"]!!.jsonPrimitive.content)
        assertEquals("longest-valid-parentheses", body["titleSlug"]!!.jsonPrimitive.content)
        assertEquals("Hard", body["difficulty"]!!.jsonPrimitive.content)
        val tags = body["tags"]!!.jsonArray
        assertEquals(2, tags.size)
        assertEquals("stack", tags[0].jsonObject["slug"]!!.jsonPrimitive.content)
    }

    @Test
    fun `daily question caches payload within same day`() {
        val first = get("/api/daily-question?platform=leetcodecn")
        val second = get("/api/daily-question?platform=leetcodecn")

        assertEquals(200, first.first)
        assertEquals(1, dailyFetches) // 同日缓存命中:站点只拉一次
        assertEquals(first.second.toString(), second.second.toString())
    }

    @Test
    fun `daily question requires capable platform`() {
        assertEquals(400, get("/api/daily-question").first)
        assertEquals(404, get("/api/daily-question?platform=leetcode").first) // COM 变体无能力
        assertEquals(404, get("/api/daily-question?platform=nope").first)
    }

    @Test
    fun `daily question degrades to null fields on fetch failure`() {
        transport.handler = { _ -> "{}" } // todayRecord 缺失 → SPI null → REST 全 null

        val (status, body) = get("/api/daily-question?platform=leetcodecn")

        assertEquals(200, status)
        assertTrue(body.isNullValue("date"))
        assertTrue(body.isNullValue("titleSlug"))
        assertEquals(0, body["tags"]!!.jsonArray.size)
    }

    // ------------------------------------------------------------------
    // /api/contests 能力探测(CN 在,COM 不在)
    // ------------------------------------------------------------------

    @Test
    fun `contests capability lists cn variant only`() {
        val (status, body) = get("/api/contests")

        assertEquals(200, status)
        val platforms = body["platforms"]!!.jsonArray
        assertEquals(1, platforms.size)
        assertEquals("leetcodecn", platforms[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("力扣", platforms[0].jsonObject["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `contests list serves cn platform and rejects com variant`() {
        val (status, body) = get("/api/contests?platform=leetcodecn")

        assertEquals(200, status)
        assertEquals("leetcodecn", body["platform"]!!.jsonPrimitive.content)
        val items = body["items"]!!.jsonArray
        assertEquals(1, items.size)
        val item = items[0].jsonObject
        assertEquals("weekly-contest-521", item["remoteId"]!!.jsonPrimitive.content)
        assertEquals("90", item["durationMinutes"]!!.jsonPrimitive.content)
        assertEquals("FINISHED", item["phase"]!!.jsonPrimitive.content)
        assertEquals("https://leetcode.cn/contest/weekly-contest-521/", item["url"]!!.jsonPrimitive.content)

        assertEquals(404, get("/api/contests?platform=leetcode").first) // COM 无 ContestCapable
    }

    @Test
    fun `contest detail serves question refs for cn platform`() {
        val (status, body) = get("/api/contest/weekly-contest-521?platform=leetcodecn")

        assertEquals(200, status)
        assertEquals(false, body["joinable"]!!.jsonPrimitive.content.toBoolean())
        val problems = body["problems"]!!.jsonArray
        assertEquals(2, problems.size)
        assertEquals("Q1", problems[0].jsonObject["index"]!!.jsonPrimitive.content)
        assertEquals("rearrange-array-by-removing-distinct-values", problems[0].jsonObject["remoteId"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun operationOf(body: String): String =
        Json.parseToJsonElement(body).jsonObject["operationName"]!!.jsonPrimitive.content

    private fun get(path: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    /** 字段缺省或 JSON null 均视为 null(explicitNulls 下 null 显式序列化为 JsonNull)。 */
    private fun kotlinx.serialization.json.JsonObject.isNullValue(name: String): Boolean = this[name] == null || this[name] is kotlinx.serialization.json.JsonNull

    private class NoopReminderRepository : ContestReminderRepository {
        override suspend fun save(reminder: ContestReminder) = Unit

        override suspend fun findByContest(contestId: ContestId): ContestReminder? = null

        override suspend fun findByPlatform(platformId: PlatformId): List<ContestReminder> = emptyList()

        override suspend fun findDue(now: Instant): List<ContestReminder> = emptyList()

        override suspend fun markNotified(contestId: ContestId) = Unit

        override suspend fun delete(contestId: ContestId): Boolean = false
    }

    private companion object {
        private const val TODAY_SAMPLE =
            """
            {"data":{"todayRecord":[{"date":"2026-10-03","userStatus":null,
              "question":{"questionFrontendId":"32","title":"Longest Valid Parentheses",
              "translatedTitle":"最长有效括号","titleSlug":"longest-valid-parentheses","difficulty":"Hard",
              "topicTags":[{"name":"Stack","slug":"stack"},{"name":"括号序列","slug":"bracket-sequences"}]}}]}}
            """

        private const val CONTESTS_SAMPLE =
            """
            {"data":{"contestHistory":{"contests":[
              {"title":"第 521 场周赛","titleSlug":"weekly-contest-521","startTime":1790476200,"duration":5400}]}}}
            """

        private const val CONTEST_INFO_SAMPLE =
            """
            {"contest":{"id":1415,"title":"第 521 场周赛","title_slug":"weekly-contest-521",
              "start_time":1790476200,"duration":5400},
             "questions":[
              {"question_id":4429,"title":"移除不同值重排数组","english_title":"Rearrange Array",
               "title_slug":"rearrange-array-by-removing-distinct-values"},
              {"question_id":4420,"title":"至多一次替换后的最大相邻相等元素对数","english_title":"Pairs",
               "title_slug":"maximum-equal-adjacent-pairs-after-at-most-one-replacement"}]}
            """
    }
}
