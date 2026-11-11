package com.ounan.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Private, locally encrypted history and connection settings. Never copies the API key. */
internal class GptConversationDb(context: Context): SQLiteOpenHelper(context.applicationContext, "ounan_gpt_conversations.db", null, 1) {
    data class Message(val role: String, val text: String, val createdAt: Long)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, sealed TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun append(role: String, body: String) {
        require(role in setOf("user", "assistant", "audit"))
        val safe=body.take(20000)
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("role",role); put("sealed",ArchiveCrypto.seal(safe)); put("created_at",System.currentTimeMillis())
        })
        writableDatabase.execSQL("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT 1000)")
    }
    @Synchronized fun recent(limit: Int = 100): List<Message> {
        val out= mutableListOf<Message>()
        readableDatabase.rawQuery("SELECT role,sealed,created_at FROM messages ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(1,1000).toString())).use { c ->
            while (c.moveToNext()) runCatching { out.add(Message(c.getString(0), ArchiveCrypto.open(c.getString(1)), c.getLong(2))) }
        }
        return out.asReversed()
    }
    @Synchronized fun clear() { writableDatabase.delete("messages",null,null) }
}

internal class GptSecureConnection(private val context: Context) {
    private val pref=context.getSharedPreferences("gpt_secure_connection_v1",Context.MODE_PRIVATE)
    fun save(endpoint: String, token: String) {
        val plain=JSONObject().put("endpoint",endpoint).put("token",token).toString()
        pref.edit().putString("sealed",ArchiveCrypto.seal(plain)).apply()
    }
    fun load(): Pair<String,String>? {
        val encrypted=pref.getString("sealed",null) ?: return null
        return runCatching {
            val o=JSONObject(ArchiveCrypto.open(encrypted))
            val url=o.optString("endpoint")
            val token=o.optString("token")
            if (!url.startsWith("https://") || token.isBlank()) null else url to token
        }.getOrNull()
    }
    fun clear() { pref.edit().clear().apply() }
}
