package com.ounan.knowledge

/** Reconstructs visible speech bubbles from user-ordered screenshots.
 * Not a WeChat database reader. It never invents missing bubbles or timestamps.
 */
internal object ChatScreenshotStitcher {
    data class Bubble(val side: String, val text: String)
    data class Page(
        val bubbles: List<Bubble>, val omitted: Int = 0,
        val seenLines: Int = 0, val usedFallback: Boolean = false, val uncertainLines: Int = 0
    )
    data class Result(
        val text: String, val messages: Int, val overlaps: Int,
        val gaps: List<Int>, val blankPages: List<Int>, val omitted: Int
    )
    private fun normalized(s: String): String = s.replace(Regex("\\s+"), "").trim()

    private fun similar(a: Bubble, b: Bubble): Boolean {
        if (a.side != b.side) return false
        val x = normalized(a.text)
        val y = normalized(b.text)
        if (x == y) return true
        // Only tolerate uncertain OCR changes in long phrases, not short repeated replies.
        if (x.length < 10 || y.length < 10 || kotlin.math.abs(x.length - y.length) > 3) return false
        val commonPrefix = x.commonPrefixWith(y).length
        val commonSuffix = x.commonSuffixWith(y).length
        return commonPrefix + commonSuffix >= maxOf(x.length, y.length) - 2
    }

    fun merge(pages: List<Page>): Result {
        val merged = mutableListOf<Bubble>()
        val gaps = mutableListOf<Int>()
        val blanks = mutableListOf<Int>()
        var totalOverlap = 0
        var removed = 0
        for ((index, page) in pages.withIndex()) {
            val bubbles = page.bubbles.filter { it.text.isNotBlank() }
            removed += page.omitted
            if (bubbles.isEmpty()) { blanks.add(index + 1); continue }
            if (merged.isEmpty()) { merged.addAll(bubbles); continue }
            var overlap = 0
            for (n in minOf(merged.size, bubbles.size, 20) downTo 1) {
                if ((0 until n).all { i -> similar(merged[merged.size - n + i], bubbles[i]) }) {
                    // A single short repeated reply could be a genuinely new message.
                    if (n > 1 || normalized(bubbles[0].text).length >= 10) { overlap = n; break }
                }
            }
            if (overlap == 0) gaps.add(index + 1)
            totalOverlap += overlap
            merged.addAll(bubbles.drop(overlap))
        }
        val output = buildString {
            append("欧南 · 聊天截图整理稿（需人工核对）\n")
            append("说明：根据截图位置识别左右聊天气泡；左右归属、发送时间未经验证。\n")
            append("截图重叠处进行保守去重，未发现重叠不代表一定缺页。\n\n")
            for (b in merged) {
                append(if (b.side == "R") "【右侧】" else "【左侧】")
                append(b.text.trim()).append("\n\n")
            }
            if (gaps.isNotEmpty() || blanks.isNotEmpty() || removed > 0) {
                append("【核对提示】\n")
                if (gaps.isNotEmpty()) append("以下截图与上一页未检测到重叠，请核对顺序与连续性：").append(gaps.joinToString("、")).append("\n")
                if (blanks.isNotEmpty()) append("以下截图没有识别到合格聊天气泡：").append(blanks.joinToString("、")).append("\n")
                if (removed > 0) append("有 ").append(removed).append(" 个文字区块因不在聊天气泡内或位于边界外而被排除。\n")
            }
        }.trim()
        return Result(output, merged.size, totalOverlap, gaps, blanks, removed)
    }
}
