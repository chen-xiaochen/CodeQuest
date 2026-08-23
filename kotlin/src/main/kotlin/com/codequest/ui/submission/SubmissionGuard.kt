package com.codequest.ui.submission

import java.util.concurrent.ConcurrentHashMap

/**
 * 提交中防重复触发门(WP21,纯 Kotlin):同一文件提交进行中时拒绝再次触发
 * (action 幂等);提交结束(成功/失败/取消)后 [end] 释放,可再次提交。
 */
class SubmissionGuard {
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 尝试占用 [key];返回 false 表示该 key 已有提交进行中。 */
    fun begin(key: String): Boolean = inFlight.add(key)

    /** 释放 [key](幂等;未知 key 安全)。 */
    fun end(key: String) {
        inFlight.remove(key)
    }

    /** [key] 是否仍在进行中(WP55:云端测试 action.update 置灰查询)。 */
    fun isInFlight(key: String): Boolean = key in inFlight
}
