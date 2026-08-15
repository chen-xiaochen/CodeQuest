package com.codequest.infra.secrets

import com.codequest.core.model.PlatformId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [unit] WP18:加密文件凭据存储 save/load/remove roundtrip 与防篡改语义。 */
class EncryptedFileSecretsStoreTest {

    private lateinit var directory: Path
    private lateinit var store: EncryptedFileSecretsStore

    private val leetcode = PlatformId("leetcode")
    private val codeforces = PlatformId("codeforces")

    @AfterTest
    fun tearDown() {
        if (::directory.isInitialized) {
            directory.toFile().walkBottomUp().forEach { it.delete() }
        }
    }

    private fun newStore(): EncryptedFileSecretsStore {
        directory = Files.createTempDirectory("codequest-secrets-test")
        store = EncryptedFileSecretsStore(directory)
        return store
    }

    @Test
    fun `save then load roundtrips the secret`() {
        val store = newStore()
        store.save(leetcode, "LEETCODE_SESSION=abc; csrftoken=xyz")
        assertEquals("LEETCODE_SESSION=abc; csrftoken=xyz", store.load(leetcode))
    }

    @Test
    fun `load returns null when nothing saved`() {
        val store = newStore()
        assertNull(store.load(leetcode))
    }

    @Test
    fun `save overwrites previous value`() {
        val store = newStore()
        store.save(leetcode, "old")
        store.save(leetcode, "new")
        assertEquals("new", store.load(leetcode))
    }

    @Test
    fun `remove deletes the secret`() {
        val store = newStore()
        store.save(leetcode, "secret")
        store.remove(leetcode)
        assertNull(store.load(leetcode))
    }

    @Test
    fun `remove on missing secret is a no-op`() {
        val store = newStore()
        store.remove(leetcode) // 不得抛异常
        assertNull(store.load(leetcode))
    }

    @Test
    fun `platforms are stored independently`() {
        val store = newStore()
        store.save(leetcode, "lc-secret")
        store.save(codeforces, "cf-secret")
        assertEquals("lc-secret", store.load(leetcode))
        assertEquals("cf-secret", store.load(codeforces))
        store.remove(leetcode)
        assertEquals("cf-secret", store.load(codeforces))
    }

    @Test
    fun `secret file is not stored in plaintext`() {
        val store = newStore()
        store.save(leetcode, "PLAINTEXT-SHOULD-NOT-APPEAR")
        // ISO_8859_1 逐字节解码，任何明文片段都会现形
        val leaked = Files.walk(directory)
            .filter { Files.isRegularFile(it) }
            .anyMatch { path -> path.toFile().readBytes().toString(Charsets.ISO_8859_1).contains("PLAINTEXT") }
        assertTrue(!leaked, "secret file contains plaintext")
    }

    @Test
    fun `swapping files between platforms is rejected by AAD binding`() {
        val store = newStore()
        store.save(leetcode, "lc-secret")
        store.save(codeforces, "cf-secret")
        // 人为交换两个平台的密文文件
        val lcFile = directory.resolve("leetcode.secret")
        val cfFile = directory.resolve("codeforces.secret")
        val lcBytes = lcFile.toFile().readBytes()
        val cfBytes = cfFile.toFile().readBytes()
        lcFile.toFile().writeBytes(cfBytes)
        cfFile.toFile().writeBytes(lcBytes)

        assertFailsWith<IllegalStateException> { store.load(leetcode) }
        assertFailsWith<IllegalStateException> { store.load(codeforces) }
    }
}
