package com.codequest.ui.challenge

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId
import com.codequest.core.model.TestCaseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP55:提交测试链路纯函数——dataInput 拼装(逐用例输入以 \n 连接,原版
 * runAnswer 语义)与错误分支文案键选择(非挑战文件/区域标记缺失复用提交流程键,
 * 云端测试专属分支走 testRun.* 新键)。
 */
class TestRunPrepTest {
    private fun case(
        input: String,
        id: String = "t",
    ) = TestCase(
        id = TestCaseId(id),
        challengeId = ChallengeId("c"),
        input = input,
        expected = "out",
        source = TestCaseSource.USER,
    )

    @Test
    fun `single case input passes through as dataInput`() {
        assertEquals("[1,2,7,11]\n9", TestRunPrep.dataInput(listOf(case("[1,2,7,11]\n9"))))
    }

    @Test
    fun `multiple case inputs are joined with newline`() {
        assertEquals("a\nb\nc", TestRunPrep.dataInput(listOf(case("a", "1"), case("b", "2"), case("c", "3"))))
    }

    @Test
    fun `inputs keep their own newlines untouched`() {
        assertEquals("1 2\n3 4\n---x\ny", TestRunPrep.dataInput(listOf(case("1 2\n3 4"), case("---x\ny", "2"))))
    }

    @Test
    fun `refusal message keys - shared with the submit flow`() {
        assertEquals(
            "submission.error.notChallengeFile",
            TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_CHALLENGE_FILE),
        )
        assertEquals(
            "submission.error.regionMarkersMissing",
            TestRunPrep.messageKey(TestRunPrep.Refusal.MISSING_REGION_MARKERS),
        )
    }

    @Test
    fun `refusal message keys - cloud-test specific`() {
        assertEquals("testRun.error.noCases", TestRunPrep.messageKey(TestRunPrep.Refusal.NO_TEST_CASES))
        assertEquals(
            "testRun.error.platformNotTestable",
            TestRunPrep.messageKey(TestRunPrep.Refusal.PLATFORM_NOT_TESTABLE),
        )
        assertEquals("testRun.error.notLoggedIn", TestRunPrep.messageKey(TestRunPrep.Refusal.NOT_LOGGED_IN))
        assertEquals(
            "testRun.error.questionIdMissing",
            TestRunPrep.messageKey(TestRunPrep.Refusal.QUESTION_ID_MISSING),
        )
        assertEquals("testRun.error.noExamples", TestRunPrep.messageKey(TestRunPrep.Refusal.NO_EXAMPLES))
    }

    // ------------------------------------------------------------------
    // WP74:全示例测试——exampleTestcases 组装(保序 join/空白过滤/空判定)
    // ------------------------------------------------------------------

    @Test
    fun `example cases keep their order and join with newline`() {
        assertEquals(
            "[2,7,11,15]\n9\n[3,2,4]\n6\n[3,3]\n6",
            TestRunPrep.exampleDataInput(listOf("[2,7,11,15]\n9", "[3,2,4]\n6", "[3,3]\n6")),
        )
    }

    @Test
    fun `single example passes through as dataInput`() {
        assertEquals("[1,2,7,11]\n9", TestRunPrep.exampleDataInput(listOf("[1,2,7,11]\n9")))
    }

    @Test
    fun `blank example elements are filtered before join`() {
        assertEquals(listOf("a", "c"), TestRunPrep.exampleCases(listOf("a", " ", "\n", "", "c")))
        assertEquals("a\nc", TestRunPrep.exampleDataInput(listOf("a", " ", "\n", "", "c")))
    }

    @Test
    fun `empty or all-blank example lists mean no examples`() {
        assertTrue(TestRunPrep.exampleCases(emptyList()).isEmpty())
        assertTrue(TestRunPrep.exampleCases(listOf("", " \n")).isEmpty())
        assertEquals("", TestRunPrep.exampleDataInput(listOf("", " ")))
    }
}
