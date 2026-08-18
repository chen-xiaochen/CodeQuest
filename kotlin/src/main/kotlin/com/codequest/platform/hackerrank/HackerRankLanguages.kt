package com.codequest.platform.hackerrank

import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.spi.LanguageInfo

/**
 * HackerRank 提交语言 id 表(WP27)。
 *
 * [LanguageInfo.id] 是提交 JSON body 的 `language` 值 = 旧 `Language.value +
 * LanguageVersion.version` 拼接(如 (Java, 8) → "java8"、(Cpp, AnyVersion) → "cpp"),
 * 语言/版本对为旧 `HackerRankSettingsConfigurable.HACKERRANK_LANGUAGES` 逐字事实
 * (28 条全量);displayName 为「语言 + 版本」拼接(旧表 Language + SpecificVersion
 * 的展示形态)。
 *
 * 排序说明:旧表展示前 `.sorted`(按 Language 枚举序);本表改为按语言族聚组、
 * 工作区默认版本置族首——`languageIdForWorkspace` 取首个匹配。族首选择:
 * - C++/Kotlin/Go/Ruby 等单 AnyVersion 语言取其本身;
 * - Java 族取 "java8"(旧表 AnyVersion "java" 为早期编译器,显式版本作工作区基线);
 * - Python 族取 "python3"(旧表 AnyVersion "python" 在 HackerRank 为 Python 2,
 *   工作区 Python 3 语义必须选显式版本);Pypy 族同理取 "pypy3"。
 *
 * [LanguageInfo.workspaceLanguageId] 指向 core 的 [WorkspaceLanguage] 注册表 id
 * (仅 Kotlin/Java/Python/C++/Go 五种有工作区语言);其余(Julia/JS/R/Erlang/PHP/
 * Swift/Rust/Scala/Perl/C#/Haskell/Clojure/C/ObjectiveC 等)为 null——可提交但
 * 不可建 challenge 工作区。
 */
object HackerRankLanguages {
    val ALL: List<LanguageInfo> =
        listOf(
            // C++ 族:cpp(旧 AnyVersion 首条)为工作区默认,cpp14/cpp20 随后
            LanguageInfo("cpp", "C++", WorkspaceLanguage.CPP.id),
            LanguageInfo("cpp14", "C++ 14", null),
            LanguageInfo("cpp20", "C++ 20", null),
            // Java 族:显式 java8 为工作区基线
            LanguageInfo("java8", "Java 8", WorkspaceLanguage.JAVA.id),
            LanguageInfo("java", "Java", null),
            LanguageInfo("java15", "Java 15", null),
            // Python 族:python(AnyVersion)在 HackerRank 为 Python 2,工作区必须选 python3
            LanguageInfo("python3", "Python 3", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("python", "Python 2", null),
            LanguageInfo("pypy3", "Pypy 3", null),
            LanguageInfo("pypy", "Pypy", null),
            LanguageInfo("kotlin", "Kotlin", WorkspaceLanguage.KOTLIN.id),
            LanguageInfo("go", "Go", WorkspaceLanguage.GO.id),
            LanguageInfo("javascript", "Javascript", null),
            LanguageInfo("typescript", "Typescript", null),
            LanguageInfo("csharp", "C#", null),
            LanguageInfo("c", "C", null),
            LanguageInfo("objectivec", "ObjectiveC", null),
            LanguageInfo("r", "R", null),
            LanguageInfo("erlang", "Erlang", null),
            LanguageInfo("haskell", "Haskell", null),
            LanguageInfo("scala", "Scala", null),
            LanguageInfo("swift", "Swift", null),
            LanguageInfo("rust", "Rust", null),
            LanguageInfo("php", "PHP", null),
            LanguageInfo("perl", "Perl", null),
            LanguageInfo("ruby", "Ruby", null),
            LanguageInfo("clojure", "Clojure", null),
            LanguageInfo("julia", "Julia", null),
        )

    /** 按 language id 查描述;未知 id 返回 null(调用方在提交前校验)。 */
    fun find(id: String): LanguageInfo? = ALL.firstOrNull { it.id == id }

    /** 工作区语言 id → HackerRank language 值(族首即默认);无对应返回 null。 */
    fun languageIdForWorkspace(workspaceLanguageId: String): String? = ALL.firstOrNull { it.workspaceLanguageId == workspaceLanguageId }?.id
}
