package com.ounan.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/** Local at-rest encryption; the key is not exportable from Android Keystore.
 * For device migration a separate passphrase-encrypted portable backup is required. */
internal object ArchiveCrypto {
    private const val KEY_ALIAS = "ounan_wechat_aes_v1"
    private const val HMAC_ALIAS = "ounan_wechat_dedup_v1"
    private val random = SecureRandom()
    private fun aesKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return kg.generateKey()
    }
    private fun hmacKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return kg.generateKey()
    }
    fun fingerprint(s: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey())
        return mac.doFinal(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }
    fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        require(bytes.size >= 29) { "密文不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun portableSeal(plain: String, passphrase: CharArray): String {
        require(passphrase.size >= 12) { "备份密码至少 12 位" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val spec = PBEKeySpec(passphrase, salt, 210_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD("OUNAN-WECHAT-BACKUP-V1".toByteArray())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return "OUNAN-WECHAT-1:" + Base64.encodeToString(salt + iv + encrypted, Base64.NO_WRAP)
        } finally { key.fill(0) }
    }
    fun portableOpen(value: String, passphrase: CharArray): String {
        require(value.startsWith("OUNAN-WECHAT-1:")) { "不是受支持的微信留档备份" }
        val bytes = Base64.decode(value.removePrefix("OUNAN-WECHAT-1:"), Base64.NO_WRAP)
        require(bytes.size >= 45) { "备份损坏" }
        val spec = PBEKeySpec(passphrase, bytes.copyOfRange(0, 16), 210_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(16, 28)))
            cipher.updateAAD("OUNAN-WECHAT-BACKUP-V1".toByteArray())
            return String(cipher.doFinal(bytes.copyOfRange(28, bytes.size)), Charsets.UTF_8)
        } finally { key.fill(0) }
    }
}

/** Chat excerpts: one entry is a visible-screen snapshot, NOT a verified chat message.
 * No plaintext contact name or conversation body in SQLite columns. */
