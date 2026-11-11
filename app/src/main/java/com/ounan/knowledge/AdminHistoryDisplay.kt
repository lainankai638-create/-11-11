package com.ounan.knowledge

/** Display-only formatting: uses the journal's already-encrypted old/new snapshots.
 * No SQLite migration and no plaintext label/preview persisted in the journal.
 */
internal object AdminHistoryDisplay {
    private fun oneLine(value: String): String =
        value.replace(Regex("[\r\n\t]+"), " ").replace(Regex(" {2,}"), " ").trim()

    private fun safeTitle(record: AdminRecord?): String? =
        record?.title?.let(::oneLine)?.takeIf { it.isNotBlank() }?.take(70)

    fun title(change: AdminChangeJournal.Change): String {
        val from = safeTitle(change.previous)
        val to = safeTitle(change.next)
        return when {
            change.action == "edit" && from != null && to != null && from != to -> "$from → $to"
            change.action == "delete" && from != null -> from
            to != null -> to
            from != null -> from
            else -> "标题暂无记录（请核对详情和 ID）"
        }
    }

    private fun short(value: String, limit: Int = 105): String {
        val line = oneLine(value)
        return if (line.length > limit) line.take(limit) + "…" else line
    }

    fun summary(change: AdminChangeJournal.Change): String = when(change.action) {
        "create" -> "新建正文：" + (change.next?.body?.let(::short) ?: "原文快照尚未写入")
        "delete" -> "删除前正文：" + (change.previous?.body?.let(::short) ?: "原文快照不可用")
        "edit" -> {
            val before = change.previous?.body
            val after = change.next?.body
            when {
                before == null || after == null -> "编辑快照不完整，可在详情中核对"
                before == after -> "正文未改动（可能修改了标题、标签或备注）"
                else -> "修改前：${short(before, 68)}\n修改后：${short(after, 68)}"
            }
        }
        else -> "请在详情中核对历史记录"
    }

    fun details(change: AdminChangeJournal.Change): String {
        val op = when(change.action) { "create" -> "新建"; "edit" -> "修改"; "delete" -> "删除"; else -> "操作" }
        fun section(header: String, record: AdminRecord?): String {
            if (record == null) return "$header：无快照（新建前/删除后可能正常）"
            val body = record.body.let { if (it.length > 20000) it.take(20000) + "\n……正文超过 20000 字，仅显示前部" else it }
            return "$header\n标题：${record.title}\n标签：${record.tags.ifBlank { "无" }}\n正文：\n$body"
        }
        return "操作：$op\n来源：${LocalKnowledgeAdmin.sourceLabel(change.source)}\n记录编号：${change.recordId}\n" +
            "\n${section("【操作前】",change.previous)}\n\n${section("【操作后】",change.next)}"
    }
}
