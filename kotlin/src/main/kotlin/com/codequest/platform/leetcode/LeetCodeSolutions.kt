package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.spi.SolutionArticleContent
import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable

/**
 * LeetCode 题解浏览(WP19 列表 / WP31 正文):SolutionViewCapable 实现。
 *
 * CN/COM 站点使用不同查询(见 [LeetCodeApi.fetchSolutionArticles] /
 * [LeetCodeApi.fetchSolutionArticleDetail]);正文为 Markdown 原文,
 * COM 站点响应为二次转义形态,渲染前经 [unescapeLeetCodeArticleContent]
 * 还原(旧 ArticleDetailPresenter.UNESCAPE_LEETCODE 事实)。
 */
class LeetCodeSolutions(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
) : SolutionViewCapable {
    override suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef> =
        api.fetchSolutionArticles(remoteId).map { article ->
            SolutionArticleRef(
                id = article.slug,
                title = article.title,
                // 浏览器兜底出口:LeetCode 文章页 /articles/{slug}/(站点自动重定向到最新路径)
                url = "https://${variant.domain}/articles/${article.slug}/",
            )
        }

    override suspend fun fetchSolutionContent(
        remoteId: String,
        solutionId: String,
    ): SolutionArticleContent {
        val article = api.fetchSolutionArticleDetail(solutionId)
        return SolutionArticleContent(
            id = article.slug.ifEmpty { solutionId },
            title = article.title,
            content =
                if (variant.isCn) {
                    article.content
                } else {
                    unescapeLeetCodeArticleContent(article.content)
                },
            contentFormat = ContentFormat.MARKDOWN,
        )
    }
}

/**
 * COM 站点题解正文反转义(WP31;旧 ArticleDetailPresenter.UNESCAPE_LEETCODE 事实:
 * commons-text OctalUnescaper + UnicodeUnescaper + LookupTranslator
 * (`\n`→换行、`\\`→反斜杠、`\"`→双引号、`\'`→单引号)的聚合行为)。
 *
 * COM 站点把正文以字面 `\n` 等转义序列下发(CN 原样),渲染管线按 Markdown
 * 解析前须还原;未识别的转义序列按原样保留(与 LookupTranslator 语义一致)。
 */
internal fun unescapeLeetCodeArticleContent(content: String): String {
    val builder = StringBuilder(content.length)
    var index = 0
    while (index < content.length) {
        val char = content[index]
        if (char != ESCAPE || index + 1 >= content.length) {
            builder.append(char)
            index++
            continue
        }
        val next = content[index + 1]
        when {
            next == 'n' -> {
                builder.append('\n')
                index += 2
            }

            next == '\\' -> {
                builder.append('\\')
                index += 2
            }

            next == '"' -> {
                builder.append('"')
                index += 2
            }

            next == '\'' -> {
                builder.append('\'')
                index += 2
            }

            next == 'u' -> {
                val hexEnd = minOf(index + UNICODE_ESCAPE_END, content.length)
                val hex = content.substring(index + UNICODE_ESCAPE_START, hexEnd)
                val code = hex.toIntOrNull(HEX_RADIX)
                if (hex.length == UNICODE_DIGITS && code != null) {
                    builder.append(code.toChar())
                    index += UNICODE_ESCAPE_END
                } else {
                    builder.append(char)
                    index++
                }
            }

            next in OCTAL_DIGIT_RANGE -> {
                var end = index + 2
                while (end < minOf(index + OCTAL_ESCAPE_END, content.length) && content[end] in OCTAL_DIGIT_RANGE) {
                    end++
                }
                val octal = content.substring(index + 1, end).toIntOrNull(OCTAL_RADIX)
                if (octal != null && octal <= MAX_OCTAL_VALUE) {
                    builder.append(octal.toChar())
                    index = end
                } else {
                    builder.append(char)
                    index++
                }
            }

            else -> {
                builder.append(char)
                index++
            }
        }
    }
    return builder.toString()
}

private const val ESCAPE = '\\'

private const val UNICODE_DIGITS = 4

private const val UNICODE_ESCAPE_START = 2

private const val UNICODE_ESCAPE_END = 6

private const val HEX_RADIX = 16

private val OCTAL_DIGIT_RANGE = '0'..'7'

private const val OCTAL_RADIX = 8

private const val OCTAL_ESCAPE_END = 4

private const val MAX_OCTAL_VALUE = 0xFF
