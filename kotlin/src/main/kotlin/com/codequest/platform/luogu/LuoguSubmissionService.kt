package com.codequest.platform.luogu

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.BaseSubmissionFlow
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * 洛谷提交(WP26):SubmissionCapable 实现。
 *
 * **适配器自身不写轮询循环**(SPI 验证点):流程为
 * 1. `submit`:语言 id 校验(数字,洛谷 `lang`)→ JSON POST `/fe/api/problem/submit/{pid}`
 *    → `rid` 包装为 [SubmissionHandle];
 * 2. `watchSubmission`:委托基于 [BaseSubmissionFlow] 的内部状态机,只实现
 *    fetchRemoteStatus 钩子(单次 GET record 页,`decodeURIComponent` JSON 判定),
 *    退避/封顶/超时全部复用 spi 状态机;轮询间隔 2s 固定(旧实现 sleep 2s 事实,
 *    PollConfig 封顶同为 2s);record 页 503 暂态重试在 [LuoguApi] 内部。
 *
 * 403(易盾/图片验证码)经 transport 错误映射为 AuthExpiredException 上抛——
 * 旧实现的 JCEF 验证码弹窗依赖未迁移的旧 webview 静态资源,本 WP 如实兜底,
 * 用户可在浏览器完成验证后重试。
 */
class LuoguSubmissionService(
    private val api: LuoguApi,
    pollConfig: BaseSubmissionFlow.PollConfig =
        BaseSubmissionFlow.PollConfig(
            initialIntervalMs = POLL_INTERVAL_MS,
            maxIntervalMs = POLL_INTERVAL_MS,
            backoffMultiplier = FIXED_BACKOFF,
        ),
    delayFn: suspend (Long) -> Unit = { ms -> delay(ms) },
) : SubmissionCapable {
    private val flow =
        object : BaseSubmissionFlow(pollConfig, delayFn) {
            override suspend fun fetchRemoteStatus(handle: SubmissionHandle) = api.fetchRecordStatus(handle.remoteSubmissionId)
        }

    override suspend fun submit(request: SubmitRequest): SubmissionHandle {
        if (LuoguLanguages.find(request.languageId) == null) {
            throw OJException("洛谷不支持的提交语言 id: ${request.languageId}")
        }
        val recordId = api.submitCode(pid = request.problemRemoteId, langId = request.languageId, code = request.code)
        return SubmissionHandle(platformId = LUOGU_PLATFORM_ID, remoteSubmissionId = recordId)
    }

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = flow.watchSubmission(handle)

    /** 快照流(供落库等需要完整快照的调用方,对应 BaseSubmissionFlow.watchSnapshots)。 */
    fun watchSnapshots(handle: SubmissionHandle) = flow.watchSnapshots(handle)

    companion object {
        /** 旧实现轮询固定 2s 间隔(Temporal.sleep(2.second))。 */
        const val POLL_INTERVAL_MS: Long = 2_000L

        private const val FIXED_BACKOFF = 1.0
    }
}
