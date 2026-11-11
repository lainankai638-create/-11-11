package com.ounan.knowledge

/** Offline, deterministic READ-ONLY display rendering.
 * The original captured plaintext remains sealed in KnowledgeDb / WeChatArchive.
 * This is NOT model reasoning, fact verification, or a guarantee of complete capture. */
object DocumentFormatter {
    private val uiLabels = setOf("返回", "分享", "点赞", "收藏", "转发", "评论", "关注", "取消关注",
        "已关注", "首页", "我的", "下一页", "上一页", "举报", "关闭", "更多", "发布", "回复",
        "展开", "收起", "复制", "取消", "确定", "发送", "查看", "推荐", "热门")
    private val listPrefix = Regex("^([一二三四五六七八九十]+[、.]|[0-9]{1,2}[.、)]|[-•*] )")
    private val sentenceEnd = Regex("[。！？!?；;：:…]|[.][”’』」）)]?$")

    private fun clean(line: String): String = line.replace('\u00a0', ' ')
        .replace('\u200b'.toString(), "").replace(Regex("[ \\t]+"), " ").trim()
    private fun endsSentence(line: String): Boolean = line.endsWith('。') || line.endsWith('！') ||
        line.endsWith('？') || line.endsWith('；') || line.endsWith('!') || line.endsWith('?') ||
        line.endsWith('…') || line.endsWith('：') || line.endsWith('”') || line.endsWith('」')
    private fun isChrome(line: String): Boolean {
        val s = line.replace(Regex("[\u0020\u00a0]+"), "").trim()
        return s in uiLabels || (s.length <= 8 && Regex("^[0-9]+[人次条个]?(赞|评论|转发|浏览|关注)$").matches(s))
    }
    private fun isMeta(line: String): Boolean = listOf("采集于：", "保存于：", "会话结束原因：", "注意：", "欧南采集蜘蛛", "欧南微信 OCR 蜘蛛").any { line.startsWith(it) }

    private fun distinctLines(raw: String): List<String> {
        val out = ArrayList<String>()
        var prev = ""
        // Deduplicate only consecutive exact repeats: non-adjacent repeats may be meaningful quotations.
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val s = clean(line)
            if (s.isNotEmpty() && s == prev) continue
            out.add(s)
            prev = s
        }
        return out
    }

    /** Deterministic heuristic that merges short line wraps and groups paragraphs.
     * Original spelling, uncertain attribution and capture chronology are never corrected. */
    fun knowledge(raw: String): String {
        val lines = distinctLines(raw)
        val meta = mutableListOf<String>()
        val chrome = mutableListOf<String>()
        val content = mutableListOf<String>()
        var source = ""
        fun flush(section: MutableList<String>, pending: StringBuilder) {
            if (pending.isNotEmpty()) { section.add(pending.toString().trim()); pending.setLength(0) }
        }
        val paragraph = StringBuilder()
        for (line in lines) {
            if (line.isEmpty()) { flush(content, paragraph); continue }
            if (line.startsWith("## 来源：")) {
                flush(content, paragraph)
                source = line.removePrefix("## ")
                content.add("《SOURCE》" + source)
                continue
            }
            if (isMeta(line)) { flush(content, paragraph); meta.add(line); continue }
            if (isChrome(line)) { flush(content, paragraph); chrome.add(line); continue }
            if (listPrefix.containsMatchIn(line) && listPrefix.find(line)?.range?.first == 0) {
                flush(content, paragraph); content.add(line); continue
            }
            if (line.startsWith("# ") || line.startsWith("## ")) {
                flush(content, paragraph); content.add(line.trimStart('#', ' ')); continue
            }
            if (paragraph.isNotEmpty()) {
                // Only connect short, clearly unfinished wraps. Keep complete sentences separate.
                if (endsSentence(paragraph.toString()) || paragraph.length + line.length > 170) flush(content, paragraph)
                else if (!paragraph.last().isWhitespace() && !line.first().isWhitespace() &&
                    (paragraph.last().isLetterOrDigit() && line.first().isLetterOrDigit()) &&
                    !(Character.UnicodeScript.of(paragraph.last().code)==Character.UnicodeScript.HAN &&
                      Character.UnicodeScript.of(line.first().code)==Character.UnicodeScript.HAN)) paragraph.append(' ')
            }
            paragraph.append(line)
            if (endsSentence(line)) flush(content, paragraph)
        }
        flush(content, paragraph)
        // Build readable sections without dropping any original content.
        val result = StringBuilder("欧南 · 知识文档（自动排版）\n")
        result.append("排版规则：本地处理；未验证事实、原始时间或内容完整性。\n")
        if (meta.isNotEmpty()) result.append("\n【采集信息】\n").append(meta.joinToString("\n")).append('\n')
        result.append("\n【正文】\n")
        for (p in content) {
            if (p.startsWith("《SOURCE》")) result.append("\n【").append(p.removePrefix("《SOURCE》")).append("】\n")
            else result.append(p).append("\n\n")
        }
        if (chrome.isNotEmpty()) result.append("【可能的界面元素 · 请核对】\n").append(chrome.joinToString(" · ")).append('\n')
        return result.toString().trim()
    }

    fun weChat(raw: String): String {
        // Do not remove identical adjacent messages: two separate "好的" may be real.
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines().map(::clean)
        val meta = ArrayList<String>()
        val body = ArrayList<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            if (isMeta(line)) meta.add(line)
            else body.add(line)
        }
        return buildString {
            append("欧南 · 微信文字留档（阅读排版）\n")
            append("注意：消息排列为屏幕采集顺序，不保证真实发送时间；左右位置不代表已验证的发送者。\n")
            if (meta.isNotEmpty()) { append("\n【采集信息】\n"); append(meta.joinToString("\n")); append('\n') }
            append("\n【屏幕文字】\n")
            for (line in body) {
                val m = Regex("^\\[(左侧|右侧|中间)·待核对\\] ?(.*)$").matchEntire(line)
                if (m != null) {
                    append('\n').append(m.groupValues[1]).append("（未确认发言人）\n")
                    append(m.groupValues[2]).append('\n')
                } else append(line).append("\n\n")
            }
        }.trim()
    }
}
