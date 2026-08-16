package com.codequest.ui.submission

import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.WorkspaceLanguage

/**
 * 提交区域标记代码提取结果(WP21)。
 */
sealed interface RegionExtraction {
    /** 成功:[code] 为区域内容拼接([regions] 为闭合区域个数),可能为空串。 */
    data class Extracted(
        val code: String,
        val regions: Int,
    ) : RegionExtraction

    /** 文件中没有 Begin 标记行。 */
    data object MissingBeginMarker : RegionExtraction

    /** 有 Begin 但从未出现 End(未闭合)。 */
    data object MissingEndMarker : RegionExtraction
}

/**
 * 提交区域标记代码提取(WP21,纯 Kotlin)。
 *
 * 语义与旧 Scala `Language.extractCodeFromRegion` 对齐(spec 2026-08-03):
 * - 逐行扫描,行首(忽略缩进)为 `注释前缀 + REGION_BEGIN/END` 的整行才是标记行,
 *   行内其他代码后跟标记文字不算;
 * - Begin..End 之间的行被收集,多个区域按出现顺序拼接;
 * - 区域内再次出现 Begin 不重置累积(旧实现事实);
 * - 只有 Begin 没有 End、或完全没有 Begin,分别报告
 *   [RegionExtraction.MissingEndMarker] / [RegionExtraction.MissingBeginMarker],
 *   供 UI 给出「保留标记行」的错误指引。
 */
object SubmitRegionExtractor {
    /**
     * 从工作区入口/解法文件全文中提取提交代码。
     *
     * @param workspaceLanguageId [com.codequest.core.model.ChallengeLanguage.language];
     *   未知语言时回退为全部已知注释前缀匹配(模板恒来自注册表,此分支仅兜底)
     */
    fun extract(
        fileContent: String,
        workspaceLanguageId: String,
    ): RegionExtraction {
        val prefixes =
            listOfNotNull(WorkspaceLanguage.fromId(workspaceLanguageId)?.commentPrefix)
                .ifEmpty { WorkspaceLanguage.ALL.map { it.commentPrefix }.distinct() }
        val beginMarker = ChallengeTemplates.REGION_BEGIN
        val endMarker = ChallengeTemplates.REGION_END

        var sawBegin = false
        var sawEnd = false
        var inRegion = false
        var regions = 0
        val output = StringBuilder()
        val accumulator = StringBuilder()

        fileContent.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                isMarkerLine(trimmed, prefixes, beginMarker) -> {
                    sawBegin = true
                    inRegion = true
                }

                isMarkerLine(trimmed, prefixes, endMarker) -> {
                    if (inRegion) {
                        inRegion = false
                        sawEnd = true
                        regions++
                        output.append(accumulator)
                        accumulator.setLength(0)
                    }
                }

                inRegion -> {
                    accumulator.append(line).append('\n')
                }
            }
        }

        return when {
            !sawBegin -> RegionExtraction.MissingBeginMarker
            !sawEnd -> RegionExtraction.MissingEndMarker
            else -> RegionExtraction.Extracted(output.toString(), regions)
        }
    }

    /** 整行即注释标记(前缀后允许空白);行内其他代码不算。 */
    private fun isMarkerLine(
        trimmedLine: String,
        commentPrefixes: List<String>,
        marker: String,
    ): Boolean =
        commentPrefixes.any { prefix ->
            trimmedLine == prefix + marker ||
                (trimmedLine.startsWith(prefix) && trimmedLine.removePrefix(prefix).trim() == marker)
        }
}
