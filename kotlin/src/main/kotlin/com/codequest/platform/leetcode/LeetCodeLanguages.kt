package com.codequest.platform.leetcode

import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.spi.LanguageInfo

/**
 * LeetCode 提交语言 id 表(WP19)。
 *
 * [LanguageInfo.id] 是提交时传给 LeetCode 的 `lang` 值(旧实现
 * `dojo.leetCodeLanguage(language, languageVersion)` 的拼接事实:go → "golang",
 * python + 版本 → "python3",其余 `语言 + 版本`);[LanguageInfo.workspaceLanguageId]
 * 指向 core 的 [WorkspaceLanguage] 注册表 id,供「工作区语言 → 平台提交语言」映射。
 * 无对应工作区语言的语言(如 Rust/Swift)为 null——可提交但不可建 challenge 工作区。
 */
object LeetCodeLanguages {
    val ALL: List<LanguageInfo> =
        listOf(
            LanguageInfo("cpp", "C++", WorkspaceLanguage.CPP.id),
            LanguageInfo("java", "Java", WorkspaceLanguage.JAVA.id),
            LanguageInfo("kotlin", "Kotlin", WorkspaceLanguage.KOTLIN.id),
            LanguageInfo("python3", "Python3", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("golang", "Go", WorkspaceLanguage.GO.id),
            LanguageInfo("c", "C", null),
            LanguageInfo("csharp", "C#", null),
            LanguageInfo("javascript", "JavaScript", null),
            LanguageInfo("typescript", "TypeScript", null),
            LanguageInfo("rust", "Rust", null),
            LanguageInfo("swift", "Swift", null),
            LanguageInfo("php", "PHP", null),
            LanguageInfo("ruby", "Ruby", null),
            LanguageInfo("scala", "Scala", null),
            LanguageInfo("dart", "Dart", null),
            LanguageInfo("erlang", "Erlang", null),
            LanguageInfo("elixir", "Elixir", null),
        )

    /** 按 LeetCode 语言 id 查描述;未知 id 返回 null(调用方在提交前校验)。 */
    fun find(id: String): LanguageInfo? = ALL.firstOrNull { it.id == id }

    /** 工作区语言 id → LeetCode 语言 id;无对应返回 null。 */
    fun languageIdForWorkspace(workspaceLanguageId: String): String? = ALL.firstOrNull { it.workspaceLanguageId == workspaceLanguageId }?.id
}
