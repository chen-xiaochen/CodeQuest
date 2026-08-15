package com.codequest.spi

import com.codequest.core.model.PlatformId
import com.codequest.core.model.SubmissionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 提交能力(WP17):平台适配器按需实现。
 *
 * 推荐实现方式:submit 调平台 API 拿到远端提交 id 后包装为 [SubmissionHandle];
 * watchSubmission 委托给基于 [BaseSubmissionFlow] 的子类,只实现 fetchRemoteStatus 钩子,
 * 轮询/退避/超时全部复用状态机。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系
 * (超时抛 SubmissionTimeoutException,登录失效抛 AuthExpiredException)。
 */
interface SubmissionCapable {
    /**
     * 提交代码。成功返回平台侧提交句柄;平台拒绝(语言不支持/频率限制/反爬)时抛
     * OJException 子类。
     */
    suspend fun submit(request: SubmitRequest): SubmissionHandle

    /**
     * 轮询提交状态并包装为冷流:每次发射一个最新判定,到达终态后正常完成;
     * 超时/登录失效等以异常终止流(见 [BaseSubmissionFlow])。
     */
    fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult>
}

/** 提交请求(WP17 最小集)。 */
@Serializable
data class SubmitRequest(
    /** 平台侧题目标识(与 [ProblemSummary.remoteId] 同源)。 */
    val problemRemoteId: String,
    /** 平台侧提交语言标识(与 [LanguageInfo.id] 同源)。 */
    val languageId: String,
    /** 提交代码全文。 */
    val code: String,
)

/** 平台侧提交句柄:watchSubmission 与 fetchRemoteStatus 的寻址凭据。 */
@Serializable
data class SubmissionHandle(
    val platformId: PlatformId,
    /** 平台侧提交 id,如 CF submissionId、LC submissionId。 */
    val remoteSubmissionId: String,
)
