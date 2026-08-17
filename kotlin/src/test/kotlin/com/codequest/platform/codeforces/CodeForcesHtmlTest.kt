package com.codequest.platform.codeforces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP24:CF 轻量 HTML 解析工具——题面 div 提取(嵌套深度计数)、
 * 登录态 userbox 检测、提交响应表格/错误 span、侧栏用户名。
 * 夹具为 CF 页面结构的简化切片(协议事实来自旧 Scala 实现的 jsoup 选择器)。
 */
class CodeForcesHtmlTest {
    private val statementHtml =
        """
        <html><body><div id="pageContent">
          <div class="problem-statement">
            <div class="header"><div class="title">A. Sum</div><div class="time-limit"><div class="property-title">time limit</div>1 s</div></div>
            <div class="ttypography"><div class="header-deep">Read numbers <b>a</b> and <b>b</b>.</div><p>Output sum.</p></div>
            <div class="sample-tests"><div class="section-title">Examples</div></div>
          </div>
        </div></body></html>
        """.trimIndent()

    @Test
    fun `extractDivElementByClass returns outer html of first matching div with nesting`() {
        val outer = CodeForcesHtml.extractDivElementByClass(statementHtml, "problem-statement")!!

        assertTrue(outer.startsWith("<div class=\"problem-statement\">"))
        assertTrue(outer.trimEnd().endsWith("</div>"))
        // 嵌套子 div(header / ttypography / sample-tests)完整包含,深度计数正确闭合
        assertTrue(outer.contains("<div class=\"ttypography\">"))
        assertTrue(outer.contains("Output sum."))
        // 不吞 statement 之外的兄弟节点
        assertFalse(outer.contains("pageContent"))
    }

    @Test
    fun `extractDivElementByClass matches class attribute containing word among multiple classes`() {
        val html = """<div class="round box sidebox  ttypography "><p>x</p></div>"""
        val outer = CodeForcesHtml.extractDivElementByClass(html, "ttypography")
        assertEquals(html, outer)
    }

    @Test
    fun `extractDivElementByClass returns null when absent or unterminated`() {
        assertNull(CodeForcesHtml.extractDivElementByClass("<div><p>none</p></div>", "problem-statement"))
        assertNull(CodeForcesHtml.extractDivElementByClass("<div class=\"problem-statement\"><p>未闭合", "problem-statement"))
    }

    @Test
    fun `hasDivElementWithClass detects userbox marker`() {
        assertTrue(CodeForcesHtml.hasDivElementWithClass("""<div class="userbox"><div class="info">handle</div></div>""", "userbox"))
        // 词边界:不能被别的 class 名前缀误命中
        assertFalse(CodeForcesHtml.hasDivElementWithClass("""<div class="userbox-legacy"></div>""", "userbox"))
        assertFalse(CodeForcesHtml.hasDivElementWithClass("""<span class="userbox"></span>""", "userbox"))
    }

    @Test
    fun `extractFirstSubmissionId reads first data-submission-id row`() {
        val html =
            """
            <table class="status frame"><thead></thead><tbody>
            <tr data-submission-id="291234567" style=""><td>291234567</td></tr>
            <tr data-submission-id="291234568"><td>291234568</td></tr>
            </tbody></table>
            """.trimIndent()

        assertEquals("291234567", CodeForcesHtml.extractFirstSubmissionId(html))
        assertNull(CodeForcesHtml.extractFirstSubmissionId("<table><tr><td>empty</td></tr></table>"))
    }

    @Test
    fun `extractSubmitError returns styled-out error span inside shiftUp only`() {
        val html =
            """
            <div class="shiftUp">
              <span class="error for__source" style="display: none;">hidden by style</span>
              <span class="error for__source">You have submitted exactly the same code before</span>
            </div>
            """.trimIndent()

        assertEquals("You have submitted exactly the same code before", CodeForcesHtml.extractSubmitError(html))
        // shiftUp 区域外不算(旧实现 div.shiftUp > span.error 事实)
        assertNull(CodeForcesHtml.extractSubmitError("""<span class="error">outside</span>"""))
        // 只有带 style 的 error span → 无错误
        assertNull(
            CodeForcesHtml.extractSubmitError(
                """<div class="shiftUp"><span class="error" style="display:none;">x</span></div>""",
            ),
        )
    }

    @Test
    fun `extractSidebarUsername takes second avatar link in personal sidebar`() {
        val html =
            """
            <div class="personal-sidebar">
              <div class="avatar"><a href="/profile/first"><img src="/a.png"/></a> <a href="/profile/tourist">tourist</a></div>
            </div>
            """.trimIndent()

        assertEquals("tourist", CodeForcesHtml.extractSidebarUsername(html))
        assertNull(CodeForcesHtml.extractSidebarUsername("<div class=\"personal-sidebar\"></div>"))
        assertNull(CodeForcesHtml.extractSidebarUsername("<div>no sidebar</div>"))
    }

    @Test
    fun `stripTags removes markup and decodes basic entities`() {
        assertEquals("a < b & c's", CodeForcesHtml.stripTags("a &lt; <b>b</b> &amp; c&#39;s&nbsp;").trim())
    }
}
