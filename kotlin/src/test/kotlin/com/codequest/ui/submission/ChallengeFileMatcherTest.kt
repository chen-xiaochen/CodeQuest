package com.codequest.ui.submission

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [unit] WP55:浮动工具栏 canShow 判定纯函数——文件是否落在任一挑战工作区根之下
 * (路径前缀按路径分量匹配,非字符串前缀;支持多基目录产生的多个根)。
 */
class ChallengeFileMatcherTest {
    private val rootA = Paths.get("C:/work/codequest/two-sum-abc12345").toAbsolutePath().normalize()
    private val rootB = Paths.get("D:/data/codequest/two-sum-67890123").toAbsolutePath().normalize()

    @Test
    fun `file directly inside a workspace root matches`() {
        val file = rootA.resolve("TwoSum.java")
        assertTrue(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA)))
    }

    @Test
    fun `file in nested subdirectory of a workspace root matches`() {
        val file = rootA.resolve("solutions").resolve("Approach2.java")
        assertTrue(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA)))
    }

    @Test
    fun `file outside all workspace roots does not match`() {
        val file = rootA.parent.resolve("unrelated").resolve("Main.java")
        assertFalse(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA)))
    }

    @Test
    fun `multiple base dirs - file matches the second root`() {
        val file = rootB.resolve("TwoSum.java")
        assertTrue(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA, rootB)))
    }

    @Test
    fun `multiple base dirs - file under neither root does not match`() {
        val file = rootA.parent.resolve("other-challenge").resolve("Main.java")
        assertFalse(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA, rootB)))
    }

    @Test
    fun `path prefix is component-wise, not string-wise`() {
        // 目录名前缀相同的兄弟工作区(two-sum-abc12345 vs two-sum-abc12345678)
        val siblingRoot = rootA.parent.resolve("two-sum-abc12345678")
        val file = siblingRoot.resolve("TwoSum.java")
        assertFalse(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA)))
    }

    @Test
    fun `unnormalized input paths are normalized before comparison`() {
        // C:/work/codequest/two-sum-abc12345/sub/../Main.kt → 归一化后落在根内
        val file = rootA.resolve("sub").resolve("..").resolve("Main.kt")
        assertTrue(ChallengeFileMatcher.isChallengeFile(file, listOf(rootA)))
    }
}