class WeChatArchive(context: Context) : SQLiteOpenHelper(context.applicationContext, "wechat_archive.db", null, 1) {
    data class Entry(
        val id: Long, val conversation: String, val body: String, val tags: String,
        val method: String, val capturedAt: Long, val note: String = ""
    )
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE excerpts (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL UNIQUE, captured_at INTEGER NOT NULL, sealed TEXT NOT NULL)")
        db.execSQL("CREATE INDEX excerpts_time ON excerpts(captured_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun packed(e: Entry): String = JSONObject().apply {
        put("conversation", e.conversation); put("body", e.body); put("tags", e.tags)
        put("method", e.method); put("captured_at", e.capturedAt); put("note", e.note)
    }.toString()
    private fun unpack(id: Long, sealed: String): Entry {
        val j = JSONObject(ArchiveCrypto.open(sealed))
        return Entry(id, j.getString("conversation"), j.getString("body"), j.optString("tags"),
            j.optString("method"), j.getLong("captured_at"), j.optString("note"))
    }
    private fun normalized(s: String) = s.trim().replace(Regex("\\s+"), " ")
    @Synchronized fun insert(conversation: String, body: String, method: String, time: Long = System.currentTimeMillis()): Boolean {
        val name = conversation.trim().take(120)
        val raw = body.trim().take(30000)
        if (name.isBlank() || raw.length < 5) return false
        val entry = Entry(0L, name, raw, KnowledgeRules.tags(raw), method.take(60), time)
        val key = ArchiveCrypto.fingerprint(name + "\n" + normalized(raw))
        return writableDatabase.insertWithOnConflict("excerpts", null, ContentValues().apply {
            put("fingerprint", key); put("captured_at", time); put("sealed", ArchiveCrypto.seal(packed(entry)))
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized fun list(search: String = "", limit: Int = 1000): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.query("excerpts", arrayOf("id", "sealed"), null, null, null, null,
            "captured_at DESC").use { c ->
            while (c.moveToNext() && result.size < limit.coerceIn(1, 10000)) {
                val e = unpack(c.getLong(0), c.getString(1))
                if (search.isBlank() || listOf(e.conversation, e.body, e.tags, e.note).any { it.contains(search, ignoreCase = true) }) {
                    result.add(e)
                }
            }
        }
        return result
    }
    @Synchronized fun update(e: Entry, conversation: String, tags: String, note: String): Boolean {
        val old = e.copy(conversation = conversation.trim().take(120), tags = tags.trim().take(300), note = note.trim().take(1500))
        if (old.conversation.isBlank()) return false
        val key = ArchiveCrypto.fingerprint(old.conversation + "\n" + normalized(old.body))
        return try {
            writableDatabase.updateWithOnConflict("excerpts", ContentValues().apply {
                put("fingerprint", key); put("sealed", ArchiveCrypto.seal(packed(old)))
            }, "id=?", arrayOf(e.id.toString()), SQLiteDatabase.CONFLICT_ABORT) == 1
        } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
    }
    @Synchronized fun get(id: Long): Entry? = readableDatabase.query("excerpts",
        arrayOf("id", "sealed"), "id=?", arrayOf(id.toString()), null, null, null, "1").use {
        if (it.moveToFirst()) unpack(it.getLong(0), it.getString(1)) else null
    }
    @Synchronized fun replace(entry: Entry): Boolean {
        if (entry.conversation.isBlank() || entry.body.isBlank() || entry.body.length > 30000) return false
        val changed = entry.copy(conversation = entry.conversation.trim().take(120),
            tags = entry.tags.take(300), note = entry.note.take(1500))
        val digest = ArchiveCrypto.fingerprint(changed.conversation + "\n" + normalized(changed.body))
        return try {
            writableDatabase.updateWithOnConflict("excerpts", ContentValues().apply {
                put("fingerprint", digest); put("sealed", ArchiveCrypto.seal(packed(changed)))
            }, "id=?", arrayOf(changed.id.toString()), SQLiteDatabase.CONFLICT_ABORT) == 1
        } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
    }
    @Synchronized fun restore(entry: Entry): Boolean {
        if (entry.id <= 0L || entry.conversation.isBlank() || entry.body.isBlank()) return false
        val digest = ArchiveCrypto.fingerprint(entry.conversation + "\n" + normalized(entry.body))
        return writableDatabase.insertWithOnConflict("excerpts", null, ContentValues().apply {
            put("id", entry.id); put("fingerprint", digest); put("captured_at", entry.capturedAt)
            put("sealed", ArchiveCrypto.seal(packed(entry)))
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized fun createManaged(title: String, body: String): Long {
        val conversation = title.trim().take(120)
        val content = body.trim().take(30000)
        if (conversation.isBlank() || content.isBlank()) return -1L
        val t = System.currentTimeMillis()
        val entry = Entry(0, conversation, content, KnowledgeRules.tags(content), "人工管理录入", t)
        return writableDatabase.insertWithOnConflict("excerpts", null, ContentValues().apply {
            put("fingerprint", ArchiveCrypto.fingerprint(conversation + "\n" + normalized(content)))
            put("captured_at", t); put("sealed", ArchiveCrypto.seal(packed(entry)))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun delete(id: Long): Boolean = writableDatabase.delete("excerpts", "id=?", arrayOf(id.toString())) == 1
    /** Every entry is included; unlike ordinary note export, this is not capped at 1,000. */
    @Synchronized fun exportEncrypted(passphrase: CharArray): String {
        val entries = JSONArray()
        readableDatabase.query("excerpts", arrayOf("id", "sealed"), null, null, null, null, "captured_at ASC").use { c ->
            while (c.moveToNext()) entries.put(JSONObject(ArchiveCrypto.open(c.getString(1))))
        }
        return ArchiveCrypto.portableSeal(JSONObject().put("format", "ounan_wechat_archive_v1").put("entries", entries).toString(), passphrase)
    }
    /** Verify entire backup before writing; entries already present are deduplicated. */
    @Synchronized fun importEncrypted(encrypted: String, passphrase: CharArray): Pair<Int, Int> {
        val j = JSONObject(ArchiveCrypto.portableOpen(encrypted.trim(), passphrase))
        require(j.optString("format") == "ounan_wechat_archive_v1") { "备份格式无效" }
        val items = j.getJSONArray("entries")
        require(items.length() <= 100_000) { "备份记录过多" }
        var added = 0
        val data = ArrayList<Entry>(items.length())
        for (i in 0 until items.length()) {
            val e = items.getJSONObject(i)
            val conv = e.getString("conversation")
            val body = e.getString("body")
            require(conv.isNotBlank() && body.length in 5..30000) { "备份条目内容无效" }
            data.add(Entry(0L, conv, body, e.optString("tags"), e.optString("method"), e.getLong("captured_at"), e.optString("note")))
        }
        val sql = writableDatabase
        sql.beginTransaction()
        try {
            for (e in data) {
                val key = ArchiveCrypto.fingerprint(e.conversation.trim() + "\n" + normalized(e.body))
                if (sql.insertWithOnConflict("excerpts", null, ContentValues().apply {
                    put("fingerprint", key); put("captured_at", e.capturedAt); put("sealed", ArchiveCrypto.seal(packed(e)))
                }, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        return added to items.length()
    }
}
