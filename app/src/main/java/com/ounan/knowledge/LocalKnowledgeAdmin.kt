package com.ounan.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Typed, local-only commands. Never runs SQL supplied by GPT or a document. */
internal data class AdminRecord(
    val source: String, val id: Long, val title: String, val body: String,
    val tags: String = "", val note: String = "", val method: String = "",
    val origin: String = "", val url: String = "", val time: Long = 0L, val images: Int = 0
) {
    fun json(): String = JSONObject().apply {
        put("source",source); put("id",id); put("title",title); put("body",body)
        put("tags",tags); put("note",note); put("method",method)
        put("origin",origin); put("url",url); put("time",time); put("images",images)
    }.toString()
    companion object {
        fun from(s: String): AdminRecord {
            val o = JSONObject(s)
            return AdminRecord(o.getString("source"), o.getLong("id"), o.getString("title"),
                o.getString("body"), o.optString("tags"), o.optString("note"), o.optString("method"),
                o.optString("origin"), o.optString("url"), o.optLong("time"), o.optInt("images"))
        }
    }
}

/** Change journal holds encrypted versions even for the optional plaintext screenshot archive.
 * All edits/deletes are preceded by a recoverable snapshot. No plaintext content in journal SQL.
 */
internal class AdminChangeJournal(context: Context): SQLiteOpenHelper(
    context.applicationContext, "ounan_admin_changes.db", null, 1
) {
    data class Change(val id: Long, val source: String, val recordId: Long, val action: String,
                      val previous: AdminRecord?, val next: AdminRecord?, val state: String,
                      val timestamp: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE changes (id INTEGER PRIMARY KEY AUTOINCREMENT, src TEXT NOT NULL, item_id INTEGER NOT NULL, action TEXT NOT NULL, old_sealed TEXT, new_sealed TEXT, state TEXT NOT NULL, at_time INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX changes_recent ON changes(at_time DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun seal(r: AdminRecord?): String? = r?.let { ArchiveCrypto.seal(it.json()) }
    private fun read(s: String?): AdminRecord? = if (s.isNullOrBlank()) null else AdminRecord.from(ArchiveCrypto.open(s))

    @Synchronized fun begin(source: String, id: Long, action: String, previous: AdminRecord?): Long =
        writableDatabase.insertOrThrow("changes", null, ContentValues().apply {
            put("src", source); put("item_id", id); put("action", action)
            put("old_sealed", seal(previous)); put("state", "pending")
            put("at_time", System.currentTimeMillis())
        })

    @Synchronized fun applied(changeId: Long, next: AdminRecord?) {
        val n = writableDatabase.update("changes", ContentValues().apply {
            put("item_id", next?.id ?: get(changeId)?.recordId ?: 0L)
            put("new_sealed", seal(next)); put("state", "applied")
        }, "id=? AND state='pending'", arrayOf(changeId.toString()))
        check(n == 1) { "无法提交操作历史" }
    }
    @Synchronized fun abort(changeId: Long) {
        writableDatabase.delete("changes", "id=? AND state='pending'", arrayOf(changeId.toString()))
    }
    @Synchronized fun markUndone(changeId: Long): Boolean =
        writableDatabase.update("changes", ContentValues().apply { put("state", "undone") },
            "id=? AND state IN ('applied','pending')", arrayOf(changeId.toString())) == 1
    @Synchronized fun get(changeId: Long): Change? = readableDatabase.query("changes",
        arrayOf("id", "src", "item_id", "action", "old_sealed", "new_sealed", "state", "at_time"),
        "id=?", arrayOf(changeId.toString()), null,null,null,"1").use { c ->
        if (!c.moveToFirst()) null else Change(c.getLong(0), c.getString(1) ?: "", c.getLong(2),
            c.getString(3) ?: "", read(c.getString(4)), read(c.getString(5)), c.getString(6) ?: "", c.getLong(7))
    }
    @Synchronized fun recent(limit: Int = 30): List<Change> {
        val result = mutableListOf<Change>()
        readableDatabase.query("changes", arrayOf("id"), null,null,null,null,"at_time DESC, id DESC",
            limit.coerceIn(1,100).toString()).use { c ->
            while (c.moveToNext()) get(c.getLong(0))?.let { result.add(it) }
        }
        return result
    }
}

/** Full local scope across the three archives. GPT is not connected in v0.5.0. */
internal class LocalKnowledgeAdmin(
    private val notes: KnowledgeDb, private val chats: WeChatArchive,
    private val screenshots: ChatBatchArchive, val journal: AdminChangeJournal
) {
    companion object {
        const val NOTES = "notes"
        const val WECHAT = "wechat"
        const val SCREENSHOTS = "screenshots"
        fun sourceLabel(source: String): String = when(source) {
            NOTES -> "知识碎片"; WECHAT -> "微信留档"; SCREENSHOTS -> "截图聊天"; else -> "未知来源"
        }
    }

    @Synchronized fun get(source: String, id: Long): AdminRecord? = when(source) {
        NOTES -> notes.get(id)?.let { AdminRecord(NOTES,it.id,it.title,it.body,it.tags,
            origin=it.origin,url=it.sourceUrl,time=it.createdAt) }
        WECHAT -> chats.get(id)?.let { AdminRecord(WECHAT,it.id,it.conversation,it.body,it.tags,
            note=it.note,method=it.method,time=it.capturedAt) }
        SCREENSHOTS -> screenshots.get(id)?.let { AdminRecord(SCREENSHOTS,it.id,it.title,it.content,
            time=it.createdAt,images=it.imageCount) }
        else -> null
    }

    /** Search all sources on device; displayed results limited for usability, never uploads content. */
    @Synchronized fun search(text: String, source: String = "all", perSource: Int = 60): List<AdminRecord> {
        val cap = perSource.coerceIn(1,1000)
        val out = mutableListOf<AdminRecord>()
        if (source == "all" || source == NOTES) notes.list(text, cap).forEach { n ->
            out.add(AdminRecord(NOTES,n.id,n.title,n.body,n.tags,origin=n.origin,url=n.sourceUrl,time=n.createdAt))
        }
        if (source == "all" || source == WECHAT) chats.list(text, cap).forEach { n ->
            out.add(AdminRecord(WECHAT,n.id,n.conversation,n.body,n.tags,note=n.note,method=n.method,time=n.capturedAt))
        }
        if (source == "all" || source == SCREENSHOTS) screenshots.list(text, cap).forEach { n ->
            out.add(AdminRecord(SCREENSHOTS,n.id,n.title,n.content,time=n.createdAt,images=n.imageCount))
        }
        return out.sortedWith(compareByDescending<AdminRecord> { it.time }.thenByDescending { it.id })
    }

    @Synchronized fun create(source: String, title: String, body: String, tags: String = ""): Boolean {
        if (title.isBlank() || body.isBlank()) return false
        val hid = journal.begin(source, 0, "create", null)
        try {
            val id = when(source) {
                NOTES -> notes.createManaged(title,body,tags)
                WECHAT -> chats.createManaged(title,body)
                SCREENSHOTS -> screenshots.save(title,body,1)
                else -> -1L
            }
            if (id < 0) { journal.abort(hid); return false }
            val created = get(source,id) ?: error("新记录无法重新读取")
            journal.applied(hid,created)
            return true
        } catch (t: Exception) {
            // Leave a pending journal entry if the mutation may already have happened.
            throw t
        }
    }

    @Synchronized fun edit(source: String, id: Long, title: String, body: String, tags: String = "", memo: String? = null): Boolean {
        val before = get(source,id) ?: return false
        if (title.isBlank() || body.isBlank()) return false
        if (before.title == title && before.body == body && (source == SCREENSHOTS || before.tags == tags) && (memo == null || before.note == memo)) return true
        val hid = journal.begin(source,id,"edit",before)
        val success = when(source) {
            NOTES -> notes.get(id)?.let { notes.update(it,title,body,tags) } ?: false
            WECHAT -> chats.get(id)?.let { chats.replace(it.copy(conversation=title,body=body,tags=tags,note=memo ?: it.note)) } ?: false
            SCREENSHOTS -> screenshots.update(id,title,body)
            else -> false
        }
        if (!success) { journal.abort(hid); return false }
        journal.applied(hid,get(source,id) ?: error("修改后记录读取失败"))
        return true
    }

    @Synchronized fun delete(source: String, id: Long): Boolean {
        val before = get(source,id) ?: return false
        val hid = journal.begin(source,id,"delete",before)
        val success = when(source) {
            NOTES -> notes.get(id)?.let { notes.delete(it) == 1 } ?: false
            WECHAT -> chats.delete(id)
            SCREENSHOTS -> screenshots.delete(id)
            else -> false
        }
        if (!success) { journal.abort(hid); return false }
        journal.applied(hid,null)
        return true
    }

    /** Undo only when data still matches the journaled next version: later edits are never overwritten. */
    @Synchronized fun undo(changeId: Long): Boolean {
        val change = journal.get(changeId) ?: return false
        if (change.state != "applied" && change.state != "pending") return false
        val before = change.previous
        val current = get(change.source, change.recordId)
        if (change.state == "applied" && current != change.next) return false
        // If an interrupted operation left a pending snapshot, restore only original (not overwrite it).
        if (change.state == "pending" && (change.action != "delete" || current != null)) return false
        val ok = if (before == null) {
            if (current == null) false else deleteDirect(current)
        } else if (current == null) {
            restore(before)
        } else {
            putBack(before)
        }
        return ok && journal.markUndone(changeId)
    }

    private fun deleteDirect(r: AdminRecord): Boolean = when(r.source) {
        NOTES -> notes.get(r.id)?.let { notes.delete(it) == 1 } ?: false
        WECHAT -> chats.delete(r.id)
        SCREENSHOTS -> screenshots.delete(r.id)
        else -> false
    }
    private fun restore(r: AdminRecord): Boolean = when(r.source) {
        NOTES -> notes.restore(KnowledgeDb.Note(r.id,r.title,r.body,r.tags,r.origin,r.url,r.time,""))
        WECHAT -> chats.restore(WeChatArchive.Entry(r.id,r.title,r.body,r.tags,r.method,r.time,r.note))
        SCREENSHOTS -> screenshots.restore(ChatBatchArchive.Entry(r.id,r.title,r.body,r.images,r.time))
        else -> false
    }
    private fun putBack(r: AdminRecord): Boolean = when(r.source) {
        NOTES -> notes.get(r.id)?.let { notes.update(it,r.title,r.body,r.tags) } ?: false
        WECHAT -> chats.replace(WeChatArchive.Entry(r.id,r.title,r.body,r.tags,r.method,r.time,r.note))
        SCREENSHOTS -> screenshots.update(r.id,r.title,r.body)
        else -> false
    }
}
