package com.ounan.knowledge

/** A single forward-scrolling user-visible conversation capture session.
 * Operates on OCR LINES, not verified WeChat message objects. Never claims
 * to know missing times, authors, offscreen messages, or media contents.
 */
internal class ChatPageStitcher {
    data class Delta(val append: List<String>, val overlap: Int, val gap: Boolean, val unchanged: Boolean)
    private var lastPage = emptyList<String>()
    var totalLines: Int = 0
        private set
    var possibleGaps: Int = 0
        private set
    fun reset() { lastPage = emptyList(); totalLines = 0; possibleGaps = 0 }
    private fun key(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    fun accept(raw: List<String>): Delta {
        // Exact repetition is legitimate if it occurs at different positions.
        val fresh = raw.map { it.trim() }.filter { it.isNotEmpty() }.take(250)
        if (fresh.isEmpty()) return Delta(emptyList(), 0, false, true)
        val now = fresh.map(::key)
        val old = lastPage.map(::key)
        if (old.isEmpty()) {
            lastPage = fresh
            totalLines += fresh.size
            return Delta(fresh, 0, false, false)
        }
        if (now == old || containsSequence(old, now)) {
            // A stationary or flickering page must not duplicate its content.
            return Delta(emptyList(), now.size.coerceAtMost(old.size), false, true)
        }
        var overlap = 0
        for (length in minOf(old.size, now.size) downTo 1) {
            if (old.takeLast(length) == now.take(length)) { overlap = length; break }
        }
        if (overlap == 0 && isMostlySame(old, now)) {
            // A small OCR variation in a stationary page should not invent new messages.
            return Delta(emptyList(), 0, false, true)
        }
        // Forward scrolling. If pages have no overlap, never invent missing lines.
        val gap = overlap == 0
        if (gap) possibleGaps++
        val delta = fresh.drop(overlap)
        lastPage = fresh
        totalLines += delta.size
        return Delta(delta, overlap, gap, delta.isEmpty())
    }
    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..(haystack.size-needle.size)).any { haystack.subList(it, it + needle.size) == needle }
    }
    private fun isMostlySame(old: List<String>, now: List<String>): Boolean {
        // OCR may transiently miss one line in an otherwise stationary frame.
        if (old.size < 4 || now.size < 4) return false
        val common = now.toSet().intersect(old.toSet()).size
        return common >= 4 && common.toDouble() / maxOf(now.size, old.size) >= 0.80
    }
}
