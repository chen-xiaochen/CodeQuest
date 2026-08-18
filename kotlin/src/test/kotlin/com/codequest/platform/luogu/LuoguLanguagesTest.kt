package com.codequest.platform.luogu

import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] WP26:洛谷语言 id 表——旧 LUOGU_LANGUAGES 24 条逐字对齐、
 * workspace 映射(同族取首条)与查找。
 */
class LuoguLanguagesTest {
    @Test
    fun `language table matches legacy luogu languages map verbatim`() {
        // 旧 LuoGuSettingsConfigurable.LUOGU_LANGUAGES 逐字(24 条)
        val expected =
            mapOf(
                "1" to "Pascal",
                "2" to "C",
                "28" to "C++ 14(GCC 9)",
                "3" to "C++ 98",
                "4" to "C++ 11",
                "11" to "C++ 14",
                "12" to "C++ 17",
                "27" to "C++ 20",
                "7" to "Python 3",
                "25" to "PyPy 3",
                "8" to "Java 8",
                "33" to "Java 21",
                "15" to "Rust",
                "14" to "Go",
                "19" to "Haskell",
                "30" to "OCaml",
                "31" to "Julia",
                "32" to "Lua",
                "21" to "Kotlin",
                "22" to "Scala",
                "17" to "C# (Mono)",
                "9" to "JavaScript (Node.js)",
                "16" to "PHP",
                "13" to "Ruby",
                "23" to "Perl",
            )
        assertEquals(expected.size, LuoguLanguages.ALL.size)
        expected.forEach { (id, _) ->
            assertEquals(id, LuoguLanguages.find(id)?.id, "语言 $id 缺失")
        }
        assertEquals("C++ 14(GCC 9)", LuoguLanguages.find("28")?.displayName)
    }

    @Test
    fun `workspace language mapping picks family default`() {
        assertEquals("28", LuoguLanguages.languageIdForWorkspace(WorkspaceLanguage.CPP.id))
        assertEquals("8", LuoguLanguages.languageIdForWorkspace(WorkspaceLanguage.JAVA.id))
        assertEquals("7", LuoguLanguages.languageIdForWorkspace(WorkspaceLanguage.PYTHON.id))
        assertEquals("21", LuoguLanguages.languageIdForWorkspace(WorkspaceLanguage.KOTLIN.id))
        assertEquals("14", LuoguLanguages.languageIdForWorkspace(WorkspaceLanguage.GO.id))
    }

    @Test
    fun `unknown ids and unmapped workspace languages return null`() {
        assertNull(LuoguLanguages.find("99"))
        assertNull(LuoguLanguages.languageIdForWorkspace("rust"))
    }
}
