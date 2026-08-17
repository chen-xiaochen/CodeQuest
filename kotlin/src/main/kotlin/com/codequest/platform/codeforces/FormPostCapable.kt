package com.codequest.platform.codeforces

import com.codequest.core.model.PlatformId

/**
 * 表单 POST 传输能力(WP24):`application/x-www-form-urlencoded` 请求的最小契约。
 *
 * ## 为什么在 platform 包定义(SPI 缺口显式化)
 *
 * CodeForces 的提交与轮询端点只接受表单编码(旧 Scala 实现的 `UrlForm` 事实),
 * 而 `OJTransport` 最小集(WP19)只有 `get / postJson / postGraphQL`,
 * `postJson` 在 infra 实现中固定 `Content-Type: application/json`——CF 的 PHP
 * 服务端不会解析 JSON 请求体里的表单字段。本 WP 权限不动 spi/infra 既有文件,
 * 故以「传输实现可选实现的本接口」暴露缺口:
 *
 * - 生产传输(infra 的 HttpOJTransport)实现本接口 + override `postForm` 一行,
 *   CF 提交链路即全量可用(建议后续 WP 把 `postForm` 提升进 `OJTransport`);
 * - 未实现时,CF 提交/轮询抛带接入指引的 [com.codequest.core.model.OJException],
 *   而非发出必然失败的请求(诚实失败优于静默坏请求);
 * - 测试注入假实现即可全链路验证,不上网络。
 */
interface FormPostCapable {
    /**
     * POST 表单(字段原文由调用方给出,URL 编码与 `application/x-www-form-urlencoded`
     * Content-Type 由实现负责),返回响应体文本;非 2xx 抛领域异常(同 OJTransport 语义)。
     */
    suspend fun postForm(
        platformId: PlatformId,
        url: String,
        fields: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): String
}
