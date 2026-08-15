package com.codequest.core.workspace

/**
 * 测试用例文本解析(WP16):把一段多用例纯文本解析为 input/expected 列表,
 * 供「粘贴样例批量导入」与平台样例导入复用。
 *
 * 格式约定:
 * ```
 * Input:
 * <任意多行,保留缩进与内部空行>
 * Output:
 * <任意多行>
 * ---
 * Input:
 * ...
 * ```
 * - 段头行(忽略大小写与首尾空白):`Input:` / `Output:`(亦接受 `Expected:`)
 * - 分隔线:整行仅由 `-` 组成且至少 3 个
 * - 各段末尾的连续空行被丢弃;段内空行与缩进原样保留
 * - 每个 case 必须同时有 Input 与 Output 段,否则抛 [IllegalArgumentException]
 */
object TestCaseParser {
    data class ParsedTestCase(
        val input: String,
        val expected: String,
    )

    private val INPUT_HEADER = Regex("""(?i)input\s*:\s*""")
    private val OUTPUT_HEADER = Regex("""(?i)(output|expected)\s*:\s*""")
    private val SEPARATOR = Regex("""-{3,}\s*""")

    fun parse(text: String): List<ParsedTestCase> {
        val cases = mutableListOf<ParsedTestCase>()
        var input: MutableList<String>? = null
        var expected: MutableList<String>? = null

        fun endCase() {
            val capturedInput = input
            val capturedExpected = expected
            if (capturedInput != null && capturedExpected != null) {
                cases += ParsedTestCase(trimTrailingBlankLines(capturedInput), trimTrailingBlankLines(capturedExpected))
            } else {
                require(capturedInput == null && capturedExpected == null) {
                    if (capturedExpected == null) "test case is missing an Output section" else "test case is missing an Input section"
                }
            }
            input = null
            expected = null
        }

        text.lineSequence().forEach { line ->
            when (lineKind(line)) {
                LineKind.INPUT -> {
                    if (input != null || expected != null) endCase()
                    input = mutableListOf()
                }

                LineKind.OUTPUT -> {
                    if (expected != null) endCase()
                    require(input != null) { "Output section appears before any Input section" }
                    expected = mutableListOf()
                }

                LineKind.SEPARATOR -> {
                    endCase()
                }

                LineKind.CONTENT -> {
                    when {
                        input != null && expected == null -> input!!.add(line)
                        expected != null -> expected!!.add(line)
                        // 段头之前的杂项行(标题、空行等)忽略
                    }
                }
            }
        }
        endCase()
        require(cases.isNotEmpty()) { "no Input/Output sections found in text" }
        return cases
    }

    private enum class LineKind {
        INPUT,
        OUTPUT,
        SEPARATOR,
        CONTENT,
    }

    private fun lineKind(line: String): LineKind =
        when {
            INPUT_HEADER.matchEntire(line) != null -> LineKind.INPUT
            OUTPUT_HEADER.matchEntire(line) != null -> LineKind.OUTPUT
            SEPARATOR.matchEntire(line) != null -> LineKind.SEPARATOR
            else -> LineKind.CONTENT
        }

    /** 解析失败返回空列表而非抛异常(UI 容错入口)。 */
    fun parseOrNull(text: String): List<ParsedTestCase> = runCatching { parse(text) }.getOrDefault(emptyList())

    /** 丢弃段尾连续空白行;段内空行保留。 */
    private fun trimTrailingBlankLines(lines: List<String>): String {
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        return lines.subList(0, end).joinToString("\n")
    }
}
