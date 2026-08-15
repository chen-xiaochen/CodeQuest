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
            buildString {
                append("{\"operationName\":\"").append(operationName).append('"')
                append(",\"query\":\"").append(query).append('"')
                append(",\"variables\":").append(variables ?: "{}")
                append('}')
            },
            headers,
        )

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
