package com.codequest.core

import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.PlatformId
import com.codequest.core.repository.ContestReminderRepository
import java.time.Instant

/**
 * 竞赛提醒用例服务(WP23,M4):订阅/取消提醒 + 到期派发。
 *
 * ## 分层理由(core,而非 spi)
 *
 * 本服务只依赖 [ContestReminderRepository](core 仓库接口)与 core 模型,
 * **零 SPI 依赖**——竞赛实时数据(contests 列表)由 infra 端点(ContestApi)经
 * ContestCapable 拉取后,把 startTime 随订阅请求传入;服务本身不需要知道平台
 * 能力接口。core 层用例服务职责「只依赖仓库接口」正匹配,且 core→spi 被
 * checkLayerDependencies 门禁禁止,放 spi 反而破坏「core 无平台知识」边界。
 * (对照:BaseSubmissionFlow 放 spi 是因为它消费 spi 类型 SubmissionHandle。)
 *
 * 通知不落 IntelliJ API:派发经注入的 notifier 回调,由 ui 层
 * (ContestReminderScheduler + NotificationGroup)接线——本类纯逻辑,可单测。
 */
class ContestReminderService(
    private val repository: ContestReminderRepository,
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * 订阅(或重新订阅)提醒:remindAt = startTime - [leadTimeMinutes]
     * (默认 24h,可配);重复订阅按 contestId 覆盖(刷新提前量并重置未通知)。
     */
    suspend fun subscribe(
        platformId: PlatformId,
        remoteId: String,
        startTime: Instant,
        leadTimeMinutes: Long = DEFAULT_LEAD_TIME_MINUTES,
    ): ContestReminder {
        require(leadTimeMinutes >= 0) { "leadTimeMinutes 不得为负: $leadTimeMinutes" }
        val reminder =
            ContestReminder(
                contestId = contestKey(platformId, remoteId),
                remindAt = startTime.minusSeconds(leadTimeMinutes * SECONDS_PER_MINUTE),
                notified = false,
            )
        repository.save(reminder)
        return reminder
    }

    /** 取消订阅。@return 是否确实删除了一条。 */
    suspend fun unsubscribe(
        platformId: PlatformId,
        remoteId: String,
    ): Boolean = repository.delete(contestKey(platformId, remoteId))

    /** 查询订阅状态(前端开关回显);未订阅返回 null。 */
    suspend fun reminderFor(
        platformId: PlatformId,
        remoteId: String,
    ): ContestReminder? = repository.findByContest(contestKey(platformId, remoteId))

    /** 该平台全部订阅(contest 页开关状态回显,WP23)。 */
    suspend fun listReminders(platformId: PlatformId): List<ContestReminder> = repository.findByPlatform(platformId)

    /**
     * 到期派发一轮:findDue(now) → 逐条 [notifier] → markNotified。
     *
     * - 去重:markNotified 后 findDue 不再命中,同一提醒只通知一次;
     * - 重试:[notifier] 抛异常时该条不 markNotified,本轮中止,下一轮(调度器
     *   周期触发)重新派发——通知丢失可自愈,重复通知被 notified 标志挡住。
     */
    suspend fun dispatchDueReminders(notifier: (ContestReminder) -> Unit) {
        for (reminder in repository.findDue(clock())) {
            notifier(reminder)
            repository.markNotified(reminder.contestId)
        }
    }

    companion object {
        /** 默认提前量:24 小时(分钟)。 */
        const val DEFAULT_LEAD_TIME_MINUTES: Long = 24 * 60

        private const val SECONDS_PER_MINUTE = 60L
    }
}

/** 确定性提醒键:`<platform>:<remoteId>`(与 spi.ContestCapable.contestKey 同映射)。 */
internal fun contestKey(
    platformId: PlatformId,
    remoteId: String,
): ContestId = ContestId("${platformId.value}:$remoteId")
