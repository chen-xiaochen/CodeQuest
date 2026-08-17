package com.codequest.core

import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.PlatformId
import com.codequest.core.repository.ContestReminderRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] ContestReminderService(WP23):订阅落库(默认 24h 提前量,可配)、
 * 到期派发经注入时钟 findDue → notify → markNotified 去重、通知失败下轮重试。
 */
class ContestReminderServiceTest {
    /** 内存假仓库:与 Exposed 实现同语义(upsert 以 contestId 为键 / findDue 过滤 notified)。 */
    private class FakeContestReminderRepository : ContestReminderRepository {
        val store = LinkedHashMap<String, ContestReminder>()

        override suspend fun save(reminder: ContestReminder) {
            store[reminder.contestId.value] = reminder
        }

        override suspend fun findByContest(contestId: ContestId): ContestReminder? = store[contestId.value]

        override suspend fun findByPlatform(platformId: PlatformId): List<ContestReminder> =
            store.values.filter { it.contestId.value.startsWith("${platformId.value}:") }

        override suspend fun findDue(now: Instant): List<ContestReminder> =
            store.values
                .filter { !it.notified && !it.remindAt.isAfter(now) }
                .sortedBy { it.remindAt }

        override suspend fun markNotified(contestId: ContestId) {
            store[contestId.value] =
                requireNotNull(store[contestId.value]) { "markNotified on missing reminder" }
                    .copy(notified = true)
        }

        override suspend fun delete(contestId: ContestId): Boolean = store.remove(contestId.value) != null
    }

    private lateinit var repository: FakeContestReminderRepository

    private var now: Instant = Instant.parse("2026-08-16T12:00:00Z")

    private lateinit var service: ContestReminderService

    @BeforeTest
    fun setUp() {
        repository = FakeContestReminderRepository()
        service = ContestReminderService(repository, clock = { now })
    }

    @Test
    fun `subscribe stores reminder 24h before start by default`() {
        val start = Instant.parse("2026-08-20T18:00:00Z")
        val reminder =
            kotlinx.coroutines.runBlocking {
                service.subscribe(PlatformId("codeforces"), "1900", startTime = start)
            }

        assertEquals(ContestId("codeforces:1900"), reminder.contestId)
        assertEquals(start.minus(24, ChronoUnit.HOURS), reminder.remindAt)
        assertFalse(reminder.notified)
        assertEquals(reminder, repository.store["codeforces:1900"])
    }

    @Test
    fun `subscribe accepts configurable lead time and resubscribe overwrites`() {
        val start = Instant.parse("2026-08-20T18:00:00Z")
        val resubscribed =
            kotlinx.coroutines.runBlocking {
                service.subscribe(PlatformId("codeforces"), "1900", startTime = start, leadTimeMinutes = 60)
                service.subscribe(PlatformId("codeforces"), "1900", startTime = start, leadTimeMinutes = 120)
            }

        assertEquals(start.minus(120, ChronoUnit.MINUTES), resubscribed.remindAt)
        assertEquals(1, repository.store.size)
    }

    @Test
    fun `unsubscribe deletes existing reminder only`() {
        val (first, second) =
            kotlinx.coroutines.runBlocking {
                service.subscribe(PlatformId("codeforces"), "1900", startTime = now.plusSeconds(3600))
                service.unsubscribe(PlatformId("codeforces"), "1900") to service.unsubscribe(PlatformId("codeforces"), "1900")
            }

        assertTrue(first)
        assertNull(repository.store["codeforces:1900"])
        assertFalse(second)
    }

    @Test
    fun `reminderFor and listReminders return stored subscriptions`() {
        val stored =
            kotlinx.coroutines.runBlocking {
                assertNull(service.reminderFor(PlatformId("codeforces"), "1900"))
                service.subscribe(PlatformId("codeforces"), "1900", startTime = now.plusSeconds(3600))
            }
        val resolved =
            kotlinx.coroutines.runBlocking {
                service.reminderFor(PlatformId("codeforces"), "1900") to service.listReminders(PlatformId("codeforces"))
            }

        assertEquals(stored, resolved.first)
        assertEquals(listOf(stored), resolved.second)
    }

    @Test
    fun `dispatch notifies only due reminders and marks them notified`() {
        val expectedDueOrder =
            kotlinx.coroutines.runBlocking {
                val due = service.subscribe(PlatformId("codeforces"), "1900", startTime = now.plusSeconds(60))
                val dueEarlier = service.subscribe(PlatformId("codeforces"), "1901", startTime = now.plusSeconds(30))
                service.subscribe(PlatformId("codeforces"), "1902", startTime = now.plusSeconds(2 * 86_400))
                val alreadyNotified =
                    service.subscribe(PlatformId("codeforces"), "1903", startTime = now.minusSeconds(60))
                repository.save(alreadyNotified.copy(notified = true))
                listOf(dueEarlier.contestId, due.contestId)
            }
        val fired = mutableListOf<ContestReminder>()
        kotlinx.coroutines.runBlocking { service.dispatchDueReminders { fired.add(it) } }

        // 只派发到期未通知的两条,按 remindAt 升序(findDue 语义)
        assertEquals(expectedDueOrder, fired.map { it.contestId })

        // 第二轮(无新到期)零通知——markNotified 去重
        val second = mutableListOf<ContestReminder>()
        kotlinx.coroutines.runBlocking { service.dispatchDueReminders { second.add(it) } }
        assertTrue(second.isEmpty())
    }

    @Test
    fun `notifier failure keeps reminder pending for next round`() {
        val failing = mutableListOf<ContestReminder>()
        val error =
            runCatching {
                kotlinx.coroutines.runBlocking {
                    service.subscribe(PlatformId("codeforces"), "1900", startTime = now.plusSeconds(60))
                    service.dispatchDueReminders {
                        failing.add(it)
                        error("notification lost")
                    }
                }
            }
        val retried = mutableListOf<ContestReminder>()
        kotlinx.coroutines.runBlocking { service.dispatchDueReminders { retried.add(it) } }

        assertTrue(error.isFailure)
        assertEquals(1, failing.size)
        // 未 markNotified:下轮重试
        assertEquals(1, retried.size)
    }
}
