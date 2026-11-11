package com.ounan.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** AES-GCM-encrypted knowledge fragments: only timestamps, ids and keyed digests remain unencrypted.
 * Searches decrypt locally. No cloud sync or database INTERNET permission. */
class KnowledgeDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "ounan_fragments_encrypted_v1.db", null, 1) {
    data class Note(val id: Long, val title: String, val body: String, val tags: String,
                    val origin: String, val sourceUrl: String, val createdAt: Long, val digest: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, captured_at INTEGER NOT NULL, digest TEXT NOT NULL UNIQUE, sealed TEXT NOT NULL)")
        db.execSQL("CREATE INDEX notes_time ON notes(captured_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun key(body: String) = ArchiveCrypto.fingerprint("fragment\n" + body.trim().replace(Regex("\\s+"), " "))
    private fun pack(n: Note) = JSONObject().apply {
        put("title", n.title); put("body", n.body); put("tags", n.tags)
        put("origin", n.origin); put("source_url", n.sourceUrl); put("created_at", n.createdAt)
    }.toString()
    private fun unpack(id: Long, digest: String, sealed: String): Note {
        val o = JSONObject(ArchiveCrypto.open(sealed))
        return Note(id, o.getString("title"), o.getString("body"), o.optString("tags"),
            o.optString("origin"), o.optString("source_url"), o.getLong("created_at"), digest)
    }
    private fun note(c: Cursor): Note = unpack(c.getLong(0), c.getString(1), c.getString(2))

    @Synchronized fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
    @Synchronized fun insert(text: String, origin: String, minChars: Int = 1): Boolean {
        val body = text.replace("\u0000", "").trim().take(30000)
        if (body.length < minChars) return false
        val created = System.currentTimeMillis()
        val n = Note(0L, body.lines().firstOrNull { it.trim().length >= 4 }?.trim()?.take(68) ?: body.take(68),
            body, KnowledgeRules.tags(body), origin.take(200),
            Regex("https?://[^\\s<>\"]+").find(body)?.value.orEmpty().take(1000), created, key(body))
        return writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("captured_at", created); put("digest", n.digest); put("sealed", ArchiveCrypto.seal(pack(n)))
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    /** Explicit clipboard capture. Preserve the complete original body, title, source and local tags. */
    @Synchronized fun createCaptured(titleInput: String, original: String, tagsInput: String, source: String): Long {
        val body = original.replace("\u0000", "").trim()
        require(body.isNotBlank() && body.length <= 30000) { "每份文档限制 30000 字；请分段复制保存" }
        val created = System.currentTimeMillis()
        val suggested = KnowledgeRules.tags(body)
        val n = Note(0L, titleInput.trim().ifBlank { body.lineSequence().first().orEmpty().take(68) }.take(200),
            body, tagsInput.trim().ifBlank { suggested }.take(300), source.trim().take(200),
            Regex("https?://[^\\s<>\"]+").find(body)?.value.orEmpty().take(1000),
            created, key(body))
        return writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("captured_at", created); put("digest", n.digest); put("sealed", ArchiveCrypto.seal(pack(n)))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun get(id: Long): Note? = readableDatabase.query("notes", arrayOf("id","digest","sealed"),
        "id=?", arrayOf(id.toString()), null,null,null,"1").use { if (it.moveToFirst()) note(it) else null }

    @Synchronized fun list(query: String = "", limit: Int = 300): List<Note> {
        val results = ArrayList<Note>()
        readableDatabase.query("notes", arrayOf("id","digest","sealed"), null,null,null,null,"captured_at DESC").use { c ->
            while (c.moveToNext() && results.size < limit.coerceIn(1,10000)) {
                val n = note(c)
                if (query.isBlank() || sequenceOf(n.title,n.body,n.tags,n.origin).any { it.contains(query, true) }) results.add(n)
            }
        }
        return results
    }
    @Synchronized fun update(note: Note, newTitle: String, newBody: String, newTags: String): Boolean {
        val body = newBody.trim().take(30000)
        if (body.isBlank()) return false
        val n = note.copy(title = newTitle.trim().take(200), body = body, tags = newTags.take(300), digest = key(body))
        return try {
            writableDatabase.updateWithOnConflict("notes", ContentValues().apply {
                put("digest", n.digest); put("sealed", ArchiveCrypto.seal(pack(n)))
            }, "id=?", arrayOf(note.id.toString()), SQLiteDatabase.CONFLICT_ABORT) == 1
        } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
    }
    /** Restore an original ID after a locally journaled deletion; never overwrite another record. */
    @Synchronized fun restore(n: Note): Boolean {
        require(n.id > 0 && n.body.isNotBlank())
        return writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", n.id); put("captured_at", n.createdAt)
            put("digest", key(n.body)); put("sealed", ArchiveCrypto.seal(pack(n)))
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized fun createManaged(title: String, bodyInput: String, tags: String): Long {
        val body = bodyInput.trim().take(30000)
        if (body.isBlank()) return -1L
        val created = System.currentTimeMillis()
        val n = Note(0L, title.trim().ifBlank { body.lineSequence().first().take(68) }.take(200),
            body, tags.trim().take(300), "本地知识管理员", "", created, key(body))
        return writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("captured_at", created); put("digest", n.digest)
            put("sealed", ArchiveCrypto.seal(pack(n)))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun delete(note: Note): Int = writableDatabase.delete("notes", "id=?", arrayOf(note.id.toString()))
    @Synchronized fun clearAll(): Int = writableDatabase.delete("notes", null, null)
    @Synchronized fun aiSummary(id: Long): String = "" // Offline first: no unapproved cloud AI data.
    @Synchronized fun revisionCount(id: Long): Long = 0L
    @Synchronized fun exportEncrypted(password: CharArray): String {
        val items = JSONArray()
        readableDatabase.query("notes", arrayOf("id","digest","sealed"), null,null,null,null,"captured_at ASC").use { c ->
            while (c.moveToNext()) items.put(JSONObject(pack(note(c))))
        }
        return PortableBackup.encrypt(JSONObject().put("format","ounan_knowledge_v1").put("entries",items).toString(), password)
    }
    /** All-or-nothing verification and import. A wrong password never changes database. */
    @Synchronized fun importEncrypted(blob: String, password: CharArray): Pair<Int,Int> {
        val root = JSONObject(PortableBackup.decrypt(blob.trim(), password))
        require(root.optString("format") == "ounan_knowledge_v1") { "知识备份格式不正确" }
        val arr = root.getJSONArray("entries")
        require(arr.length() <= 100_000) { "备份记录过多" }
        val pending = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val body = o.getString("body")
            require(body.isNotBlank() && body.length <= 30000) { "记录内容格式错误" }
            pending.add(Note(0L, o.getString("title").take(200), body, o.optString("tags").take(300),
                o.optString("origin").take(200), o.optString("source_url").take(1000), o.getLong("created_at"), key(body)))
        }
        val sql = writableDatabase; var added = 0
        sql.beginTransaction()
        try {
            for (n in pending) {
                if (sql.insertWithOnConflict("notes", null, ContentValues().apply {
                    put("captured_at", n.createdAt); put("digest", n.digest); put("sealed", ArchiveCrypto.seal(pack(n)))
                },SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        return added to pending.size
    }
}
