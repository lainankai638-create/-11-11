package com.ounan.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** App-private SQLite archive for manually reviewed screenshot transcriptions.
 * Intentionally plaintext for this phase, as requested by the user.
 * No storage permission, network access or automatic screenshot capture.
 */
internal class ChatBatchArchive(context: Context) : SQLiteOpenHelper(context.applicationContext, "ounan_chat_screenshots.db", null, 1) {
    data class Entry(val id: Long, val title: String, val content: String, val imageCount: Int, val createdAt: Long)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, content TEXT NOT NULL, images INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX chats_recent ON chats(created_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun save(title: String, content: String, images: Int): Long {
        if (title.isBlank() || content.isBlank() || images <= 0 || content.length > 180_000) return -1L
        return writableDatabase.insert("chats", null, ContentValues().apply {
            put("title", title.take(120)); put("content", content); put("images", images)
            put("created_at", System.currentTimeMillis())
        })
    }
    @Synchronized fun list(query: String = "", limit: Int = 100): List<Entry> {
        val found = mutableListOf<Entry>()
        readableDatabase.query("chats", arrayOf("id", "title", "content", "images", "created_at"),
            null, null, null, null, "created_at DESC").use { c ->
            while (c.moveToNext() && found.size < limit.coerceIn(1, 1000)) {
                val entry = Entry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4))
                if (query.isBlank() || entry.title.contains(query, true) || entry.content.contains(query, true)) found.add(entry)
            }
        }
        return found
    }
    @Synchronized fun get(id: Long): Entry? = readableDatabase.query("chats",
        arrayOf("id", "title", "content", "images", "created_at"), "id=?",
        arrayOf(id.toString()), null, null, null, "1").use { c ->
        if (c.moveToFirst()) Entry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)) else null
    }
    @Synchronized fun restore(e: Entry): Boolean {
        if (e.id <= 0 || e.title.isBlank() || e.content.isBlank()) return false
        return writableDatabase.insertWithOnConflict("chats", null, ContentValues().apply {
            put("id", e.id); put("title", e.title); put("content", e.content)
            put("images", e.imageCount); put("created_at", e.createdAt)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized fun update(id: Long, title: String, body: String): Boolean {
        if (title.isBlank() || body.isBlank() || body.length > 180_000) return false
        return writableDatabase.update("chats", ContentValues().apply {
            put("title", title.take(120)); put("content", body)
        }, "id=?", arrayOf(id.toString())) == 1
    }
    @Synchronized fun delete(id: Long) = writableDatabase.delete("chats", "id=?", arrayOf(id.toString())) == 1
}
