package com.codequest.platform.codeforces

import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.spi.LanguageInfo

/**
 * CodeForces 提交语言 id 表(WP24)。
 *
 * [LanguageInfo.id] 是提交表单的 `programTypeId`(数字串),id/版本对照为旧实现
 * `CodeForcesSettingsConfigurable.CODEFORCES_LANGUAGES` 逐字事实;displayName 沿用
 * 旧表的「语言 + SpecificVersion」形态。[LanguageInfo.workspaceLanguageId] 指向 core
 * 的 [WorkspaceLanguage] 注册表 id(同语言多版本取首个匹配);无对应工作区语言的
 * (Rust/C#/Python2/Scala 等)为 null——可提交但不可建 challenge 工作区。
 */
object CodeForcesLanguages {
    val ALL: List<LanguageInfo> =
        listOf(
            LanguageInfo("36", "Java 8(32bit)", WorkspaceLanguage.JAVA.id),
            LanguageInfo("87", "Java 21(64bit)", WorkspaceLanguage.JAVA.id),
            LanguageInfo("43", "C 11(GCC 5.1.0)", null),
            LanguageInfo("54", "C++17(GCC 7.3.0)", WorkspaceLanguage.CPP.id),
            LanguageInfo("89", "GNU G++20", WorkspaceLanguage.CPP.id),
            LanguageInfo("91", "GNU G++23", WorkspaceLanguage.CPP.id),
            LanguageInfo("65", "C# 8(.NET Core 3.1)", null),
            LanguageInfo("79", "C# 10(.NET SDK 6.0)", null),
            LanguageInfo("9", "C#(Mono 6.8)", null),
            LanguageInfo("83", "Kotlin 1.7.20", WorkspaceLanguage.KOTLIN.id),
            LanguageInfo("88", "Kotlin 1.9.21", WorkspaceLanguage.KOTLIN.id),
            LanguageInfo("19", "OCaml 4.02.1", null),
            LanguageInfo("3", "Delphi 7", null),
            LanguageInfo("4", "Pascal 3.2.2(Free)", null),
            LanguageInfo("28", "D(DMD32 v2.105.0)", null),
            LanguageInfo("32", "Go 1.22.2", WorkspaceLanguage.GO.id),
            LanguageInfo("12", "Haskell(GHC 8.10.1)", null),
            LanguageInfo("13", "Perl 5.20.1", null),
            LanguageInfo("6", "PHP 8.1.7", null),
            LanguageInfo("7", "Python 2.7.18", null),
            LanguageInfo("31", "Python 3.8.10", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("40", "PyPy 2.7.13(7.3.0)", null),
            LanguageInfo("41", "PyPy 3.6.9(7.3.0)", null),
            LanguageInfo("70", "PyPy 3.10(7.3.15 64bit)", null),
            LanguageInfo("67", "Ruby 3.2.2", null),
            LanguageInfo("75", "Rust 1.75.0(2021)", null),
            LanguageInfo("20", "Scala 2.12.8", null),
            LanguageInfo("34", "JavaScript(V8 4.8.0)", null),
            LanguageInfo("55", "Node.js 15.8.0 64bit", null),
        )

    /** 按 programTypeId 查描述;未知 id 返回 null(调用方在提交前校验)。 */
    fun find(id: String): LanguageInfo? = ALL.firstOrNull { it.id == id }

    /** 工作区语言 id → programTypeId(多版本取首个匹配);无对应返回 null。 */
    fun languageIdForWorkspace(workspaceLanguageId: String): String? = ALL.firstOrNull { it.workspaceLanguageId == workspaceLanguageId }?.id
}
