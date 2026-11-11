package com.ounan.knowledge

import org.json.JSONArray
import org.json.JSONObject

/** Executed only inside the Android process, never on the HTTPS relay server.
 * No arbitrary SQL, filesystem, shell, WebView or implicit network actions.
 */
internal class GptToolBridge(private val admin: LocalKnowledgeAdmin) {
    companion object {
        val WRITES = setOf("create_record", "edit_record", "delete_record", "undo_change")
        private val SOURCES = setOf("all", LocalKnowledgeAdmin.NOTES,
            LocalKnowledgeAdmin.WECHAT, LocalKnowledgeAdmin.SCREENSHOTS)
    }

    fun isWrite(name: String): Boolean = name in WRITES

    fun describe(name: String, args: JSONObject): String = when (name) {
        "create_record" -> "新建 ${label(args)}：${args.optString("title").take(80)}"
        "edit_record" -> "修改 ${label(args)} ID ${args.optLong("id")}：${args.optString("title").take(80)}"
        "delete_record" -> "删除 ${label(args)} ID ${args.optLong("id")}（可从历史恢复）"
        "undo_change" -> "撤销历史操作 ${args.optLong("change_id")}"
        else -> "只读检索：$name"
    }
    private fun label(o: JSONObject) = LocalKnowledgeAdmin.sourceLabel(o.optString("source"))
    private fun source(o: JSONObject, all: Boolean = false): String {
        val s = o.optString("source", if (all) "all" else "")
        require(s in SOURCES && (all || s != "all")) { "无效数据库来源" }
        return s
    }
    private fun id(o: JSONObject): Long = o.optLong("id", 0L).also {
        require(it > 0) { "无效记录编号" }
    }
    private fun required(o: JSONObject, k: String, max: Int): String =
        o.optString(k, "").trim().also { require(it.isNotBlank() && it.length <= max) { "$k 不合法" } }

    fun call(name: String, args: JSONObject): JSONObject {
      return try {
        when (name) {
            "search_records" -> {
                val src = source(args, true)
                val query = args.optString("query", "").take(150)
                val offset = args.optInt("offset", 0).coerceIn(0, 3000)
                val limit = args.optInt("limit", 10).coerceIn(1, 25)
                val results = admin.search(query, src, (offset + limit + 1).coerceAtMost(1000))
                val part = results.drop(offset).take(limit)
                val items = JSONArray()
                part.forEach { r -> items.put(JSONObject().apply {
                    put("source",r.source); put("id",r.id); put("title",r.title)
                    put("time",r.time); put("tags",r.tags)
                    put("preview",r.body.take(360))
                    put("truncated_preview",r.body.length > 360)
                }) }
                JSONObject().put("ok",true).put("results",items)
                    .put("next_offset", if (results.size > offset + limit) offset + limit else JSONObject.NULL)
                    .put("note", "按需再用 get_record 读取全文；检索每来源最多扫描最近1000条")
            }
            "get_record" -> {
                val r = admin.get(source(args), id(args))
                    ?: return JSONObject().put("ok",false).put("error","记录不存在")
                val offset = args.optInt("offset",0).coerceIn(0,r.body.length)
                val size = args.optInt("max_chars",6000).coerceIn(1,12000)
                val end = (offset + size).coerceAtMost(r.body.length)
                JSONObject().put("ok",true).put("source",r.source).put("id",r.id)
                    .put("title",r.title).put("tags",r.tags).put("time",r.time)
                    .put("body",r.body.substring(offset,end))
                    .put("body_offset",offset).put("next_offset",if (end < r.body.length) end else JSONObject.NULL)
                    .put("total_chars",r.body.length)
            }
            "create_record" -> {
                val ok = admin.create(source(args), required(args,"title",200),
                    required(args,"body",30000), args.optString("tags","").take(300))
                JSONObject().put("ok",ok).put("history_id",if (ok) admin.journal.recent(1).firstOrNull()?.id else JSONObject.NULL)
            }
            "edit_record" -> {
                val src = source(args); val rid = id(args)
                val original = admin.get(src,rid) ?: return JSONObject().put("ok",false).put("error","记录不存在")
                // Prevent AI overwriting unmentioned fields. Original metadata preserved by admin.edit().
                val ok = admin.edit(src,rid,required(args,"title",200),required(args,"body",180000),
                    args.optString("tags", original.tags).take(300))
                JSONObject().put("ok",ok).put("history_id", if (ok) admin.journal.recent(1).firstOrNull()?.id else JSONObject.NULL)
            }
            "delete_record" -> {
                val src = source(args); val rid = id(args)
                val ok = admin.delete(src,rid)
                JSONObject().put("ok",ok).put("history_id", if (ok) admin.journal.recent(1).firstOrNull()?.id else JSONObject.NULL)
            }
            "undo_change" -> {
                val historyId = args.optLong("change_id",0)
                require(historyId > 0) { "无效历史编号" }
                JSONObject().put("ok",admin.undo(historyId))
            }
            else -> JSONObject().put("ok",false).put("error","未授权的工具名称")
        }
    } catch (t: Exception) {
        JSONObject().put("ok",false).put("error", "操作未完成：${t.javaClass.simpleName}")
    }
  }
}
