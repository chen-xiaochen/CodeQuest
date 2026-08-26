package com.codequest.platform.leetcode

import com.codequest.spi.FavoriteList

/**
 * LeetCode CN 学习计划内置清单(WP56):下拉「学习计划」组数据源。
 *
 * slug 即官方 https://leetcode.cn/studyplan/{slug}/ 路径段,2026-08-22 抓取官方
 * 索引页逐项核对(页面公开列出全部计划入口;spec 预估的 interview-75 /
 * data-structure / primary-algorithm 系经典 LeetBook 不在 /studyplan/ 域,
 * 已实测返回「未找到学习计划」,不收录)。名企秋招系列(华为/腾讯等 12 个
 * 2023-fall-sprint)时效性差不收录。失效 slug 表现为该计划空列表,
 * 清单可随时追加(常量表无其他消费方)。
 *
 * 1.6.0:条目附站点/会员标记(2026-08-26 `studyPlanV2Detail` 双站匿名实测):
 * - [Entry.cnOnly]:COM 站返回 null(CN 专属计划),COM 变体不下发;
 * - [Entry.premium]:会员计划,未订阅时题目列表为空(premiumOnly=true),
 *   名称加「(会员)」后缀提示;
 * - 学习计划数据源由 favoriteQuestionList 换为 studyPlanV2Detail(旧查询对
 *   学习计划 slug 基本返回空集,是「学习计划为空」的根因,见
 *   [LeetCodeApi.fetchStudyPlanDetail])。
 */
internal object LeetCodeStudyPlans {
    /** 内置条目:slug/名称 + 站点与会员标记(事实来源见类 KDoc)。 */
    private data class Entry(
        val slug: String,
        val name: String,
        val cnOnly: Boolean = false,
        val premium: Boolean = false,
    )

    /** 展示顺序 = 官方索引页编排(精选 → 面试 → 专项)。 */
    private val ENTRIES: List<Entry> =
        listOf(
            // 精选
            Entry(slug = "top-100-liked", name = "热题 100"),
            Entry(slug = "leetcode-75", name = "LeetCode 75"),
            Entry(slug = "top-interview-150", name = "面试经典 150 题"),
            Entry(slug = "premium-algo-100", name = "尊享面试 100 题", premium = true),
            Entry(slug = "cracking-the-coding-interview", name = "程序员面试金典", cnOnly = true),
            // SQL / 数据方向
            Entry(slug = "sql-free-50", name = "高频 SQL 50 题(基础版)", cnOnly = true),
            Entry(slug = "sql-premium-50", name = "高频 SQL 50 题(进阶版)", cnOnly = true, premium = true),
            Entry(slug = "introduction-to-pandas", name = "Pandas 入门 15 题"),
            Entry(slug = "30-days-of-pandas", name = "30 天 Pandas 挑战"),
            Entry(slug = "30-days-of-javascript", name = "30 天 JavaScript 挑战"),
            // 专项
            Entry(slug = "dynamic-programming", name = "动态规划(基础版)"),
            Entry(slug = "dynamic-programming-grandmaster", name = "动态规划(进阶版)", premium = true),
            Entry(slug = "graph-theory", name = "图论 · 从入门到精通"),
            Entry(slug = "binary-search", name = "二分查找 · 系统掌握"),
            Entry(slug = "programming-skills", name = "编程基础 0 到 1"),
            Entry(slug = "primers-list", name = "「新」动计划 · 编程入门", cnOnly = true),
            Entry(slug = "coding-interviews-special", name = "119 经典题变种挑战", cnOnly = true),
            Entry(slug = "2024-spring-sprint-100", name = "2024 春招冲刺百题计划", cnOnly = true),
        )

    /** 全部内置学习计划 slug(题单查询路由用:命中走 studyPlanV2Detail 路径)。 */
    val SLUGS: Set<String> = ENTRIES.map { it.slug }.toSet()

    /** 按站点变体下发:COM 过滤 CN 专属 slug;会员计划名称带「(会员)」后缀。 */
    fun forVariant(isCn: Boolean): List<FavoriteList> =
        ENTRIES
            .filter { isCn || !it.cnOnly }
            .map { FavoriteList(slug = it.slug, name = if (it.premium) "${it.name}(会员)" else it.name) }
}
