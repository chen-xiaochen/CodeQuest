package com.codequest.platform.codeforces

import com.codequest.core.ContestReminderService
import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestPhase
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.core.repository.ContestReminderRepository
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.ContestDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [unit] WP24:CodeForcesContestService(ContestCapable)——
 * contests() 的 phase 映射/近期过滤/URL 选型(注册页 vs 竞赛页)、contestDetail
 * 的题目引用与 joinable 判定、standings 辅助查询,以及**竞赛提醒订阅链路**
 * (contests() → WP23 ContestReminderService.subscribe → 落库,验证 CF 零额外
 * 接线即可被通用 /api/contests 端点与 contest 页消费)。
 */
class CodeForcesContestServiceTest {
    private val now: Instant = Instant.parse("2026-08-17T12:00:00Z")

    /** startTime 按秒偏移构造,phase 覆盖五个阶段。 */
    private fun contestListBody(): String {
        val nowSec = now.epochSecond
        val day = 86_400L
        return """
        {"status":"OK","result":[
          {"id":2001,"name":"Round Future","type":"CF","phase":"BEFORE","frozen":false,
           "durationSeconds":7200,"startTimeSeconds":${nowSec + day},"relativeTimeSeconds":-86400},
          {"id":2002,"name":"Round Live","type":"CF","phase":"CODING","frozen":false,
           "durationSeconds":9000,"startTimeSeconds":${nowSec - 1800},"relativeTimeSeconds":1800},
          {"id":2003,"name":"Round Pending Test","type":"CF","phase":"PENDING_SYSTEM_TEST","frozen":false,
           "durationSeconds":7200,"startTimeSeconds":${nowSec - 7200},"relativeTimeSeconds":7200},
          {"id":2004,"name":"Round Yesterday","type":"CF","phase":"FINISHED","frozen":true,
           "durationSeconds":7200,"startTimeSeconds":${nowSec - day},"relativeTimeSeconds":172800},
          {"id":2005,"name":"Round Ancient","type":"CF","phase":"FINISHED","frozen":true,
           "durationSeconds":7200,"startTimeSeconds":${nowSec - 30 * day},"relativeTimeSeconds":2592000}
        ]}
        """.trimIndent()
    }

    private fun standingsBody(contestId: Long): String =
        """
        {"status":"OK","result":{
          "contest":{"id":$contestId,"name":"Round Live","type":"CF","phase":"CODING","frozen":false,
            "durationSeconds":9000,"startTimeSeconds":1,"relativeTimeSeconds":1},
          "problems":[
            {"contestId":$contestId,"index":"A","name":"First Problem","type":"PROGRAMMING","points":500.0,"rating":800,"tags":[]},
            {"contestId":$contestId,"index":"B","name":"Second Problem","type":"PROGRAMMING","points":1000.0,"rating":1200,"tags":[]}
          ],
          "rows":[
            {"party":{"members":[{"handle":"tourist"}]},"rank":1,"points":2,"penalty":40,"problemResults":[]}
          ]
        }}
        """.trimIndent()

    private fun transportWith(
        contestList: String = contestListBody(),
        standingsByContest: Map<Long, String> = mapOf(2002L to standingsBody(2002)),
    ): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when {
                request.method == "GET" && request.url.contains("/api/contest.list") -> contestList
                request.method == "GET" && request.url.contains("/api/contest.standings") ->
                    standingsByContest[request.url.substringAfter("contestId=").toLong()]
                        ?: """{"status":"FAILED","comment":"contest not found or not started"}"""
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        return transport
    }

    private fun service(
        transport: FakeOJTransport,
        loggedIn: () -> Boolean = { false },
    ) = CodeForcesContestService(CodeForcesApi(transport), isLoggedIn = loggedIn, clock = { now })

