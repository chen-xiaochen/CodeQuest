package com.codequest.core.repository

import com.codequest.core.model.ContestId
import com.codequest.core.model.ContestReminder
import com.codequest.core.model.PlatformId
import java.time.Instant

/**
 * 竞赛提醒仓库接口(WP13;WP23 增 [findByPlatform] 供 contest 页订阅状态回显)。
 *
 * 每场竞赛至多一条提醒(以 [ContestId] 为键);竞赛主档不落库,实时数据走 SPI。
 * 实现归 infra(WP14 用 Exposed);接口不出现任何持久化框架类型。
 */
interface ContestReminderRepository {
    /** 新增或更新提醒(upsert,以 contestId 为键)。 */
    suspend fun save(reminder: ContestReminder)

    suspend fun findByContest(contestId: ContestId): ContestReminder?

    /** 该平台全部提醒(contestId 约定为 `<platform>:<remoteId>` 前缀,WP23)。 */
    suspend fun findByPlatform(platformId: PlatformId): List<ContestReminder>

    /** 全部到期未通知的提醒(remindAt <= [now] 且 notified = false),供提醒调度器轮询。 */
    suspend fun findDue(now: Instant): List<ContestReminder>

    /** 标记提醒已通知。 */
    suspend fun markNotified(contestId: ContestId)

    /** 删除提醒。@return 是否确实删除了一条。 */
    suspend fun delete(contestId: ContestId): Boolean
}
