package com.codequest.platform.atcoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP25:AtCoderHtml——settings 登录态/用户名、题面提取、提交错误/
 * 提交 id/judge-status 判定的 HTML 切片解析(单测夹具固化页面结构,不上网络)。
 */
class AtCoderHtmlTest {
    // -------------------------------------------------------------------
    // settings 页(登录态 / 用户名)
    // -------------------------------------------------------------------

    private val loggedInSettings =
        """
        <html><div id="main-container">
          <ul class="nav nav-tabs" id="user-nav-tabs"><li>Account</li></ul>
          <form>
            <input class="form-control" id="ui.UserName" name="ui.UserName" type="text" value="tourist">
            <input class="form-control" id="ui.Password" type="password">
          </form>
        </div></html>
        """.trimIndent()

    private val loggedOutSettings =
        """
        <html><div id="main-container">
          <div class="alert">Please log in</div>
        </div></html>
        """.trimIndent()

    @Test
    fun `user-nav-tabs presence marks logged in`() {
        assertTrue(AtCoderHtml.hasUserNavTabs(loggedInSettings))
        assertFalse(AtCoderHtml.hasUserNavTabs(loggedOutSettings))
    }

    @Test
    fun `username extracted from ui dot UserName input value`() {
        assertEquals("tourist", AtCoderHtml.extractUserName(loggedInSettings))
        // 属性顺序无关:value 在 id 之前同样命中
        assertEquals(
            "chokudai",
            AtCoderHtml.extractUserName("""<input value="chokudai" id="ui.UserName" type="text">"""),
        )
        assertNull(AtCoderHtml.extractUserName(loggedOutSettings))
        // value 空白视为缺失(调用方兜底 unknown)
        assertNull(AtCoderHtml.extractUserName("""<input id="ui.UserName" value=" " />"""))
    }

    // -------------------------------------------------------------------
    // 题面页(div#main-container div.row > div 的第二个直接子 div)
    // -------------------------------------------------------------------

    private val taskPageHtml =
        """
        <html><div id="main-container">
          <div class="header">AtCoder Beginner Contest 100</div>
          <div class="row">
            <div class="col-sm-2 nav">
              <nav><a href="/contests/abc100/tasks/abc100_a">A</a></nav>
              <div class="nested-in-sidebar"><span>x</span></div>
            </div>
            <div class="col-sm-10">
              <span class="h2">A. Happy Birthday!</span>
              <div class="problem">You are given an integer.</div>
              <h3>入力例 1</h3><pre>3
        1 2 3</pre>
            </div>
          </div>
        </div></html>
        """.trimIndent()

    @Test
    fun `statement is second direct child div of first row in main container`() {
        val statement = AtCoderHtml.extractStatement(taskPageHtml)
        assertTrue(statement != null, "应提取到题面")
        assertTrue(statement!!.startsWith("""<div class="col-sm-10">"""), "外层应为第二个子 div: $statement")
        assertTrue(statement.contains("You are given an integer."))
        assertTrue(statement.contains("<pre>"))
        // 不含第一个子 div(题目侧栏导航)
        assertFalse(statement.contains("col-sm-2"))
        assertTrue(statement.endsWith("</div>"))
    }

    @Test
    fun `statement missing when structure deviates`() {
        assertNull(AtCoderHtml.extractStatement("""<html><div class="no-main-container"></div></html>"""))
        // row 只有侧栏一个子 div(题面缺位)
        assertNull(
            AtCoderHtml.extractStatement(
                """<div id="main-container"><div class="row"><div class="col-sm-2">nav</div></div></div>""",
            ),
        )
        // 未闭合 div 视为结构异常
        assertNull(
            AtCoderHtml.extractStatement(
                """<div id="main-container"><div class="row"><div>1</div><div>2""",
            ),
        )
    }

    // -------------------------------------------------------------------
    // 提交响应(错误 alert / 提交 id)
    // -------------------------------------------------------------------

    @Test
    fun `submit error text from first role alert div`() {
        val html =
            """
            <div role="alert" class="alert alert-danger alert-dismissible">
              You have already submitted exactly the same code.
            </div>
            """.trimIndent()
        assertEquals("You have already submitted exactly the same code.", AtCoderHtml.extractSubmitError(html))
        assertNull(AtCoderHtml.extractSubmitError("""<div class="ok"></div>"""))
    }

    @Test
    fun `submission id from first submission-score td with data-id`() {
        val html =
            """
            <table><tbody>
              <tr><td class="text-center no-break">2025-01-01</td>
                  <td class="text-center no-break submission-score" data-id="65432101">100</td></tr>
              <tr><td class="text-center no-break">2025-01-02</td>
                  <td class="submission-score" data-id="65432102">0</td></tr>
            </tbody></table>
            """.trimIndent()
        assertEquals("65432101", AtCoderHtml.extractSubmissionId(html))
        // data-id 在 class 之前同样命中(属性顺序无关)
        assertEquals(
            "42",
            AtCoderHtml.extractSubmissionId("""<td data-id="42" class="submission-score">0</td>"""),
        )
        assertNull(AtCoderHtml.extractSubmissionId("""<table></table>"""))
    }

    // -------------------------------------------------------------------
    // 提交详情页(judge-status)
    // -------------------------------------------------------------------

    @Test
    fun `judge status reads title attr and span text`() {
        val html =
            """
            <table><tbody><tr>
              <td class="text-center no-break"><span class="label">2025-01-01</span></td>
              <td id="judge-status" class="text-center no-break"><span title="Wrong Answer" class="label label-danger">WA</span></td>
            </tr></tbody></table>
            """.trimIndent()
        val status = AtCoderHtml.extractJudgeStatus(html)!!
        assertEquals("Wrong Answer", status.title)
        assertEquals("WA", status.label)
    }

    @Test
    fun `judge status missing returns null`() {
        assertNull(AtCoderHtml.extractJudgeStatus("""<table><tr><td>nothing</td></tr></table>"""))
    }

    @Test
    fun `stripTags decodes basic entities`() {
        assertEquals("""a < b & "c" 'd'""", AtCoderHtml.stripTags("""<b>a &lt; b &amp; &quot;c&quot; &apos;d&apos;</b>"""))
    }
}
