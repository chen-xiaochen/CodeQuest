package com.codequest.platform.atcoder

/**
 * AtCoder 轻量 HTML 解析(WP25):只覆盖适配器需要的提取点,
 * 对应旧 Scala 实现的 jsoup 选择器事实——
 *
 * | 提取点          | 旧选择器 / 逻辑                                       | 本实现 |
 * * |-----------------|-------------------------------------------------------|--------|
 * | 登录态标记      | `#main-container #user-nav-tabs` 非空                  | [hasUserNavTabs] |
 * | 登录用户名      | `input[id='ui.UserName']` 的 value 属性                 | [extractUserName] |
 * | 题面            | `div#main-container div.row > div` 第二个               | [extractStatement] |
 * | 提交错误        | `div[role='alert']` 首个文本                            | [extractSubmitError] |
 * | 提交记录 id     | `td.submission-score[data-id]` 首个 data-id 属性        | [extractSubmissionId] |
 * | 评测状态        | `td#judge-status > span` 的 title 属性 + 文本           | [extractJudgeStatus] |
 *
 * 不引入 jsoup 依赖(新构建无此依赖,WP25 白名单不动 build 脚本):
 * 页面切片由单测夹具固化;HTML 结构变化时解析返回 null / false,
 * 由调用方翻译为 OJException,不会崩溃。div 配对以开/闭标签深度计数。
 */
internal object AtCoderHtml {
    private val DIV_OPEN = Regex("""<div\b""", RegexOption.IGNORE_CASE)
    private val DIV_CLOSE = Regex("""</div\s*>""", RegexOption.IGNORE_CASE)

    private val INPUT_TAG = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** settings 页是否含 `#user-nav-tabs`(旧 checkLogin 事实:存在即已登录)。 */
    fun hasUserNavTabs(html: String): Boolean = Regex("""id\s*=\s*["']user-nav-tabs["']""").containsMatchIn(html)

    /**
     * settings 页账号表单 `ui.UserName` 输入框的 value(旧 getUserInfo 事实);
     * 属性顺序无关,值空白或缺失返回 null(调用方兜底)。
     */
    fun extractUserName(html: String): String? =
        INPUT_TAG
            .findAll(html)
            .map { it.value }
            .firstOrNull { tag -> Regex("""\bid\s*=\s*["']ui\.UserName["']""").containsMatchIn(tag) }
            ?.let { tag ->
                Regex("""\bvalue\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)
            }?.takeIf { it.isNotBlank() }

    /**
     * 题面:`#main-container` 内第一个 `div.row` 的第二个直接子 div outerHtml
     * (旧 `div#main-container div.row > div` `.drop(1).headOption`;第一个子 div
     * 是题目侧栏导航,第二个是题面主体)。结构不符返回 null。
     */
    fun extractStatement(html: String): String? {
        val container = extractDivElementById(html, "main-container") ?: return null
        val row = firstDivElementWithClassToken(container, "row") ?: return null
        return directChildDivs(row).getOrNull(STATEMENT_CHILD_INDEX)
    }

    /** 提交失败页第一个 `role="alert"` 的 div 文本(旧 `.ownText().trim`;无则 null)。 */
    fun extractSubmitError(html: String): String? {
        val openTag =
            Regex("""<div\b[^>]*\brole\s*=\s*["']alert["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html) ?: return null
        val alertDiv = balancedDiv(html, openTag.range.first) ?: return null
        return stripTags(alertDiv).trim().takeIf { it.isNotEmpty() }
    }

    /** 提交响应页第一个 `td.submission-score[data-id]` 的提交 id;不存在返回 null。 */
    fun extractSubmissionId(html: String): String? =
        Regex("""<td\b([^>]*)>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull(::isSubmissionScoreCell)
            ?.let { attrs -> DATA_ID.find(attrs)?.groupValues?.get(1) }

    /**
     * 提交详情页 `td#judge-status > span` 的判定:title 属性(如 "Judging" /
     * 全称)+ 文本短码(如 "AC"/"TLE"/"WJ",旧 judgeStatusToSubmissionResult 的
     * 双输入);无该单元格返回 null。
     */
    fun extractJudgeStatus(html: String): JudgeStatus? {
        val tdOpen =
            Regex("""<td\b[^>]*\bid\s*=\s*["']judge-status["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html) ?: return null
        val span =
            Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html, tdOpen.range.last) ?: return null
        val title =
            Regex("""\btitle\s*=\s*["']([^"']*)["']""")
                .find(span.groupValues[1])
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return JudgeStatus(title = title, label = stripTags(span.groupValues[2]).trim())
    }

