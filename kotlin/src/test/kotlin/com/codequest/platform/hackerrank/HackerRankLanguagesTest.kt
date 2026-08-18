package com.codequest.platform.hackerrank

import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP27:HackerRank 提交语言表——旧 HACKERRANK_LANGUAGES 28 条逐字事实
 * (language+version 拼接形态)、工作区语言映射与未知 id 行为。
 */
class HackerRankLanguagesTest {
    @Test
    fun `language table keeps all 28 legacy entries`() {
        assertEquals(28, HackerRankLanguages.ALL.size)
        val expected =
            setOf(
                "julia", "java", "java8", "java15", "javascript", "r", "kotlin", "typescript",
                "erlang", "cpp", "cpp14", "cpp20", "php", "swift", "rust", "scala", "perl",
                "csharp", "haskell", "go", "ruby", "clojure", "c", "objectivec",
                "python", "python3", "pypy", "pypy3",
            )
        assertEquals(expected, HackerRankLanguages.ALL.map { it.id }.toSet())
    }

    @Test
    fun `find returns entry for known id and null for unknown`() {
        assertEquals("Python 3", HackerRankLanguages.find("python3")?.displayName)
        assertEquals("Java 8", HackerRankLanguages.find("java8")?.displayName)
        assertNull(HackerRankLanguages.find("coffeescript"))
    }

    @Test
    fun `workspace languages map to family head submissions`() {
        // 族首选择见 KDoc:C++ 取 cpp、Java 取 java8、Python 必须 python3(裸 python 为 Python 2)
        assertEquals("cpp", HackerRankLanguages.languageIdForWorkspace(WorkspaceLanguage.CPP.id))
        assertEquals("java8", HackerRankLanguages.languageIdForWorkspace(WorkspaceLanguage.JAVA.id))
        assertEquals("python3", HackerRankLanguages.languageIdForWorkspace(WorkspaceLanguage.PYTHON.id))
        assertEquals("kotlin", HackerRankLanguages.languageIdForWorkspace(WorkspaceLanguage.KOTLIN.id))
        assertEquals("go", HackerRankLanguages.languageIdForWorkspace(WorkspaceLanguage.GO.id))
    }

    @Test
    fun `workspace mapped entries carry workspace language id`() {
        WorkspaceLanguage.ALL.forEach { language ->
            val entry = HackerRankLanguages.ALL.first { it.workspaceLanguageId == language.id }
            assertEquals(language.id, entry.workspaceLanguageId)
        }
        // 其余语言(可提交但不可建工作区)workspaceLanguageId 为 null
        assertTrue(HackerRankLanguages.ALL.count { it.workspaceLanguageId == null } == 28 - WorkspaceLanguage.ALL.size)
    }
}
