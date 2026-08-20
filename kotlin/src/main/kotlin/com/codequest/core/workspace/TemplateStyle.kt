package com.codequest.core.workspace

/**
 * 入口文件模版风格(WP44):同一挑战工作区按平台评测交互形态选默认骨架。
 *
 * - [ACM]:stdin/stdout 竞赛版——main 读标准输入、打印标准输出
 *   (CodeForces/AtCoder/洛谷/HackerRank 的评测形态);
 * - [LEETCODE]:Solution 函数版——平台只给函数签名,本地 main 仅作调试桩
 *   (力扣中国/国际站形态)。
 */
enum class TemplateStyle {
    /** 竞赛版:stdin 读入 / stdout 输出的完整程序骨架。 */
    ACM,

    /** 函数版:Solution 类 + main 调试桩(WP16 历史默认)。 */
    LEETCODE,
    ;

    /** 风格×语言的设置存储键(WP45 模版编辑器持久化用),如 "acm:java"。 */
    fun storageKey(languageId: String): String = "${name.lowercase()}:$languageId"
}
