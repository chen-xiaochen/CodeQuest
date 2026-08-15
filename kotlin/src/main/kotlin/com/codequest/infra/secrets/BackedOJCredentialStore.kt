package com.codequest.infra.secrets

import com.codequest.core.model.PlatformId
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJCredentialStoreProvider

/**
 * [OJCredentialStore] 的生产实现(WP19):薄委托 [PasswordSafeSecretsStore]
 * (IntelliJ PasswordSafe,OS 钥匙串级加密)。
 *
 * spi 接口 / infra 实现的分层理由见 spi.OJTransport KDoc;
 * 经 plugin.xml `<ojCredentialStoreProvider implementation="..."/>` 注册。
 */
class SecretsStoreCredentialStore(
    private val store: OJSecretsStore = PasswordSafeSecretsStore(),
) : OJCredentialStore {
    override fun save(
        platformId: PlatformId,
        secret: String,
    ): Unit = store.save(platformId, secret)

    override fun load(platformId: PlatformId): String? = store.load(platformId)

    override fun remove(platformId: PlatformId) {
        store.remove(platformId)
    }
}

/** EP `com.codequest.ojCredentialStoreProvider` 声明类(见 plugin.xml)。 */
class SecretsStoreCredentialStoreProvider : OJCredentialStoreProvider {
    override fun create(): OJCredentialStore = SecretsStoreCredentialStore()
}
