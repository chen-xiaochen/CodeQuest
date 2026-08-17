package com.codequest.platform.atcoder

import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.spi.LanguageInfo

/**
 * AtCoder 提交语言 id 表(WP25)。
 *
 * [LanguageInfo.id] 是提交表单的 `data.LanguageId`(数字串),id/版本对照为旧
 * `AtCoderSettingsConfigurable.ATCODER_LANGUAGES` 逐字事实(51 条全量);
 * displayName 为「语言 + 版本」拼接(旧表 Language + SpecificVersion 的展示形态)。
 *
 * 排序说明:同一语言族内把**标准工作区运行时**排在首位——`languageIdForWorkspace`
 * 取首个匹配,Python 族把 5055(CPython)放在 5033(SageMath)之前,否则
 * 工作区默认提交语言会落到 SageMath;C++ 族保持旧表顺序(5001 C++20 GCC 居首,
 * 即工作区默认)。
 *
 * [LanguageInfo.workspaceLanguageId] 指向 core 的 [WorkspaceLanguage] 注册表 id
 * (仅 Kotlin/Java/Python/C++/Go 五种有工作区语言);其余(Rust/C#/Ruby/PHP 等)
 * 为 null——可提交但不可建 challenge 工作区。
 */
object AtCoderLanguages {
    val ALL: List<LanguageInfo> =
        listOf(
            LanguageInfo("5001", "C++ 20(GCC 12.2)", WorkspaceLanguage.CPP.id),
            LanguageInfo("5028", "C++ 23(GCC 12.2)", WorkspaceLanguage.CPP.id),
            LanguageInfo("5031", "C++ 23(Clang 16.0.6)", WorkspaceLanguage.CPP.id),
            LanguageInfo("5002", "Go 1.20.6", WorkspaceLanguage.GO.id),
            LanguageInfo("5003", "C# 11(.NET 7.0.7)", null),
            LanguageInfo("5004", "Kotlin 1.8.20", WorkspaceLanguage.KOTLIN.id),
            LanguageInfo("5005", "Java 17", WorkspaceLanguage.JAVA.id),
            LanguageInfo("5009", "JavaScript(Node.js 18.6.1)", null),
            LanguageInfo("5010", "JavaScript(Deno 1.35.1)", null),
            LanguageInfo("5011", "R 4.2.1(GNU)", null),
            LanguageInfo("5014", "Swift 5.8.1", null),
            LanguageInfo("5015", "Dart 3.0.5", null),
            LanguageInfo("5016", "PHP 8.2.8", null),
            LanguageInfo("5017", "C(GCC 12.2.0)", null),
            LanguageInfo("5018", "Ruby 3.2.2", null),
            LanguageInfo("5021", "F# 7.0(.NET 7.0.7)", null),
            LanguageInfo("5025", "Haskell(GHC 9.4.5)", null),
            // Python 族:CPython 5055 排首(工作区默认),SageMath/其余随后(旧表逐字)
            LanguageInfo("5055", "Python 3.11.4(CPython)", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("5033", "Python(SageMath 9.5)", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("5063", "Python 3.10.10(Mambaforge / CPython)", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("5082", "Python(Cython 0.29.34)", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("5078", "Python(PyPy 3.10-v7.3.12)", WorkspaceLanguage.PYTHON.id),
            LanguageInfo("5037", "Perl 5.34", null),
            LanguageInfo("5040", "Assembly(NASM 2.15.05 x64)", null),
            LanguageInfo("5041", "Pascal 3.2.2(fpc)", null),
            LanguageInfo("5042", "C# 11(.NET 7.0.7 AOT)", null),
            LanguageInfo("5043", "Lua 5.4.6", null),
            LanguageInfo("5027", "Lua(LuaJIT 2.1.0-beta3)", null),
            LanguageInfo("5047", "Scala 3.3.0(Scala Native 0.4.14)", null),
            LanguageInfo("5050", "Clojure(babashka 1.3.181)", null),
            LanguageInfo("5051", "Erlang 26.0.2", null),
            LanguageInfo("5052", "TypeScript 5.1(Deno 1.35.1)", null),
            LanguageInfo("5053", "C++ 17(GCC 12.2)", WorkspaceLanguage.CPP.id),
            LanguageInfo("5054", "Rust 1.70.0", null),
            LanguageInfo("5056", "Scala 3.3.0(Dotty)", null),
            LanguageInfo("5058", "TypeScript 5.1(Node.js 18.16.1)", null),
            LanguageInfo("5059", "OCaml 5.0.0(ocamlopt)", null),
            LanguageInfo("5064", "Clojure 1.11.1", null),
            LanguageInfo("5071", "Clojure(Carp 0.5.5)", null),
            LanguageInfo("5072", "C++ 17(Clang 16.0.6)", WorkspaceLanguage.CPP.id),
            LanguageInfo("5073", "C++ 20(Clang 16.0.6)", WorkspaceLanguage.CPP.id),
            LanguageInfo("5074", "LLVM Clang 16.0.6", null),
            LanguageInfo("5029", "Common Lisp(SBCL 2.3.6)", null),
            LanguageInfo("5075", "Lisp(Emacs Byte Compile 28.2)", null),
            LanguageInfo("5062", "Lisp(Emacs Native Compile 28.2)", null),
            LanguageInfo("5077", "D(GDC 12.2)", null),
            LanguageInfo("5012", "D(DMD 2.104.0)", null),
            LanguageInfo("5013", "D(LDC 1.32.2)", null),
            LanguageInfo("5081", "ReasonML 3.9.0", null),
            LanguageInfo("5083", "Octave 8.2.0", null),
            LanguageInfo("5085", "Elixir 1.15.2", null),
        )

    /** 按 data.LanguageId 查描述;未知 id 返回 null(调用方在提交前校验)。 */
    fun find(id: String): LanguageInfo? = ALL.firstOrNull { it.id == id }

    /** 工作区语言 id → data.LanguageId(同族取首条标准运行时);无对应返回 null。 */
    fun languageIdForWorkspace(workspaceLanguageId: String): String? = ALL.firstOrNull { it.workspaceLanguageId == workspaceLanguageId }?.id
}
