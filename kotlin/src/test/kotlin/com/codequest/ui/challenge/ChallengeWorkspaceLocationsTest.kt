package com.codequest.ui.challenge

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ProblemId
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [regression] 修复#44(落点跟随设置) + 1.3.2(目录名 ASCII 化,中文题目录如
 * `回文数-0525d717` 改为 `<slug>-<id>`,根因消除 package 告警) + 旧目录名兼容探测。
 */
class ChallengeWorkspaceLocationsTest {
    private fun challenge(name: String = "Two Sum") =
        Challenge(
            id = ChallengeId("a1b2c3d4-1111-2222-3333-444444444444"),
            problemId = ProblemId("p-1"),
            name = name,
            createdAt = Instant.EPOCH,
        )

    // ------------------------------------------------------------------
    // 目录名(1.3.2)
    // ------------------------------------------------------------------

    @Test
    fun `dir name prefers ascii slug source over title`() {
        assertEquals(
            "palindrome-number-a1b2c3d4",
            ChallengeWorkspaceLocations.workspaceDirName(challenge("回文数"), "palindrome-number"),
        )
    }

    @Test
    fun `dir name falls back to ascii title then fixed prefix`() {
        // slug 缺失 + 英文标题 → 标题 ASCII 化
        assertEquals("two-sum-a1b2c3d4", ChallengeWorkspaceLocations.workspaceDirName(challenge("Two Sum"), null))
        // slug 缺失 + 纯中文标题(滤光) → 固定前缀兜底
        assertEquals("challenge-a1b2c3d4", ChallengeWorkspaceLocations.workspaceDirName(challenge("回文数"), null))
        // slug 本身含中文 → 滤光后回退标题
        assertEquals("two-sum-a1b2c3d4", ChallengeWorkspaceLocations.workspaceDirName(challenge("Two Sum"), "回文数"))
    }

    @Test
    fun `ascii slugify keeps alnum and hyphen only`() {
        assertEquals("two-sum", ChallengeWorkspaceLocations.asciiSlugify("Two Sum"))
        assertEquals("binary-tree-level-order", ChallengeWorkspaceLocations.asciiSlugify("Binary Tree Level-Order"))
        assertEquals("", ChallengeWorkspaceLocations.asciiSlugify("回文数"))
        assertEquals("", ChallengeWorkspaceLocations.asciiSlugify(null))
        assertEquals("a-b", ChallengeWorkspaceLocations.asciiSlugify("a--b"))
        assertEquals("abc123", ChallengeWorkspaceLocations.asciiSlugify("abc123"))
    }

    // ------------------------------------------------------------------
    // 落点(纯函数核心)
    // ------------------------------------------------------------------

    @Test
    fun `new challenge lands under configured folder when set`() {
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge(),
                slugSource = "two-sum",
                configuredFolder = "E:/codequest-data",
                projectBasePath = "E:/proj",
                directoryExists = { false },
            )
        assertEquals(Paths.get("E:/codequest-data/codequest/two-sum-a1b2c3d4"), root)
    }

    @Test
    fun `new challenge lands under project codequest dir by default`() {
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge(),
                slugSource = "two-sum",
                configuredFolder = "",
                projectBasePath = "E:/proj",
                directoryExists = { false },
            )
        assertEquals(Paths.get("E:/proj/codequest/two-sum-a1b2c3d4"), root)
    }

    @Test
    fun `legacy chinese dir name still resolves when new name does not exist`() {
        // 1.3.2 前创建的中文目录(`<slugify(中文名)>-id`)按旧命名规则探测命中,不"丢"
        val legacyDir = Paths.get("E:/proj/codequest/回文数-a1b2c3d4")
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge("回文数"),
                slugSource = "palindrome-number",
                configuredFolder = "",
                projectBasePath = "E:/proj",
                directoryExists = { it == legacyDir },
            )
        assertEquals(legacyDir, root)
    }

    @Test
    fun `new ascii dir name wins over legacy when both exist`() {
        val legacyDir = Paths.get("E:/proj/codequest/回文数-a1b2c3d4")
        val newDir = Paths.get("E:/proj/codequest/palindrome-number-a1b2c3d4")
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge("回文数"),
                slugSource = "palindrome-number",
                configuredFolder = "",
                projectBasePath = "E:/proj",
                directoryExists = { it == newDir || it == legacyDir },
            )
        assertEquals(newDir, root)
    }

    @Test
    fun `legacy workspace directly under project root still resolves after setting folder`() {
        val legacyParent = Paths.get("E:/proj") // v1.1.0 旧布局:目录直接在项目根下
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge(),
                slugSource = "two-sum",
                configuredFolder = "E:/codequest-data",
                projectBasePath = "E:/proj",
                directoryExists = { candidate -> candidate.parent == legacyParent },
            )
        assertEquals(Paths.get("E:/proj/two-sum-a1b2c3d4"), root)
    }

    @Test
    fun `workspace under configured folder wins over legacy location`() {
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge(),
                slugSource = "two-sum",
                configuredFolder = "E:/codequest-data",
                projectBasePath = "E:/proj",
                directoryExists = { candidate -> candidate.startsWith(Paths.get("E:/codequest-data")) },
            )
        assertEquals(Paths.get("E:/codequest-data/codequest/two-sum-a1b2c3d4"), root)
    }

    @Test
    fun `blank configured folder is treated as unset`() {
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                challenge = challenge(),
                slugSource = "two-sum",
                configuredFolder = "   ",
                projectBasePath = "E:/proj",
                directoryExists = { false },
            )
        assertEquals(Paths.get("E:/proj/codequest/two-sum-a1b2c3d4"), root)
    }
}
