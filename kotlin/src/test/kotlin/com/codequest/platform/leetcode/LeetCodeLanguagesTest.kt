package com.codequest.platform.leetcode

import com.codequest.core.workspace.WorkspaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP19:LeetCode 语言映射表完整性——
 * 1) 每个非空 workspaceLanguageId 必须能在 core 的 WorkspaceLanguage 注册表解析;
 * 2) WorkspaceLanguage 注册表全覆盖(每种工作区语言都有 LeetCode 提交语言);
 * 3) 关键语言 id 与旧实现拼接事实一致(java/kotlin/python3/cpp/golang)。
 */
class LeetCodeLanguagesTest {
    @Test
    fun `every non-null workspace language id resolves in workspace registry`() {
        LeetCodeLanguages.ALL
            .filter { it.workspaceLanguageId != null }
            .forEach { info ->
                assertNotNull(
                    WorkspaceLanguage.fromId(info.workspaceLanguageId!!),
                    "LeetCode 语言 ${info.id} 的 workspaceLanguageId=${info.workspaceLanguageId} 不在 WorkspaceLanguage.ALL",
                )
            }
    }

    @Test
    fun `every workspace language has a leetcode submit language`() {
        WorkspaceLanguage.ALL.forEach { workspace ->
            assertNotNull(
                LeetCodeLanguages.languageIdForWorkspace(workspace.id),
                "工作区语言 ${workspace.id} 缺少 LeetCode 提交语言映射",
            )
        }
    }

    @Test
    fun `language ids match legacy slug facts`() {
        // 旧实现拼接事实:go → golang、python+3 → python3、其余原样
        assertEquals("kotlin", LeetCodeLanguages.languageIdForWorkspace("kotlin"))
        assertEquals("java", LeetCodeLanguages.languageIdForWorkspace("java"))
        assertEquals("python3", LeetCodeLanguages.languageIdForWorkspace("python3"))
        assertEquals("cpp", LeetCodeLanguages.languageIdForWorkspace("cpp"))
        assertEquals("golang", LeetCodeLanguages.languageIdForWorkspace("go"))
        assertTrue(LeetCodeLanguages.ALL.any { it.id == "rust" && it.workspaceLanguageId == null })
    }

    @Test
    fun `find returns null for unknown id`() {
        assertNull(LeetCodeLanguages.find("cobol"))
        assertEquals("Kotlin", LeetCodeLanguages.find("kotlin")!!.displayName)
    }
}
