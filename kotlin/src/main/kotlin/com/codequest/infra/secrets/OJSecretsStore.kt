package com.codequest.infra.secrets

import com.codequest.core.model.PlatformId

/**
 * OJ 平台凭据持久化接口(WP18):存储登录 cookie 串或 token,按平台隔离。
 *
 * 语义沿用旧版 CodeEpiphany 的 `SensitiveDataStore`(key → secret 的
 * save/load/remove),差异只在 key 粒度固定为 `PlatformId`。
 *
 * 实现二选一:
 * - [PasswordSafeSecretsStore]:插件内默认,OS 钥匙串级加密(IntelliJ PasswordSafe);
 * - [EncryptedFileSecretsStore]:AES-GCM 加密文件,不依赖 IntelliJ 运行时,
 *   可在纯 JVM 单测中验证 roundtrip。
 */
interface OJSecretsStore {
    /** 写入(覆盖)某平台凭据。 */
    fun save(
        platformId: PlatformId,
        secret: String,
    )

    /** 读取某平台凭据;从未保存过返回 null。 */
    fun load(platformId: PlatformId): String?

    /** 删除某平台凭据;未保存过时为 no-op。 */
    fun remove(platformId: PlatformId)
}
