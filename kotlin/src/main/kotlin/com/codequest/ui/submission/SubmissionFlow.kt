package com.codequest.ui.submission

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.repository.SubmissionRepository
import com.codequest.spi.OJPlatform
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.util.UUID

/**
 * 提交流程编排(WP21,纯 Kotlin,可脱离 IntelliJ 平台测试)。
 *
 * 职责:经平台 SPI `submit → watchSubmission 收集至终态 → Submission 落库`
 * (含提交代码快照);AuthExpired 时经 [Hooks.relogin] 发起重登并**自动重试一次**,
 * 再失败(或重登被拒绝)异常原样向调用方传播,由 UI 层通知报错。
 *
 * UI 层(SubmissionCoordinator)负责:线程调度(网络/落库在后台协程)、
 * 通知与桥推送;本类不感知 IDE。
 */
class SubmissionFlow(
    private val submissionRepository: SubmissionRepository,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Instant = Instant::now,
) {
    /** 流程各阶段的 UI 回调(均有默认空实现,测试按需覆写)。 */
    interface Hooks {
        /** watchSubmission 每次发射的判定状态(提交中 loading 反馈)。 */
        fun onState(result: SubmissionResult) {}

        /**
         * 收到 AuthExpiredException 时的重登钩子:返回 true 表示已恢复登录
         * (如 UI 弹 cookie 粘贴对话框且 ensureLoggedIn 校验通过),流程将重试一次。
         */
        suspend fun relogin(): Boolean = false

        /** 终态判定已落库。 */
        fun onSaved(submission: Submission) {}
    }

    suspend fun submit(
        platform: OJPlatform,
        capable: SubmissionCapable,
        request: SubmitRequest,
        challengeId: ChallengeId,
        languageLabel: String,
        hooks: Hooks,
    ): Submission =
        try {
            submitOnce(platform, capable, request, challengeId, languageLabel, hooks)
        } catch (error: AuthExpiredException) {
            if (!hooks.relogin()) throw error
            submitOnce(platform, capable, request, challengeId, languageLabel, hooks)
        }

    private suspend fun submitOnce(
        platform: OJPlatform,
        capable: SubmissionCapable,
        request: SubmitRequest,
        challengeId: ChallengeId,
        languageLabel: String,
        hooks: Hooks,
    ): Submission {
        val handle = capable.submit(request)
        var last: SubmissionResult? = null
        capable.watchSubmission(handle).collect { result ->
            last = result
            hooks.onState(result)
        }
        val verdict =
            checkNotNull(last) { "watchSubmission 流完成但未发射任何判定状态: ${handle.remoteSubmissionId}" }
        val submission =
            Submission(
                id = SubmissionId(ids()),
                challengeId = challengeId,
                platformId = platform.id,
                remoteSubmissionId = handle.remoteSubmissionId,
                language = languageLabel,
                codeSnapshot = request.code,
                verdict = verdict,
                timeMs = null,
                memoryKb = null,
                submittedAt = clock(),
            )
        submissionRepository.save(submission)
        hooks.onSaved(submission)
        return submission
    }
}
