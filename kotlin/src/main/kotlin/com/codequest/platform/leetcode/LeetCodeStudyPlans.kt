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
 */
internal object LeetCodeStudyPlans {
    /** 展示顺序 = 官方索引页编排(精选 → 面试 → 专项)。 */
    val ALL: List<FavoriteList> =
        listOf(
            // 精选
            FavoriteList(slug = "top-100-liked", name = "热题 100"),
            FavoriteList(slug = "leetcode-75", name = "LeetCode 75"),
            FavoriteList(slug = "top-interview-150", name = "面试经典 150 题"),
            FavoriteList(slug = "premium-algo-100", name = "尊享面试 100 题"),
            FavoriteList(slug = "cracking-the-coding-interview", name = "程序员面试金典"),
            // SQL / 数据方向
            FavoriteList(slug = "sql-free-50", name = "高频 SQL 50 题(基础版)"),
            FavoriteList(slug = "sql-premium-50", name = "高频 SQL 50 题(进阶版)"),
            FavoriteList(slug = "introduction-to-pandas", name = "Pandas 入门 15 题"),
            FavoriteList(slug = "30-days-of-pandas", name = "30 天 Pandas 挑战"),
            FavoriteList(slug = "30-days-of-javascript", name = "30 天 JavaScript 挑战"),
            // 专项
            FavoriteList(slug = "dynamic-programming", name = "动态规划(基础版)"),
            FavoriteList(slug = "dynamic-programming-grandmaster", name = "动态规划(进阶版)"),
            FavoriteList(slug = "graph-theory", name = "图论 · 从入门到精通"),
            FavoriteList(slug = "binary-search", name = "二分查找 · 系统掌握"),
            FavoriteList(slug = "programming-skills", name = "编程基础 0 到 1"),
            FavoriteList(slug = "primers-list", name = "「新」动计划 · 编程入门"),
            FavoriteList(slug = "coding-interviews-special", name = "119 经典题变种挑战"),
            FavoriteList(slug = "2024-spring-sprint-100", name = "2024 春招冲刺百题计划"),
        )
}
