package com.codequest.platform.leetcode

import com.codequest.core.model.PlatformId
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJTransport

/** 平台层单测共用夹具(WP19):假 transport + 内存凭据存储,全程不上网络。 */

/** 记录型假 [OJTransport]:handler 按请求返回脚本化响应体。 */
class FakeOJTransport : OJTransport {
    data class Recorded(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
    )

    private val _recorded = mutableListOf<Recorded>()
    val recorded: List<Recorded>
        get() = _recorded

    val cookieJars = mutableMapOf<PlatformId, MutableMap<String, String>>()
    val clearedPlatforms = mutableSetOf<PlatformId>()

    var handler: suspend (Recorded) -> String = { "{}" }

    private suspend fun perform(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): String {
        val request = Recorded(method, url, body, headers)
        _recorded += request
        return handler(request)
    }

    override suspend fun get(
        platformId: PlatformId,
        url: String,
        headers: Map<String, String>,
    ): String = perform("GET", url, null, headers)

    override suspend fun postJson(
        platformId: PlatformId,
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): String = perform("POST", url, jsonBody, headers)

    override suspend fun postGraphQL(
        platformId: PlatformId,
        url: String,
        query: String,
        variables: String?,
        operationName: String?,
        headers: Map<String, String>,
    ): String =
        perform(
            "POST_GRAPHQL", url,
            // 合法 JSON 信封(kotlinx.serialization 转义):WP42 起查询文本含引号
            // (favoriteQuestionList 的 `$version: String = "v2"` 默认值),手拼字符串
            // 会产生非法 JSON,按 key 消费 body 的 handler 将解析失败。
            kotlinx.serialization.json.buildJsonObject {
                put("operationName", kotlinx.serialization.json.JsonPrimitive(operationName))
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put(
                    "variables",
                    variables?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
                        ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                )
            }.toString(),
            headers,
        )

    /** postForm 的记录形态(WP25:表单字段为 Map,插入序即提交序)。 */
    data class FormRecorded(
        val url: String,
        val form: Map<String, String>,
        val headers: Map<String, String>,
    )

    val forms = mutableListOf<FormRecorded>()

    /** 表单 POST 响应脚本(与 [handler] 独立分发);默认返回占位体。 */
    var formHandler: suspend (FormRecorded) -> String = { "{}" }

    override suspend fun postForm(
        platformId: PlatformId,
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        val request = FormRecorded(url, form, headers)
        forms += request
        return formHandler(request)
    }

    override suspend fun updateCookies(
        platformId: PlatformId,
        url: String,
        cookies: Map<String, String>,
    ) {
        cookieJars.getOrPut(platformId) { mutableMapOf() }.putAll(cookies)
    }

    override suspend fun cookies(
        platformId: PlatformId,
        url: String,
    ): Map<String, String> = cookieJars[platformId] ?: emptyMap()

    override suspend fun clearCookies(platformId: PlatformId) {
        cookieJars.remove(platformId)
        clearedPlatforms += platformId
    }
}

/** 内存 [OJCredentialStore],断言 save/remove 行为用。 */
class InMemoryCredentialStore : OJCredentialStore {
    val map = mutableMapOf<PlatformId, String>()

    override fun save(
        platformId: PlatformId,
        secret: String,
    ) {
        map[platformId] = secret
    }

    override fun load(platformId: PlatformId): String? = map[platformId]

    override fun remove(platformId: PlatformId) {
        map.remove(platformId)
    }
}
