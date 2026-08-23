package com.codequest.ui.challenge

import com.codequest.core.model.TestCase

/**
 * 提交测试链路纯函数核(WP55):dataInput 拼装与错误分支文案键选择。
 *
 * 与平台/IntelliJ 均解耦,单测见 `TestRunPrepTest`;云端测试编排(网络/对话框)
 * 归 [CloudTestCoordinator],动作壳归 `ChallengeEditorActions`。
 */
object TestRunPrep {
    /** 逐用例输入的连接符(原版 runAnswer 语义:多段自定义输入以换行连接)。 */
    const val DATA_INPUT_SEPARATOR = "\n"

    /** 拉取工作区全部用例拼平台测试输入:各用例 input 原样以 \n 连接。 */
    fun dataInput(cases: List<TestCase>): String = cases.joinToString(DATA_INPUT_SEPARATOR) { it.input }

    /** 提交测试发起前的确定性拒绝分支(文案键见 [messageKey];参数由调用方补)。 */
    enum class Refusal {
        /** 文件不属于任何挑战工作区(文案同提交流程)。 */
        NOT_CHALLENGE_FILE,

        /** 缺 Begin/End 区域标记(文案同提交流程,{0}/{1} 为两标记原文)。 */
        MISSING_REGION_MARKERS,

        /** 工作区没有任何测试用例。 */
        NO_TEST_CASES,

        /** 平台未实现 TestEvaluationCapable({0} = 平台展示名)。 */
        PLATFORM_NOT_TESTABLE,

        /** 平台有登录能力但当前无会话({0} = 平台展示名)。 */
        NOT_LOGGED_IN,

        /** 平台未返回题目数字主档 id(interpret 请求体需要)。 */
        QUESTION_ID_MISSING,
    }

    /** 拒绝分支 → PluginBundle 键(非挑战文件/区域标记缺失复用提交侧既有键)。 */
    fun messageKey(refusal: Refusal): String =
        when (refusal) {
            Refusal.NOT_CHALLENGE_FILE -> "submission.error.notChallengeFile"
            Refusal.MISSING_REGION_MARKERS -> "submission.error.regionMarkersMissing"
            Refusal.NO_TEST_CASES -> "testRun.error.noCases"
            Refusal.PLATFORM_NOT_TESTABLE -> "testRun.error.platformNotTestable"
            Refusal.NOT_LOGGED_IN -> "testRun.error.notLoggedIn"
            Refusal.QUESTION_ID_MISSING -> "testRun.error.questionIdMissing"
        }
}
