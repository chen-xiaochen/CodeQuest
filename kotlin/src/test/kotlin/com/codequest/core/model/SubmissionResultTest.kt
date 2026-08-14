package com.codequest.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [SubmissionResult] 终态判定矩阵单测(WP13)。
 *
 * 语义契约:
 * - isTerminal:Pending/Judging 为非终态(判定还会变化),其余全部为终态
 * - isSuccessful:仅 Accepted 为 true;PartialScore 不算完全通过
 */
class SubmissionResultTest {
    private val json = Json

    @Test
    fun `pending and judging are not terminal`() {
        assertFalse(SubmissionResult.Pending.isTerminal, "Pending 是非终态")
        assertFalse(SubmissionResult.Judging.isTerminal, "Judging 是非终态")
    }

    @Test
    fun `all final verdicts are terminal`() {
        val terminalVerdicts = listOf(
            SubmissionResult.Accepted,
            SubmissionResult.WrongAnswer,
            SubmissionResult.TimeLimitExceeded,
            SubmissionResult.MemoryLimitExceeded,
            SubmissionResult.OutputLimitExceeded,
            SubmissionResult.RuntimeError,
            SubmissionResult.CompileError,
            SubmissionResult.PartialScore(score = 0.75),
            SubmissionResult.Unknown(raw = "frozen"),
        )
        terminalVerdicts.forEach { verdict ->
            assertTrue(verdict.isTerminal, "$verdict 应为终态")
        }
    }

    @Test
    fun `only accepted is successful`() {
        val allVerdicts = listOf(
            SubmissionResult.Pending,
            SubmissionResult.Judging,
            SubmissionResult.Accepted,
            SubmissionResult.WrongAnswer,
            SubmissionResult.TimeLimitExceeded,
            SubmissionResult.MemoryLimitExceeded,
            SubmissionResult.OutputLimitExceeded,
            SubmissionResult.RuntimeError,
            SubmissionResult.CompileError,
            SubmissionResult.PartialScore(score = 1.0),
            SubmissionResult.Unknown(raw = "x"),
        )
        val successful = allVerdicts.filter { it.isSuccessful }
        assertEquals(listOf<SubmissionResult>(SubmissionResult.Accepted), successful)
    }

    @Test
    fun `verdict round trip preserves subtype`() {
        val verdicts: List<SubmissionResult> = listOf(
            SubmissionResult.Pending,
            SubmissionResult.Judging,
            SubmissionResult.Accepted,
            SubmissionResult.WrongAnswer,
            SubmissionResult.TimeLimitExceeded,
            SubmissionResult.MemoryLimitExceeded,
            SubmissionResult.OutputLimitExceeded,
            SubmissionResult.RuntimeError,
            SubmissionResult.CompileError,
            SubmissionResult.PartialScore(score = 0.5),
            SubmissionResult.Unknown(raw = "custom"),
        )
        verdicts.forEach { verdict ->
            val decoded = json.decodeFromString(SubmissionResult.serializer(), json.encodeToString(SubmissionResult.serializer(), verdict))
            assertEquals(verdict, decoded, "$verdict 往返后子类型/负载应保持一致")
        }
    }

    @Test
    fun `sealed discriminator is stable for frontend contract`() {
        // 前端(webview)按 type 字段分发渲染,discriminator 必须是 "type"
        val element = json.parseToJsonElement(
            json.encodeToString(SubmissionResult.serializer(), SubmissionResult.PartialScore(score = 0.5)),
        ).jsonObject
        assertEquals("PartialScore", element["type"]!!.jsonPrimitive.content)
        assertEquals(0.5, element["score"]!!.jsonPrimitive.content.toDouble())
    }
}
