package com.codequest.platform.leetcode

import com.codequest.core.model.ContestPhase
import com.codequest.core.model.OJException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] 1.8.0 F2(WP67):LeetCodeContestService——contestHistory 映射 +
 * phase 推导边界(注入 clock:赛前/赛中/赛后)+ contestDetail(题目引用/
 * 不存在领域异常/题目降级空表),FakeOJTransport 样例回放,不上网络。
 */
class LeetCodeContestServiceTest {
    private val transport = FakeOJTransport()

    /** 注入时钟:三态边界用例各自覆写。 */
    private var now: Instant = Instant.parse("2026-10-03T00:00:00Z")

    private lateinit var service: LeetCodeContestService

    @BeforeTest
    fun setUp() {
        service = LeetCodeContestService(LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transport), clock = { now })
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL") {
                when (operationOf(request.body!!)) {
                    "contestHistory" -> CONTEST_HISTORY_SAMPLE
                    else -> "{}"
                }
            } else if (request.method == "GET" && request.url.contains("/contest/api/info/")) {
                if (request.url.contains("/contest/api/info/not-exist/")) """{"error":"竞赛不存在"}""" else CONTEST_INFO_SAMPLE
            } else {
                "{}"
            }
        }
    }

    private fun operationOf(body: String): String =
        kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { it as kotlinx.serialization.json.JsonObject }["operationName"]!!
            .let { it as kotlinx.serialization.json.JsonPrimitive }.content

    // now = 1790985600(2026-10-03T00:00:00Z);三态样例:start = now+3600(未开始)/
    // start = now(恰开赛,赛中)/ start = now-7200 且 dur 5400(已结束,now-start 7200 > 5400)
    private val startBefore = 1_790_989_200L // now + 3600
    private val startFinished = 1_790_978_400L // now - 7200

    private fun sample(start: Long) =
        """
        {"data":{"contestHistory":{"contests":[
          {"title":"第 521 场周赛","titleSlug":"weekly-contest-521","startTime":$start,"duration":5400}]}}}
        """.trimIndent()

    // -------------------------------------------------------------------
    // contests():phase 推导边界
    // -------------------------------------------------------------------

    @Test
    fun `contests derive BEFORE before start`() = runBlocking {
        transportHandler(sample(startBefore))

        val items = service.contests().toList()

        assertEquals(ContestPhase.BEFORE, items.single().phase)
    }

    @Test
    fun `contests derive CODING inside window including exact start`() = runBlocking {
        // 恰好开赛瞬间(now == startTime)也属赛中
        transportHandler(sample(now.epochSecond))

        val items = service.contests().toList()

        assertEquals(ContestPhase.CODING, items.single().phase)
    }

    @Test
    fun `contests derive FINISHED past window`() = runBlocking {
        transportHandler(sample(startFinished))

        val items = service.contests().toList()

        assertEquals(ContestPhase.FINISHED, items.single().phase)
    }

    @Test
    fun `contests map summary fields duration and url`() = runBlocking {
        transportHandler(sample(startFinished))

        val item = service.contests().toList().single()

        assertEquals("weekly-contest-521", item.remoteId)
        assertEquals("第 521 场周赛", item.title)
        assertEquals(Instant.ofEpochSecond(startFinished), item.startTime) // 秒级换算(单位锁定)
        assertEquals(90, item.durationMinutes) // 5400s → 90min
        assertEquals("https://leetcode.cn/contest/weekly-contest-521/", item.url)
    }

    // -------------------------------------------------------------------
    // contestDetail()
    // -------------------------------------------------------------------

    @Test
    fun `contestDetail maps question refs with Q index and joinable false`() = runBlocking {
        val detail = service.contestDetail("weekly-contest-521")

        assertEquals("weekly-contest-521", detail.summary.remoteId)
        assertEquals(ContestPhase.FINISHED, detail.summary.phase) // 2026-09-27 开赛,now=10-03 已结束
        assertEquals(90, detail.summary.durationMinutes)
        assertEquals(2, detail.problems.size)
        assertEquals("Q1", detail.problems[0].index)
        assertEquals("rearrange-array-by-removing-distinct-values", detail.problems[0].remoteId)
        assertEquals("移除不同值重排数组", detail.problems[0].title)
        assertEquals("Q2", detail.problems[1].index)
        assertTrue(!detail.joinable) // 回顾语义,参赛走 url 外链
    }

    @Test
    fun `contestDetail throws OJException for nonexistent slug`() {
        assertFailsWith<OJException> { runBlocking { service.contestDetail("not-exist") } }
    }

    @Test
    fun `contestDetail degrades missing questions to empty list`() = runBlocking {
        transport.handler = { _ ->
            """{"contest":{"title":"第 521 场周赛","title_slug":"weekly-contest-521",
               "start_time":1790476200,"duration":5400}}"""
        }

        val detail = service.contestDetail("weekly-contest-521")

        assertTrue(detail.problems.isEmpty())
        assertEquals("第 521 场周赛", detail.summary.title) // 摘要不因题目缺失致败
    }

    // -------------------------------------------------------------------
    // fetchDailyQuestion():透传降级语义(api 层三态见 LeetCodeApiTest)
    // -------------------------------------------------------------------

    @Test
    fun `fetchDailyQuestion returns null on transport degradation`() = runBlocking {
        transport.handler = { _ -> "{}" } // todayRecord 缺失 → null

        assertNull(service.fetchDailyQuestion())
    }

    /** 覆写 contestHistory 脚本(按用例注入不同 startTime)。 */
    private fun transportHandler(body: String) {
        transport.handler = { request ->
            if (request.method == "POST_GRAPHQL" && operationOf(request.body!!) == "contestHistory") body else "{}"
        }
    }

    private companion object {
        private const val CONTEST_HISTORY_SAMPLE =
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
