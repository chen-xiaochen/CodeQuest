package com.codequest.platform.luogu

import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.spi.LanguageInfo

/**
 * 洛谷提交语言 id 表(WP26)。
 *
 * [LanguageInfo.id] 是提交 JSON body 的 `lang` 值(数字串),id/版本对照为旧
 * `LuoGuSettingsConfigurable.LUOGU_LANGUAGES` 逐字事实(24 条全量,含 AnyVersion
 * 与 SpecificVersion);displayName 为「语言 + 版本」拼接(旧表 Language +
 * SpecificVersion 的展示形态)。
 *
 * 排序说明:旧表是无序 Map(展示前才 sorted),本表改为按语言族聚组排列、工作区
 * 默认版本置族首——`languageIdForWorkspace` 取首个匹配,故 C++ 族取 28(C++14
 * GCC 9,洛谷默认编译器)、Java 族取 8(Java 8,旧表 AnyVersion 之外的通用版本)、
 * Python 族取 7(Python 3 而非 PyPy 3)。
 *
 * [LanguageInfo.workspaceLanguageId] 指向 core 的 [WorkspaceLanguage] 注册表 id
 * (仅 Kotlin/Java/Python/C++/Go 五种有工作区语言);其余(Pascal/Rust/Haskell/
 * OCaml/Julia/Lua/Scala/C#/JS/PHP/Ruby/Perl)为 null——可提交但不可建 challenge 工作区。
 */
object LuoguLanguages {
    val ALL: List<LanguageInfo> =
        listOf(
            LanguageInfo("28", "C++ 14(GCC 9)", WorkspaceLanguage.CPP.id),
            LanguageInfo("3", "C++ 98", null),
            LanguageInfo("4", "C++ 11", null),
            LanguageInfo("11", "C++ 14", null),
            LanguageInfo("12", "C++ 17", null),
            LanguageInfo("27", "C++ 20", null),
            LanguageInfo("2", "C", null),
            LanguageInfo("7", "Python 3", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("25", "PyPy 3", null),
            LanguageInfo("8", "Java 8", WorkspaceLanguage.JAVA.id),
            LanguageInfo("33", "Java 21", null),
            LanguageInfo("21", "Kotlin", WorkspaceLanguage.KOTLIN.id),
            LanguageInfo("22", "Scala", null),
            LanguageInfo("14", "Go", WorkspaceLanguage.GO.id),
            LanguageInfo("15", "Rust", null),
            LanguageInfo("17", "C# (Mono)", null),
            LanguageInfo("9", "JavaScript (Node.js)", null),
            LanguageInfo("16", "PHP", null),
            LanguageInfo("13", "Ruby", null),
            LanguageInfo("23", "Perl", null),
            LanguageInfo("19", "Haskell", null),
            LanguageInfo("30", "OCaml", null),
            LanguageInfo("31", "Julia", null),
            LanguageInfo("32", "Lua", null),
            LanguageInfo("1", "Pascal", null),
        )

    /** 按 `lang` id 查描述;未知 id 返回 null(调用方在提交前校验)。 */
    fun find(id: String): LanguageInfo? = ALL.firstOrNull { it.id == id }

    /** 工作区语言 id → 洛谷 `lang` id(同族取旧表首条);无对应返回 null。 */
    fun languageIdForWorkspace(workspaceLanguageId: String): String? = ALL.firstOrNull { it.workspaceLanguageId == workspaceLanguageId }?.id
}
