package com.codequest.infra.secrets

import com.codequest.core.model.PlatformId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM 加密文件凭据存储(WP18):不依赖 IntelliJ 运行时,用于纯 JVM 单测与
 * 无 PasswordSafe 场景(如 CLI/测试环境)的兜底。
 *
 * ## 布局
 * - `<dir>/secrets.key`:32 字节 AES 密钥,首次保存时生成,权限尽量收敛;
 * - `<dir>/<platformId>.secret`:`版本(1B) | GCM nonce(12B) | 密文+tag`;
 * - **AAD 绑定平台 id**:把 leetcode 与 codeforces 的密文文件对调会解密失败
 *   (防「文件搬运」类篡改,单测固化)。
 *
 * ## 安全级别说明
 * 密钥与密文同目录,防御的是「随手打开文件即见明文 cookie」,等同于 PasswordSafe
 * 的「文件加密 + 密钥就近」兜底档;插件内生产路径仍优先 [PasswordSafeSecretsStore]
 * (OS 钥匙串托管密钥)。这是**加密混淆**而非对抗性安全边界。
 */
class EncryptedFileSecretsStore(
    private val directory: Path,
) : OJSecretsStore {
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override fun save(
        platformId: PlatformId,
        secret: String,
    ) {
        Files.createDirectories(directory)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(platformId.value.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        val content = ByteArray(1 + iv.size + encrypted.size)
        content[0] = FORMAT_VERSION
        iv.copyInto(content, 1)
        encrypted.copyInto(content, 1 + iv.size)
        // 原子写:先写临时文件再原子移动,避免半截密文
        val target = fileOf(platformId)
        val tmp = directory.resolve("${platformId.value}.secret.tmp")
        Files.write(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(platformId: PlatformId): String? {
        val file = fileOf(platformId)
        if (!Files.exists(file)) return null
        val content = Files.readAllBytes(file)
        require(content.size > 1 + GCM_IV_LENGTH_BYTES) { "corrupted secret file: ${file.fileName}" }
        if (content[0] != FORMAT_VERSION) {
            error("unsupported secret format version ${content[0]}: ${file.fileName}")
        }
        val iv = content.copyOfRange(1, 1 + GCM_IV_LENGTH_BYTES)
        val encrypted = content.copyOfRange(1 + GCM_IV_LENGTH_BYTES, content.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(platformId.value.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalStateException("secret decrypt failed (corrupted or AAD mismatch): ${file.fileName}", e)
        }
    }

    override fun remove(platformId: PlatformId) {
        Files.deleteIfExists(fileOf(platformId))
    }

    private fun fileOf(platformId: PlatformId): Path = directory.resolve("${platformId.value}.secret")

    private fun loadOrCreateKey(): SecretKey {
        Files.createDirectories(directory)
        val keyFile = directory.resolve(KEY_FILE_NAME)
        if (Files.exists(keyFile)) {
            return SecretKeySpec(Files.readAllBytes(keyFile), "AES")
        }
        val generated = KeyGenerator.getInstance("AES").apply { init(AES_KEY_SIZE_BITS) }.generateKey()
        Files.write(keyFile, generated.encoded, StandardOpenOption.CREATE_NEW)
        return SecretKeySpec(generated.encoded, "AES")
    }

    companion object {
        private const val KEY_FILE_NAME = "secrets.key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        private val SECURE_RANDOM = java.security.SecureRandom()
    }
}
