package com.codequest.ui.challenge

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] 1.5.1:模板变量 challenge.packageName 的纯函数核——工作区目录相对最近
 * source root 的包名(最长匹配优先;非法包段 null;目录不在任何 root 下 null)。
 */
class JavaPackageDeclarationTest {
    @Test
    fun `derives package from deepest matching source root`() {
        val src = Paths.get("E:/algoCode/src")
        val gen = Paths.get("E:/algoCode/gen")
        val dir = Paths.get("E:/algoCode/src/codequest/two_sum_a1b2c3d4")
        assertEquals("codequest.two_sum_a1b2c3d4", JavaPackageDeclaration.relativePackageName(listOf(src, gen), dir))
    }

    @Test
    fun `returns null when dir is not under any source root`() {
        val src = Paths.get("E:/algoCode/src")
        assertNull(JavaPackageDeclaration.relativePackageName(listOf(src), Paths.get("E:/other/codequest/ws")))
        assertNull(JavaPackageDeclaration.relativePackageName(emptyList(), Paths.get("E:/any")))
    }

    @Test
    fun `returns null when dir equals source root or has illegal segments`() {
        val src = Paths.get("E:/algoCode/src")
        // 目录=root 本身(文件在 source root 直下,无 package 合法)
        assertNull(JavaPackageDeclaration.relativePackageName(listOf(src), src))
        // 历史目录段含连字符/中文 → 非法包段,放弃(不做转写,保真实性)
        assertNull(JavaPackageDeclaration.relativePackageName(listOf(src), Paths.get("E:/algoCode/src/two-sum")))
        assertNull(JavaPackageDeclaration.relativePackageName(listOf(src), Paths.get("E:/algoCode/src/回文数")))
    }
}
