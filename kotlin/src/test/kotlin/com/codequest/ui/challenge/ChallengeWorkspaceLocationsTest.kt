package com.codequest.ui.challenge

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ProblemId
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [regression] 修复#44:工作区落点跟随「数据库文件夹」设置——
 * - 配置目录 → 新 challenge 落 `<配置>/codequest/<dir>`;
 * - 未配置 → 落 `<项目>/codequest/<dir>`(默认态);
 * - 旧工作区(设置切换前创建)按实际存在位置回迁解析,不"丢"。
 */
class ChallengeWorkspaceLocationsTest {
    @Test
    fun `new challenge lands under configured folder when set`() {
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                dirName = "two-sum-a1b2c3d4",
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
                dirName = "two-sum-a1b2c3d4",
                configuredFolder = "",
                projectBasePath = "E:/proj",
                directoryExists = { false },
            )
        assertEquals(Paths.get("E:/proj/codequest/two-sum-a1b2c3d4"), root)
    }

    @Test
    fun `legacy workspace directly under project root still resolves after setting folder`() {
        val legacyParent = Paths.get("E:/proj") // v1.1.0 旧布局:目录直接在项目根下
        val root =
            ChallengeWorkspaceLocations.workspaceRoot(
                dirName = "two-sum-a1b2c3d4",
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
                dirName = "two-sum-a1b2c3d4",
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
                dirName = "two-sum-a1b2c3d4",
                configuredFolder = "   ",
                projectBasePath = "E:/proj",
                directoryExists = { false },
            )
        assertEquals(Paths.get("E:/proj/codequest/two-sum-a1b2c3d4"), root)
    }

    @Test
    fun `workspace dir name is slug plus id prefix`() {
        val challenge =
            Challenge(
                id = ChallengeId("ffffffff-1111-2222-3333-444444444444"),
                problemId = ProblemId("p-1"),
                name = "Two Sum",
                createdAt = Instant.EPOCH,
            )
        assertEquals("two-sum-ffffffff", ChallengeWorkspaceLocations.workspaceDirName(challenge))
    }
}