    @Test
    fun `contests maps phase duration and url with recency window`() = runBlocking {
        val summaries = service(transportWith()).contests().toList()

        // 非FINISHED 全发 + FINISHED 只保留 7 天内(2005 为 30 天前被滤除)
        assertEquals(listOf("2001", "2002", "2003", "2004"), summaries.map { it.remoteId })
        val round = summaries.first()
        assertEquals("Round Future", round.title)
        assertEquals(ContestPhase.BEFORE, round.phase)
        assertEquals(120, round.durationMinutes)
        assertEquals(now.epochSecond + 86_400, round.startTime.epochSecond)
        // BEFORE → 注册页,其余 → 竞赛页(旧 contestUrl 事实)
        assertEquals("https://codeforces.com/contestRegistration/2001", round.url)
        assertEquals("https://codeforces.com/contest/2002", summaries[1].url)
        assertEquals(ContestPhase.CODING, summaries[1].phase)
        assertEquals(ContestPhase.PENDING_SYSTEM_TEST, summaries[2].phase)
        assertEquals(ContestPhase.FINISHED, summaries[3].phase)
    }

    @Test
    fun `contestDetail returns problem refs from standings and joinable for coding when logged in`() = runBlocking {
        val detail = service(transportWith(), loggedIn = { true }).contestDetail("2002")

        assertEquals("2002", detail.summary.remoteId)
        assertEquals(
            listOf("2002A" to "First Problem", "2002B" to "Second Problem"),
            detail.problems.map { it.remoteId to it.title },
        )
        assertEquals(listOf("A", "B"), detail.problems.mapNotNull { it.index })
        assertTrue(detail.joinable) // CODING + 已登录
    }

    @Test
    fun `contestDetail before start has empty problems and joinable registration`() = runBlocking {
        val detail = service(transportWith()).contestDetail("2001")

        assertTrue(detail.problems.isEmpty()) // 题目未公开(standings 不可用)
        assertTrue(detail.joinable) // BEFORE = 可注册(与登录态无关)
    }

    @Test
    fun `contestDetail joinable false when coding and not logged in`() = runBlocking {
        val detail = service(transportWith(), loggedIn = { false }).contestDetail("2002")

        assertFalse(detail.joinable)
        assertEquals(2, detail.problems.size)
    }

    @Test
    fun `contestDetail finished contest is not joinable`() = runBlocking {
        val detail = service(transportWith(), loggedIn = { true }).contestDetail("2004")

        assertFalse(detail.joinable)
        assertTrue(detail.problems.isEmpty()) // standings 未脚本化(FAILED)→ 题目列表为空不致败
    }

    @Test
    fun `contestDetail throws for unknown or malformed id`() {
        assertFailsWith<OJException> {
            runBlocking { service(transportWith()).contestDetail("999999") }
        }
        assertFailsWith<OJException> {
            runBlocking { service(transportWith()).contestDetail("not-a-number") }
        }
    }

    @Test
    fun `standings helper exposes rows with documented limits`() = runBlocking {
        val standings = service(transportWith()).contestStandings("2002")

        assertEquals(1, standings.rows.size)
        assertEquals("tourist", standings.rows.single().handle)
        assertEquals(1, standings.rows.single().rank)
    }

    // -------------------------------------------------------------------
    // 竞赛提醒订阅链路(WP24 checklist 第 2 项的自动化验证)
    // -------------------------------------------------------------------

    private class RecordingReminderRepository : ContestReminderRepository {
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

    @Test
    fun `reminder subscription stores contest reminder via wp23 service`() = runBlocking {
        val repository = RecordingReminderRepository()
        val reminderService = ContestReminderService(repository, clock = { now })
        val upcoming = service(transportWith()).contests().first()

        assertEquals("2001", upcoming.remoteId)
        // contest 页订阅动作等价调用:平台 id + remoteId + startTime(WP23 契约)
        reminderService.subscribe(CODEFORCES_PLATFORM_ID, upcoming.remoteId, upcoming.startTime)

        val stored = repository.store.values.single()
        assertEquals(ContestId("codeforces:2001"), stored.contestId)
        assertEquals(upcoming.startTime.minusSeconds(24 * 60 * 60), stored.remindAt)
        assertFalse(stored.notified)
    }

    @Test
    fun `contest detail dto shape satisfies contest api consumption`() = runBlocking {
        // ContestApi(WP23)按 ContestCapable 接口消费;此处固化详情的字段完整性
        val detail: ContestDetail = service(transportWith()).contestDetail("2002")

        assertTrue(detail.summary.url.startsWith("https://codeforces.com/contest/2002"))
        assertTrue(detail.problems.all { it.remoteId.startsWith("2002") })
    }
}
