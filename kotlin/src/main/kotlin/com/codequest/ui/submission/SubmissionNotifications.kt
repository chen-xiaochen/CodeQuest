package com.codequest.ui.submission

import com.codequest.core.model.SubmissionResult
import com.codequest.infra.i18n.PluginBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import kotlin.math.roundToInt

/**
 * 提交相关 IntelliJ 通知(WP21):AC 绿色(ERROR/INFORMATION 语义由 [NotificationType] 承载,
 * 气泡图标绿/红)、错误红色;文案走 PluginBundle(中英)。
 *
 * 通知组 `codequest.submissions` 在 plugin.xml 声明(displayType=BALLOON)。
 */
object SubmissionNotifications {
    const val GROUP_ID = "codequest.submissions"

    /** 提交开始的进行中气泡;返回引用供终态时 [Notification.expire] 收掉。 */
    fun notifySubmitting(
        project: Project?,
        challengeName: String,
    ): Notification = notify(project, PluginBundle.message("submission.progress.submitting", challengeName), NotificationType.INFORMATION)

    /** 云端测试进行中气泡(WP55);返回引用供终态时收掉。 */
    fun notifyTesting(
        project: Project?,
        challengeName: String,
    ): Notification = notify(project, PluginBundle.message("testRun.progress.running", challengeName), NotificationType.INFORMATION)

    /** 终态通知:AC 成功(绿),其余失败(红),含题目名与 verdict 文案。 */
    fun notifyCompleted(
        project: Project?,
        challengeName: String,
        verdict: SubmissionResult,
    ) {
        val content =
            if (verdict.isSuccessful) {
                PluginBundle.message("submission.notification.accepted", challengeName)
            } else {
                PluginBundle.message("submission.notification.failed", challengeName, verdictText(verdict))
            }
        notify(project, content, if (verdict.isSuccessful) NotificationType.INFORMATION else NotificationType.ERROR)
    }

    fun notifyError(
        project: Project?,
        message: String,
    ): Notification = notify(project, message, NotificationType.ERROR)

    /** verdict → 本地化文案([SubmissionResult.Unknown]/[PartialScore] 保留平台原文/得分)。 */
    fun verdictText(verdict: SubmissionResult): String =
        when (verdict) {
            SubmissionResult.Accepted -> {
                PluginBundle.message("submission.verdict.accepted")
            }

            SubmissionResult.WrongAnswer -> {
                PluginBundle.message("submission.verdict.wrongAnswer")
            }

            SubmissionResult.TimeLimitExceeded -> {
                PluginBundle.message("submission.verdict.timeLimitExceeded")
            }

            SubmissionResult.MemoryLimitExceeded -> {
                PluginBundle.message("submission.verdict.memoryLimitExceeded")
            }

            SubmissionResult.OutputLimitExceeded -> {
                PluginBundle.message("submission.verdict.outputLimitExceeded")
            }

            SubmissionResult.RuntimeError -> {
                PluginBundle.message("submission.verdict.runtimeError")
            }

            SubmissionResult.CompileError -> {
                PluginBundle.message("submission.verdict.compileError")
            }

            is SubmissionResult.PartialScore -> {
                PluginBundle.message(
                    "submission.verdict.partialScore",
                    (verdict.score * PERCENT).roundToInt(),
                )
            }

            is SubmissionResult.Unknown -> {
                PluginBundle.message("submission.verdict.unknown", verdict.raw)
            }

            SubmissionResult.Pending -> {
                PluginBundle.message("submission.verdict.pending")
            }

            SubmissionResult.Judging -> {
                PluginBundle.message("submission.verdict.judging")
            }
        }

    private fun notify(
        project: Project?,
        content: String,
        type: NotificationType,
    ): Notification =
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(PluginBundle.message("submission.notification.title"), content, type)
            .apply { notify(project) }

    private const val PERCENT = 100.0
}
