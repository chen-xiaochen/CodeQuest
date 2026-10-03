package com.codequest.ui.challenge

import com.codequest.spi.TestRunResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 结果表格行(WP54):输入摘要/期望/实际/通过标记([passed] null = 平台信息不足以判定)。 */
data class TestRunCaseRow(
    val inputSummary: String?,
    val expected: String?,
    val actual: String?,
    val passed: Boolean?,
)

/**
 * 云端测试结果的数据组装逻辑核(WP54;1.5.0 对话框删除后由右窗「测试用例」页消费)——
 * 逐用例通过判定、汇总计数、CE/RE 替代表格分支、桥推送 payload 组装。
 *
 * 纯函数、不碰 Swing;单测见 `TestRunResultStateTest`。
 */
object TestRunResultState {
    /**
     * 逐位通过判定:[TestRunResult.compareResult] 位图('1'=过)优先且以位为准;
     * 位图缺失或该位越界时回退 codeAnswers 与 expectedCodeAnswers 逐项相等;
     * 任一侧答案缺失 → null(无法判定)。
     */
    fun passedAt(
        result: TestRunResult,
        index: Int,
    ): Boolean? {
        val bitmap = result.compareResult
        if (!bitmap.isNullOrEmpty() && index < bitmap.length) return bitmap[index] == '1'
        val actual = result.codeAnswers.getOrNull(index) ?: return null
        val expected = result.expectedCodeAnswers.getOrNull(index) ?: return null
        return actual == expected
    }

    /** 结果表格行:行数 = 期望/实际两列表长的最大值,inputs 按位对齐(缺位为 null)。 */
    fun caseRows(
        result: TestRunResult,
        inputs: List<String>,
    ): List<TestRunCaseRow> {
        val size = maxOf(result.expectedCodeAnswers.size, result.codeAnswers.size)
        return (0 until size).map { i ->
            TestRunCaseRow(
                inputSummary = inputs.getOrNull(i),
                expected = result.expectedCodeAnswers.getOrNull(i),
                actual = result.codeAnswers.getOrNull(i),
                passed = passedAt(result, i),
            )
        }
    }

    /** 汇总 (通过数, 总数):平台直报 correctCount/totalCount 优先,缺失时逐行计数。 */
    fun summaryCounts(result: TestRunResult): Pair<Int, Int> {
        val correct = result.correctCount
        val total = result.totalCount
        if (correct != null && total != null) return correct to total
        val rows = caseRows(result, emptyList())
        return rows.count { it.passed == true } to rows.size
    }

    /** CE/RE 全文:非空即以全文区替代表格(编译错误优先于运行错误)。 */
    fun errorText(result: TestRunResult): String? =
        result.compileError?.takeIf { it.isNotBlank() }
            ?: result.runtimeError?.takeIf { it.isNotBlank() }

    /**
     * 桥推送 payload(1.5.0,`testrun:result`,右窗「测试用例」页结果区协议):
     * `{challengeId, passed, total, rows:[{input,expected,actual,passed}], errorKind?, errorText?, mode?}`——
     * `passed` 行级可 null(平台信息不足以判定);`errorKind`("compile"/"runtime")与
     * `errorText` 仅在 CE/RE 时携带,页面以全文区替代表格;`mode`(WP74)仅全示例
     * 测试携带("examples"),页面据此以「全部示例」标题区分工作区用例的「提交测试」。
     */
    fun resultPayload(
        challengeId: String,
        result: TestRunResult,
        inputs: List<String>,
        mode: String? = null,
    ): String =
        buildJsonObject {
            put("challengeId", challengeId)
            val (passed, total) = summaryCounts(result)
            put("passed", passed)
            put("total", total)
            put(
                "rows",
                buildJsonArray {
                    caseRows(result, inputs).forEach { row ->
                        add(
                            buildJsonObject {
                                put("input", row.inputSummary)
                                put("expected", row.expected)
                                put("actual", row.actual)
                                put("passed", row.passed)
                            },
                        )
                    }
                },
            )
            errorText(result)?.let { text ->
                put("errorKind", if (result.compileError?.isNotBlank() == true) "compile" else "runtime")
                put("errorText", text)
            }
            mode?.let { put("mode", it) }
        }.toString()
}
