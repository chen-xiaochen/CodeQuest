package com.codequest.ui.contest

import com.codequest.core.ContestReminderService
import com.codequest.core.model.ContestReminder
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.i18n.PluginBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

/**
 * 竞赛提醒调度器(WP23,项目级服务):周期调用 [ContestReminderService.dispatchDueReminders]
 * (findDue → 通知 → markNotified 去重,语义见该服务 KDoc),通知经
 * `codequest.contests` NotificationGroup 气球展示。
 *
 * 形态:daemon 轮询线程(Alarm 的 POOLED 变体在 2026.2 已不可用);轮询只在
 * 后台线程,通知 API 线程安全。单轮失败(通知异常/库不可用)仅记日志,下一轮
 * (默认 15 分钟)findDue 仍会命中未 markNotified 的提醒,自愈重试。
 */
@Service(Level.PROJECT)
class ContestReminderScheduler(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getLogger(ContestReminderScheduler::class.java.name)

    @Volatile
    private var started = false

    private val worker =
        Thread(::loop, THREAD_NAME).apply {
            isDaemon = true
        }

    /** 启动周期调度(幂等);由 [ContestReminderSchedulerStartupActivity] 在项目打开后触发。 */
    @Synchronized
    fun start() {
        if (started || project.isDisposed) return
        started = true
        worker.start()
    }

    private fun loop() {
        try {
            Thread.sleep(FIRST_DISPATCH_DELAY_MS)
            while (!project.isDisposed && !Thread.currentThread().isInterrupted) {
                dispatchOnce()
                Thread.sleep(DISPATCH_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun dispatchOnce() {
        try {
            val repository = project.getService(DatabaseManager::class.java).contestReminderRepository
            runBlocking { ContestReminderService(repository).dispatchDueReminders(::notifyReminder) }
        } catch (error: Exception) {
            logger.warning("Contest reminder dispatch failed, will retry next round: $error")
        }
    }

    private fun notifyReminder(reminder: ContestReminder) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                PluginBundle.message("contest.notification.title"),
                PluginBundle.message("contest.notification.due", reminder.contestId.value),
                NotificationType.INFORMATION,
            ).notify(project)
    }

    override fun dispose() {
        worker.interrupt()
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "codequest.contests"

        private const val THREAD_NAME = "cq-contest-reminders"

        /** 调度周期:15 分钟(提醒粒度分钟级足够,远小于默认 24h 提前量)。 */
        private const val DISPATCH_INTERVAL_MS = 15L * 60 * 1000

        /** 首轮延迟:项目打开后稍等,避开启动高峰。 */
        private const val FIRST_DISPATCH_DELAY_MS = 60_000L
    }
}

/** 项目启动后活动:touch 一次调度服务(懒加载服务需显式实例化)。 */
class ContestReminderSchedulerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(ContestReminderScheduler::class.java).start()
    }
}
