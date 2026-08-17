package com.codequest.platform.codeforces

import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP24:CF 语言映射表——programTypeId 数字 id 表与旧实现
 * CODEFORCES_LANGUAGES 逐字一致,workspaceLanguageId 全部可解析。
 */
class CodeForcesLanguagesTest {
    @Test
    fun `every non-null workspace language id resolves in workspace registry`() {
        CodeForcesLanguages.ALL
            .filter { it.workspaceLanguageId != null }
            .forEach { info ->
                assertNotNull(
                    WorkspaceLanguage.fromId(info.workspaceLanguageId!!),
                    "CF 语言 ${info.id} 的 workspaceLanguageId=${info.workspaceLanguageId} 不在 WorkspaceLanguage.ALL",
                )
            }
    }

    @Test
    fun `language ids match legacy program type ids`() {
        // 旧 CODEFORCES_LANGUAGES 逐字事实(抽样覆盖每类)
        assertEquals(
            mapOf(
                "36" to "java", // Java 8(32bit)
                "87" to "java", // Java 21
                "54" to "cpp", // C++17
                "89" to "cpp", // C++20
                "83" to "kotlin", // Kotlin 1.7.20
                "88" to "kotlin", // Kotlin 1.9.21
                "31" to "python3", // Python 3.8.10
                "32" to "go", // Go 1.22.2
            ),
            CodeForcesLanguages.ALL
                .filter { it.id in setOf("36", "87", "54", "89", "83", "88", "31", "32") }
                .associate { it.id to it.workspaceLanguageId },
        )
        // 无工作区对应的语言:workspaceLanguageId 为 null(可提交但不可建 challenge 工作区)
        assertTrue(CodeForcesLanguages.ALL.any { it.id == "75" && it.workspaceLanguageId == null }) // Rust
        assertTrue(CodeForcesLanguages.ALL.any { it.id == "43" && it.workspaceLanguageId == null }) // C
        assertTrue(CodeForcesLanguages.ALL.any { it.id == "7" && it.workspaceLanguageId == null }) // Python 2
    }

    @Test
    fun `every workspace language has a codeforces submit language`() {
        WorkspaceLanguage.ALL.forEach { workspace ->
            assertNotNull(
                CodeForcesLanguages.languageIdForWorkspace(workspace.id),
                "工作区语言 ${workspace.id} 缺少 CF 提交语言映射",
            )
        }
    }

    @Test
    fun `find returns null for unknown id and table is non-empty`() {
        assertNull(CodeForcesLanguages.find("cobol"))
        assertEquals("GNU G++20", CodeForcesLanguages.find("89")!!.displayName)
        assertEquals(29, CodeForcesLanguages.ALL.size) // 旧 CODEFORCES_LANGUAGES 全量 29 项
    }
}
