package com.codequest.platform.luogu

/**
 * 洛谷轻量 HTML/信封解析(WP26):只覆盖适配器需要的提取点,
 * 对应旧 Scala 实现的 jsoup 选择器 / 正则事实——
 *
 * | 提取点              | 旧选择器 / 正则                                        | 本实现 |
 * |---------------------|--------------------------------------------------------|--------|
 * | 页面数据 JSON       | `script#lentille-context[type="application/json"]` html | [extractLentilleContext] |
 * | CSRF token          | `meta[name=csrf-token]` content                        | [extractCsrfToken] |
 * | 反爬标记 C3VK       | 正则 `C3VK=(\w+);`                                     | [extractC3vk] |
 * | 登录态 title        | `title` 文本                                           | [extractTitle] |
 * | 未登录 401 标题     | `#app > h1` 文本含 401                                 | [contains401Heading] |
 * | 提交结果 JSON       | 正则 `decodeURIComponent\("(.*)"\)`(贪婪)             | [extractEncodedRecordJson] |
 *
 * 不引入 jsoup 依赖(新构建依赖白名单不动):页面切片由单测夹具固化;
 * HTML 结构变化时解析返回 null,由调用方翻译为 OJException,不会崩溃。
 */
internal object LuoguHtml {
    private val SCRIPT_OPEN = Regex("""<script\b[^>]*\bid\s*=\s*["']lentille-context["'][^>]*>""", RegexOption.IGNORE_CASE)

    private val SCRIPT_CLOSE = Regex("""</script\s*>""", RegexOption.IGNORE_CASE)

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** `<title>` 文本(登录态判定输入;缺失返回 null)。 */
    fun extractTitle(html: String): String? =
        Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * `meta[name=csrf-token]` 的 content(旧 getCSRFTokenAndPassAntiCrawler 事实,
     * 值形如 `1787113333:O8QOVr+QEB+...`,可含 `+`/`=`/`:`);缺失返回 null。
     */
    fun extractCsrfToken(html: String): String? =
        META_TAG
            .findAll(html)
            .map { it.value }
            .firstOrNull { tag -> Regex("""\bname\s*=\s*["']csrf-token["']""").containsMatchIn(tag) }
            ?.let { tag ->
                Regex("""\bcontent\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)
            }?.takeIf { it.isNotBlank() }

    /** 响应体中的反爬标记 `C3VK=(\w+);`(命中则需注入同名 cookie 后重试);无则 null。 */
    fun extractC3vk(html: String): String? = Regex("""C3VK=(\w+);""").find(html)?.groupValues?.get(1)

    /** `script#lentille-context` 内嵌 JSON 原文;页面无该 script 返回 null。 */
    fun extractLentilleContext(html: String): String? {
        val open = SCRIPT_OPEN.find(html) ?: return null
        val close = SCRIPT_CLOSE.find(html, open.range.last + 1) ?: return null
        return html.substring(open.range.last + 1, close.range.first).takeIf { it.isNotBlank() }
    }

    /**
     * 首个 `<h1>` 文本是否含 "401"(旧 checkLogin 的 `#app > h1` 判定;
     * 洛谷未登录页以大标题提示 401,夹具固化该结构)。
     */
    fun contains401Heading(html: String): Boolean =
        Regex("""<h1\b[^>]*>(.*?)</h1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { it.groupValues[1] }
            .any { it.contains("401") }

    /**
     * 提交结果页 `decodeURIComponent("...")` 内的 URL 编码 JSON(旧正则
     * `(?s:.*decodeURIComponent\("(.*)"\).*)` 为贪婪匹配,多段时取末段,此处保持);
     * 未命中返回 null,由调用方抛 OJException。
     */
    fun extractEncodedRecordJson(html: String): String? =
        Regex("""decodeURIComponent\("(.*)"\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
}
