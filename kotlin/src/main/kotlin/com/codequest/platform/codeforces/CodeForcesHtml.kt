package com.codequest.platform.codeforces

/**
 * CodeForces 轻量 HTML 解析(WP24):只覆盖适配器需要的四个提取点,
 * 对应旧 Scala 实现的 jsoup 选择器事实——
 *
 * | 提取点                       | 旧选择器 / 逻辑                                    | 本实现 |
 * |------------------------------|----------------------------------------------------|--------|
 * | 题面                         | `div#pageContent` 下 statement 区                  | class 词匹配 + `<div>` 嵌套深度计数 |
 * | 登录态标记                   | `div.userbox` 非空                                 | [hasDivElementWithClass] |
 * | 登录用户名                   | `div.personal-sidebar .avatar a` 第 2 个文本       | [extractSidebarUsername] |
 * | 提交结果 id                  | `tr[data-submission-id]` 第一行                    | [extractFirstSubmissionId] |
 * | 提交错误                     | `div.shiftUp > span.error` 且无 style 属性         | [extractSubmitError] |
 *
 * 不引入 jsoup 依赖(新构建无此依赖,WP24 白名单不动 build 脚本):
 * 页面切片由单测夹具固化;HTML 结构变化时解析返回 null / false,
 * 由调用方翻译为 OJException,不会崩溃。
 */
internal object CodeForcesHtml {
    /** class 名安全校验(防止调用方把正则片段当 class 名注入)。 */
    private val CLASS_NAME = Regex("""^[A-Za-z0-9_-]+$""")

    private val DIV_OPEN = Regex("""<div\b""", RegexOption.IGNORE_CASE)
    private val DIV_CLOSE = Regex("""</div\s*>""", RegexOption.IGNORE_CASE)

    /**
     * 提取第一个 class 属性含 [className](完整 class 词,不匹配 `foo-bar` 的前缀/后缀)
     * 的 `<div>` 元素 outerHtml。嵌套 div 以开/闭标签深度计数配对;未闭合或不存在返回 null。
     */
    fun extractDivElementByClass(
        html: String,
        className: String,
    ): String? {
        require(CLASS_NAME.matches(className)) { "非法 class 名: $className" }
        val startTag =
            Regex(
                """<div\b[^>]*\bclass\s*=\s*"[^"]*(?<![\w-])$className(?![\w-])[^"]*"[^>]*>""",
                RegexOption.IGNORE_CASE,
            ).find(html) ?: return null
        val start = startTag.range.first
        var depth = 0
        var cursor = start
        while (true) {
            val nextOpen = DIV_OPEN.find(html, cursor)
            val nextClose = DIV_CLOSE.find(html, cursor)
            if (nextClose == null) return null // 未闭合,结构异常
            if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                depth++
                cursor = nextOpen.range.last + 1
            } else {
                depth--
                if (depth == 0) return html.substring(start, nextClose.range.last + 1)
                cursor = nextClose.range.last + 1
            }
        }
    }

    /** 是否存在 class 含 [className](完整 class 词)的 `<div>`;非 div 标签不命中。 */
    fun hasDivElementWithClass(
        html: String,
        className: String,
    ): Boolean {
        require(CLASS_NAME.matches(className)) { "非法 class 名: $className" }
        return Regex("""<div\b[^>]*\bclass\s*=\s*"[^"]*(?<![\w-])$className(?![\w-])""", RegexOption.IGNORE_CASE)
            .containsMatchIn(html)
    }

    /** 提交响应页第一行 `tr[data-submission-id]` 的提交 id;不存在返回 null。 */
    fun extractFirstSubmissionId(html: String): String? =
        Regex("""<tr\b[^>]*\bdata-submission-id="(\d+)"""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)

    /**
     * 提交失败页错误文本:`div.shiftUp` 区域内第一个 class 含 `error`、
     * **且标签无 style 属性**的 `<span>` 纯文本(旧 `.not("[style]")` 事实);
     * 无错误返回 null。
     */
    fun extractSubmitError(html: String): String? {
        val shiftUp = extractDivElementByClass(html, "shiftUp") ?: return null
        val spanPattern = Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (match in spanPattern.findAll(shiftUp)) {
            val attrs = match.groupValues[1]
            val hasErrorClass = Regex("""\bclass\s*=\s*"[^"]*\berror\b""", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
            val hasStyle = Regex("""\bstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
            if (hasErrorClass && !hasStyle) return stripTags(match.groupValues[2]).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * settings 页侧栏(`div.personal-sidebar`)内 avatar 区第 2 个 `<a>` 的文本
     * (旧实现 `select(".avatar a").get(1).text()` 事实:第 1 个是头像图链接,
     * 其文本为空,不参与过滤)。文本为空或不足两个链接返回 null(调用方兜底)。
     */
    fun extractSidebarUsername(html: String): String? {
        val sidebarAt = html.indexOf("personal-sidebar")
        if (sidebarAt < 0) return null
        val window = html.substring(sidebarAt, minOf(sidebarAt + WINDOW_CHARS, html.length))
        val secondLink =
            Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(window)
                .map { stripTags(it.groupValues[1]).trim() }
                .drop(1) // 跳过头像图链接(第 1 个)
                .firstOrNull()
        return secondLink?.takeIf { it.isNotEmpty() }
    }

    /** 去标签 + 基本实体解码(错误文本/用户名展示路径)。 */
    fun stripTags(fragment: String): String =
        fragment
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    private const val WINDOW_CHARS = 8_000
}
