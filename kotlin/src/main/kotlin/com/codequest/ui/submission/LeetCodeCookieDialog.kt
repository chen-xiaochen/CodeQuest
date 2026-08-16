package com.codequest.ui.submission

import com.codequest.infra.i18n.PluginBundle
import com.codequest.platform.leetcode.LeetCodeAuth
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * LeetCode cookie 粘贴对话框(WP21):浏览器登录后复制 Cookie 请求头粘贴进来,
 * 经 [com.codequest.platform.leetcode.LeetCodeAuth] 注入并校验。
 *
 * [UiCookiePrompt] 把它桥接为 [LeetCodeAuth.CookiePrompt](suspend 回调 → EDT 模态对话框),
 * 由 SubmissionPlatforms 组装进平台实例——注册表实例无法回填 prompt,故提交链路
 * 使用的平台实例统一经 [SubmissionPlatforms] 组装。
 */
class LeetCodeCookieDialog(
    project: Project,
    private val domain: String,
) : DialogWrapper(project) {
    private companion object {
        const val COOKIE_AREA_ROWS = 6
    }

    private val cookieArea =
        JBTextArea().apply {
            rows = COOKIE_AREA_ROWS
            lineWrap = true
        }

    init {
        init()
        title = PluginBundle.message("cookieDialog.title", domain)
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                cell(JLabel(PluginBundle.message("cookieDialog.description", domain)))
            }
            row(PluginBundle.message("cookieDialog.cookiesLabel")) {
                cell(cookieArea).resizableColumn().align(Align.FILL)
            }.resizableRow()
        }

    override fun getPreferredFocusedComponent(): JComponent = cookieArea

    override fun doValidate(): ValidationInfo? =
        if (cookieArea.text.isNullOrBlank()) {
            ValidationInfo(PluginBundle.message("submission.error.cookieRequired"), cookieArea)
        } else {
            null
        }

    /** 模态显示;确认返回粘贴内容(trim),取消返回 null。 */
    fun askCookies(): String? = if (showAndGet()) cookieArea.text.trim() else null
}

/**
 * [LeetCodeAuth.CookiePrompt] 的真实 UI 实现(WP21):在 EDT 弹出 [LeetCodeCookieDialog],
 * 阻塞当前后台协程直到用户确认/取消——提交流程整体在 pooled 线程,EDT 不被占用
 * (模态对话框自身的事件泵正常运转)。
 */
class UiCookiePrompt(
    private val project: Project,
    private val domain: String,
) : LeetCodeAuth.CookiePrompt {
    override suspend fun requestCookies(): String? {
        var cookies: String? = null
        if (SwingUtilities.isEventDispatchThread()) {
            // 防御:绝不应发生(提交流程在后台线程),真发生时仍弹窗但无法并发
            cookies = LeetCodeCookieDialog(project, domain).askCookies()
        } else {
            ApplicationManager.getApplication().invokeAndWait {
                cookies = LeetCodeCookieDialog(project, domain).askCookies()
            }
        }
        return cookies
    }
}
