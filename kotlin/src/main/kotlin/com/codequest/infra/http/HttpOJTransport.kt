package com.codequest.infra.http

import com.codequest.core.model.PlatformId
import com.codequest.spi.OJTransport
import com.codequest.spi.OJTransportProvider

/**
 * [OJTransport] 的生产实现(WP19):薄委托 [OJHttpClient]。
 *
 * spi 的传输接口由 infra 实现——依赖方向 infra → spi 合法(分层规则只禁 infra → ui),
 * 平台适配器经 spi 注入本接口,全程不感知 infra 类型。
 * 经 plugin.xml `<ojTransportProvider implementation="..."/>` 注册。
 */
class HttpOJTransport(
    private val client: OJHttpClient = OJHttpClient.shared(),
) : OJTransport {
    override suspend fun get(
        platformId: PlatformId,
        url: String,
        headers: Map<String, String>,
    ): String = client.get(platformId, url, headers)

    override suspend fun postJson(
        platformId: PlatformId,
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): String = client.postJson(platformId, url, jsonBody, headers)

    override suspend fun postGraphQL(
        platformId: PlatformId,
        url: String,
        query: String,
        variables: String?,
        operationName: String?,
        headers: Map<String, String>,
    ): String = client.postGraphQL(platformId, url, query, variables, operationName, headers)

    override suspend fun postForm(
        platformId: PlatformId,
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): String = client.postForm(platformId, url, form, headers)

    override suspend fun updateCookies(
        platformId: PlatformId,
        url: String,
        cookies: Map<String, String>,
    ): Unit = client.updateCookies(platformId, url, cookies)

    override suspend fun cookies(
        platformId: PlatformId,
        url: String,
    ): Map<String, String> = client.cookiesFor(platformId, url).associate { it.name to it.value }

    override suspend fun clearCookies(platformId: PlatformId) {
        client.clearCookies(platformId)
    }
}

/** EP `com.codequest.ojTransportProvider` 声明类(见 plugin.xml)。 */
class HttpOJTransportProvider : OJTransportProvider {
    override fun create(): OJTransport = HttpOJTransport()
}