    /** 判定双输入(title 属性 + 短码文本),消费方见 judgeStatusToResult。 */
    data class JudgeStatus(
        val title: String,
        val label: String,
    )

    /** 去标签 + 基本实体解码(错误文本/判定短码展示路径)。 */
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

    // -------------------------------------------------------------------
    // 内部:div 深度计数工具
    // -------------------------------------------------------------------

    /** `id` 属性命中的 div outerHtml(开/闭标签深度配对;未闭合返回 null)。 */
    private fun extractDivElementById(
        html: String,
        id: String,
    ): String? {
        val openTag =
            Regex("""<div\b[^>]*\bid\s*=\s*["']$id["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(html) ?: return null
        return balancedDiv(html, openTag.range.first)
    }

    /** 片段内第一个 class 含 [token](完整 class 词)的 div outerHtml。 */
    private fun firstDivElementWithClassToken(
        html: String,
        token: String,
    ): String? =
        Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .firstOrNull { openTag ->
                Regex("""\bclass\s*=\s*["']([^"']*)["']""")
                    .find(openTag.value)
                    ?.groupValues
                    ?.get(1)
                    ?.split(WHITESPACE)
                    ?.any { it == token } == true
            }?.let { balancedDiv(html, it.range.first) }

    /** [containerHtml](某 div 的 outerHtml)内部**直接子 div** 的 outerHtml 列表。 */
    private fun directChildDivs(containerHtml: String): List<String> {
        val children = mutableListOf<String>()
        val containerOpen = DIV_OPEN.find(containerHtml) ?: return children
        var cursor = containerOpen.range.last + 1
        var depth = 0
        var childStart = -1
        while (true) {
            val nextOpen = DIV_OPEN.find(containerHtml, cursor)
            val nextClose = DIV_CLOSE.find(containerHtml, cursor)
            if (nextClose == null) return children // 未闭合,返回已收集部分
            if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                if (depth == 0) childStart = nextOpen.range.first
                depth++
                cursor = nextOpen.range.last + 1
            } else {
                depth--
                if (depth == 0 && childStart >= 0) {
                    children += containerHtml.substring(childStart, nextClose.range.last + 1)
                    childStart = -1
                }
                if (depth < 0) return children // 容器自身的闭合标签
                cursor = nextClose.range.last + 1
            }
        }
    }

    /** 从 [openStart](div 开标签起点)配对到平衡闭标签的 outerHtml;未闭合返回 null。 */
    private fun balancedDiv(
        html: String,
        openStart: Int,
    ): String? {
        var depth = 0
        var cursor = openStart
        while (true) {
            val nextOpen = DIV_OPEN.find(html, cursor)
            val nextClose = DIV_CLOSE.find(html, cursor)
            if (nextClose == null) return null
            if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                depth++
                cursor = nextOpen.range.last + 1
            } else {
                depth--
                if (depth == 0) return html.substring(openStart, nextClose.range.last + 1)
                cursor = nextClose.range.last + 1
            }
        }
    }

    /** td 是否为「class 含 submission-score 且带 data-id」的提交记录格。 */
    private fun isSubmissionScoreCell(attrs: String): Boolean =
        Regex("""\bclass\s*=\s*["'][^"']*\bsubmission-score\b""").containsMatchIn(attrs) &&
            DATA_ID.containsMatchIn(attrs)

    private val DATA_ID = Regex("""\bdata-id\s*=\s*["'](\d+)["']""")

    private val WHITESPACE = Regex("""\s+""")

    /** 旧 `div.row > div` drop(1) 语义:取第二个直接子 div(下标 1)。 */
    private const val STATEMENT_CHILD_INDEX = 1
}
