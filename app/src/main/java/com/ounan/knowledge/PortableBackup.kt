package com.ounan.knowledge

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable password-encrypted backups, independent of device Keystore. No recovery backdoor. */
internal object PortableBackup {
    private const val PREFIX = "OUNAN-LOCAL-1:"
    private val aad = "OUNAN-PRIVATE-KNOWLEDGE-V1".toByteArray(Charsets.US_ASCII)
    private val rng = SecureRandom()

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
    }
    fun encrypt(json: String, password: CharArray): String {
        require(password.size >= 12) { "备份口令至少 12 位" }
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val key = derive(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            PREFIX + Base64.encodeToString(salt + nonce + cipher.doFinal(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } finally { key.fill(0) }
    }
    fun decrypt(payload: String, password: CharArray): String {
        require(payload.startsWith(PREFIX)) { "不是受支持的知识库备份" }
        val blob = Base64.decode(payload.removePrefix(PREFIX), Base64.NO_WRAP)
        require(blob.size >= 16 + 12 + 16) { "备份长度不完整" }
        val key = derive(password, blob.copyOfRange(0,16))
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(16,28)))
            cipher.updateAAD(aad)
            String(cipher.doFinal(blob.copyOfRange(28, blob.size)), Charsets.UTF_8)
        } finally { key.fill(0) }
    }
}
