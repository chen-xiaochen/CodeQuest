package com.codequest.platform.atcoder

import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] WP25:AtCoderLanguages——旧 ATCODER_LANGUAGES 逐字移植(51 条)、
 * 工作区语言映射(标准运行时优先)与 find 查询。
 */
class AtCoderLanguagesTest {
    @Test
    fun `table is full legacy fact with all ids distinct`() {
        assertEquals(51, AtCoderLanguages.ALL.size)
        assertEquals(51, AtCoderLanguages.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `legacy entries are ported verbatim`() {
        // 旧表逐字抽查(语言 + 版本 → data.LanguageId)
        assertEquals("5001", AtCoderLanguages.find("5001")?.id)
        assertEquals("C++ 20(GCC 12.2)", AtCoderLanguages.find("5001")?.displayName)
        assertEquals("Kotlin 1.8.20", AtCoderLanguages.find("5004")?.displayName)
        assertEquals("Java 17", AtCoderLanguages.find("5005")?.displayName)
        assertEquals("Go 1.20.6", AtCoderLanguages.find("5002")?.displayName)
        assertEquals("Rust 1.70.0", AtCoderLanguages.find("5054")?.displayName)
        assertEquals("Python 3.11.4(CPython)", AtCoderLanguages.find("5055")?.displayName)
        assertEquals("Elixir 1.15.2", AtCoderLanguages.find("5085")?.displayName)
        assertNull(AtCoderLanguages.find("cobol"))
    }

    @Test
    fun `workspace mapping picks standard runtime first`() {
        // Python 族 CPython 5055 优先(而非 SageMath 5033)
        assertEquals("5055", AtCoderLanguages.languageIdForWorkspace(WorkspaceLanguage.PYTHON.id))
        assertEquals("5001", AtCoderLanguages.languageIdForWorkspace(WorkspaceLanguage.CPP.id))
        assertEquals("5004", AtCoderLanguages.languageIdForWorkspace(WorkspaceLanguage.KOTLIN.id))
        assertEquals("5005", AtCoderLanguages.languageIdForWorkspace(WorkspaceLanguage.JAVA.id))
        assertEquals("5002", AtCoderLanguages.languageIdForWorkspace(WorkspaceLanguage.GO.id))
        // 无工作区语言(Rust/C# 等)为 null
        assertNull(AtCoderLanguages.ALL.first { it.id == "5054" }.workspaceLanguageId)
        assertNull(AtCoderLanguages.ALL.first { it.id == "5003" }.workspaceLanguageId)
    }
}
