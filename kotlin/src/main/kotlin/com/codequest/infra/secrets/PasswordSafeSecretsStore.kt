package com.codequest.infra.secrets

import com.codequest.core.model.PlatformId
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager

/**
 * 基于 IntelliJ PasswordSafe 的凭据存储(WP18):插件内**默认实现**,
 * 与旧版 CodeEpiphany `SensitiveDataStore` 同机制(方案选型见类尾说明)。
 *
 * 2026.2 平台兼容说明:`PasswordSafe.getInstance()` 与 `generateServiceName`
 * 已被平台标注 `@Deprecated(HIDDEN)`(Kotlin 侧不可见,Java 静态调用仍在),
 * 故此处改用 `ApplicationManager.getApplication().getService(PasswordSafe::class.java)`
 * 与手写 serviceName,语义等价(PasswordSafe 本身仍是 application service)。
 *
 * 需要运行中的 IntelliJ 平台(OS 钥匙串 / 加密 KeePass 兜底),不做纯 JVM 单测;
 * 逻辑为薄委托,roundtrip 语义由 [EncryptedFileSecretsStore] 的同接口单测保证。
 */
class PasswordSafeSecretsStore(
    private val serviceName: String = SERVICE_NAME,
) : OJSecretsStore {
    private fun passwordSafe(): PasswordSafe? = ApplicationManager.getApplication()?.getService(PasswordSafe::class.java)

    override fun save(
        platformId: PlatformId,
        secret: String,
    ) {
        val safe = passwordSafe() ?: error("PasswordSafe unavailable: no running IntelliJ application")
        safe.set(attributes(platformId), Credentials(null, secret))
    }

    override fun load(platformId: PlatformId): String? = passwordSafe()?.get(attributes(platformId))?.password?.toString()

    override fun remove(platformId: PlatformId) {
        // 置 null 即删除该凭据(旧版 SensitiveDataStore 同语义)
        passwordSafe()?.set(attributes(platformId), null)
    }

    private fun attributes(platformId: PlatformId): CredentialAttributes = CredentialAttributes("$serviceName — ${keyOf(platformId)}")

    companion object {
        /** 服务名前缀,与插件 id 对齐;完整 serviceName 形如 "CodeQuest — oj.leetcode"。 */
        private const val SERVICE_NAME = "CodeQuest"

        internal fun keyOf(platformId: PlatformId): String = "oj.${platformId.value}"
    }
}
