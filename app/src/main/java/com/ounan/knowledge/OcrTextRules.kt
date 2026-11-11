package com.ounan.knowledge

import java.util.Locale

/** Pure text rules. OCR/Accessibility titles are human-readable hints, not verified identities. */
internal object OcrTextRules {
    fun titleKey(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("[\\s()（）·•【】\\[\\]_-]+"), "")

    fun titleMatches(entered: String, header: String): Boolean {
        val key = titleKey(entered)
        return key.length >= 2 && titleKey(header).contains(key)
    }

    fun dedupeKey(side: String, body: String): String = side + "|" +
        body.trim().replace(Regex("\\s+"), " ")
}
