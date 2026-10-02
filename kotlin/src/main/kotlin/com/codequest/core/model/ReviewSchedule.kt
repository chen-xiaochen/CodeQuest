package com.codequest.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 间隔重复复习排期(1.7.0 F3,schema v3),对应表 `review_schedule`。
 *
 * `planDates` / `historyDates` 为 epoch 秒数组(对齐当天零点,系统默认时区):
 * 「当日到期」= plan_dates 含今天零点;「已过期」= plan_dates 存在小于今天零点的值。
 */
@Serializable
data class ReviewSchedule(
    val problemId: ProblemId,
    /** 待复习日期(epoch 秒,零点对齐),升序;空 = 排期全部完成。 */
    val planDates: List<Long>,
    /** 已完成复习日期(epoch 秒,零点对齐),追加式。 */
    val historyDates: List<Long>,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
)
