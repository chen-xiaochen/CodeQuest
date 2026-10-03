package com.codequest.spi

/**
 * 题目提示能力(1.9.0 F2):题面页「提示」懒加载折叠区的数据出口。
 *
 * 仅站内有官方提示(题解前剧透分段)的平台实现(LeetCode 两变体);REST
 * 消费方(`GET /api/problem/{remoteId}/hints?platform=`)按能力探测 404。
 * 提示是附属能力:**任何失败形态降级空表不抛**(前端显示「无提示」,
 * spec §2 降级声明)。
 *
 * 协议事实(LeetCode,2026-10-03 双站匿名实测 + 插件源锁定,详见
 * LeetCodeApi.fetchHints KDoc):GraphQL `questionHints` →
 * `data.question.hints` 字符串数组(元素可含换行/HTML 实体如 `<code>`,
 * 原样透出前端按文本渲染;无提示题为空数组,如 add-two-numbers)。
 */
interface ProblemHintsCapable {
    /**
     * 题目提示列表([remoteId] 为题库同源标识,LC 为 titleSlug)。
     *
     * @return 提示原文列表;平台无此概念/站点查询失败/响应字段缺失一律空表(不抛)。
     */
    suspend fun fetchHints(remoteId: String): List<String>
}
