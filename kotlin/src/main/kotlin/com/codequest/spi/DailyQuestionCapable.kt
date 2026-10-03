package com.codequest.spi

import kotlinx.serialization.Serializable

/**
 * 每日一题能力(1.8.0 F1):力扣 CN「今日题目」常驻卡片的数据源。
 *
 * 仅站内有官方每日一题栏目的平台实现(LeetCode CN 变体);其余平台不实现,
 * REST 消费方(`/api/daily-question`)按能力探测 404。拉取失败/字段缺失返回
 * null 降级不抛错——每日一题是附属能力,不致败题库页。
 */
interface DailyQuestionCapable {
    /**
     * 当日题目快照。
     *
     * @return 平台无此概念/站点查询失败/响应字段缺失时为 null(消费方静默降级)。
     */
    suspend fun fetchDailyQuestion(): DailyQuestion?
}

/** 每日一题快照(字段最小集;titleSlug 与题库 remoteId 同源,题面链路直达)。 */
@Serializable
data class DailyQuestion(
    /** 站点返回的当日日期(yyyy-MM-dd)。 */
    val date: String,
    val frontendQuestionId: String,
    /** 站点语言标题优先(如 CN 站 translatedTitle),缺失回退英文 title。 */
    val title: String,
    val titleSlug: String,
    val difficulty: String,
    val tags: List<DailyQuestionTag>,
)

/** 每日一题标签(精简 name+slug,与题库标签同源)。 */
@Serializable
data class DailyQuestionTag(
    val name: String,
    val slug: String,
)
