package com.codequest.platform.hackerrank

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * HackerRank HTML 内嵌数据提取(WP27):旧实现经 jsoup 的选择器逻辑,
 * 本实现等价正则化(平台包不引 jsoup,与 LuoguHtml 同法)。
 *
 * - `script#initialData`:内容为 URL 编码 JSON(旧 `Uri.decode(element.html())`),
 *   经 [extractInitialDataJson] 提取并解码;
 * - `script#__NEXT_DATA__`:Next.js 常规内联 JSON(**不做** URL 解码——旧实现对
 *   其误调 Uri.decode,含 `+` 的原文会被吞成空格,本实现修正,端点/字段不变);
 * - `meta#csrf-token`:content 属性(attributes 顺序不定,两种顺序均匹配)。
 */
object HackerRankHtml {
    private val INITIAL_DATA =
        Regex(
            """<script[^>]*id="initialData"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    private val NEXT_DATA =
        Regex(
            """<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    private val META_TAG = Regex("""<meta\s[^>]*>""")

    private val META_CONTENT = Regex("""content="([^"]*)"""")

    /** 提取并 URL 解码 `script#initialData` 的 JSON;缺失/解码失败返回 null。 */
    fun extractInitialDataJson(html: String): String? {
        val raw =
            INITIAL_DATA
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrNull()
    }

    /** 提取 `script#__NEXT_DATA__` 的 JSON 原文(不解码);缺失返回 null。 */
    fun extractNextDataJson(html: String): String? =
        NEXT_DATA
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()

    /** 提取 `meta#csrf-token` 的 content;缺失返回 null。 */
    fun extractCsrfToken(html: String): String? =
        META_TAG
            .findAll(html)
            .filter { tag -> tag.value.contains("""id="csrf-token"""") }
            .mapNotNull { tag ->
                META_CONTENT
                    .find(tag.value)
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf { it.isNotEmpty() }
            }.firstOrNull()
}
