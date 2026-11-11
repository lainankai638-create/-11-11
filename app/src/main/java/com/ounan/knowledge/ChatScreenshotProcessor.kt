package com.ounan.knowledge

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text

/**
 * Screenshot-only recognizer. OCR lines are classified individually: an ML Kit textBlock
 * may include multiple messages, so classifying whole blocks drops real conversations.
 * All decisions remain on the phone. Low-confidence results must be reviewed before saving.
 */
internal object ChatScreenshotProcessor {
    private fun pixel(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(
        x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)
    )
    private fun green(p: Int): Boolean {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        return g >= 110 && g - r >= 24 && g - b >= 25
    }
    private fun white(p: Int, background: Int): Boolean {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        val bg = (Color.red(background) + Color.green(background) + Color.blue(background)) / 3
        return minOf(r, g, b) >= 244 && (r + g + b) / 3 - bg >= 9
    }
    private fun bgColor(bitmap: Bitmap, top: Int, bottom: Int): Int {
        // Sample OUTER gutters: the image midpoint can be covered by a green/white bubble.
        val hist = HashMap<Int, Int>()
        for (y in top until bottom step maxOf(10, (bottom - top) / 45)) {
            for (f in floatArrayOf(.008f, .018f, .982f, .992f)) {
                val p = pixel(bitmap, (bitmap.width * f).toInt(), y)
                val q = ((Color.red(p) / 8) shl 16) or ((Color.green(p) / 8) shl 8) or (Color.blue(p) / 8)
                hist[q] = (hist[q] ?: 0) + 1
            }
        }
        val q = hist.maxByOrNull { it.value }?.key ?: 0x1D1D1D
        return Color.rgb(minOf(255, ((q shr 16) and 255) * 8 + 4),
            minOf(255, ((q shr 8) and 255) * 8 + 4), minOf(255, (q and 255) * 8 + 4))
    }
    private data class LineRecord(val box: Rect, val text: String, val side: String)
    private data class BubbleGroup(val side: String, val lines: MutableList<String>, var lastRect: Rect)

    private fun detectedSide(bitmap: Bitmap, rect: Rect, background: Int): String? {
        val w = bitmap.width
        val inset = maxOf(8, w / 90)
        val ys = intArrayOf(rect.centerY(), (rect.top + rect.centerY()) / 2,
            (rect.bottom + rect.centerY()) / 2)
        // OCR bounds wrap glyph ink, not the colored bubble. Look just outside both text edges.
        val nearLeft = ys.map { pixel(bitmap, rect.left - inset, it) }
        val nearRight = ys.map { pixel(bitmap, rect.right + inset, it) }
        val greenVotes = (nearLeft + nearRight).count(::green)
        val whiteVotes = (nearLeft + nearRight).count { white(it, background) }
        return when {
            greenVotes >= 2 && rect.right >= w * .48f -> "R"
            whiteVotes >= 2 && rect.left <= w * .37f -> "L"
            else -> null
        }
    }

    private fun guessedSide(rect: Rect, width: Int, raw: String): String? {
        // Last-resort preview, never treated as verified. Reject centered timestamps and labels.
        if (raw.length <= 2 && raw.all { it.isLetter() && it.code < 128 }) return null
        return when {
            rect.left <= width * .33f -> "L"
            rect.left >= width * .32f && rect.right >= width * .60f -> "R"
            else -> null
        }
    }

    private fun group(lines: List<LineRecord>): List<ChatScreenshotStitcher.Bubble> {
        val grouped = mutableListOf<BubbleGroup>()
        for (line in lines.sortedWith(compareBy<LineRecord> { it.box.top }.thenBy { it.box.left })) {
            val prev = grouped.lastOrNull()
            val gap = if (prev == null) Int.MAX_VALUE else line.box.top - prev.lastRect.bottom
            val limit = minOf(48, maxOf(20, (minOf(line.box.height(), prev?.lastRect?.height() ?: 0) * .9f).toInt() + 8))
            // Adjacent lines of one bubble tend to have only a small vertical gap.
            val same = prev != null && prev.side == line.side && gap in -6..limit
            if (same && prev != null) {
                prev.lines.add(line.text)
                prev.lastRect = line.box
            } else grouped.add(BubbleGroup(line.side, mutableListOf(line.text), line.box))
        }
        return grouped.map { ChatScreenshotStitcher.Bubble(it.side, it.lines.joinToString("\n")) }
    }

    fun extract(bitmap: Bitmap, ocr: Text, topPercent: Int = 8,
        bottomPercent: Int = 11, strict: Boolean = true): ChatScreenshotStitcher.Page {
        val w = bitmap.width
        val h = bitmap.height
        val top = h * topPercent.coerceIn(0, 35) / 100
        val bottom = h * (100 - bottomPercent.coerceIn(0, 35)) / 100
        val bg = bgColor(bitmap, top, bottom)
        val high = mutableListOf<LineRecord>()
        val lower = mutableListOf<LineRecord>()
        var omitted = 0
        var seen = 0
        // IMPORTANT: TextBlock can merge separate messages. Classify *lines*, not blocks.
        for (block in ocr.textBlocks) for (line in block.lines) {
            val rect = line.boundingBox ?: continue
            val raw = line.text.trim().replace(Regex("[ \t]+"), " ")
            if (raw.isBlank()) continue
            seen++
            if (rect.top < top || rect.bottom > bottom || rect.left < 0 || rect.right > w ||
                raw.length > 1200 || rect.width() < w * .018f) { omitted++; continue }
            val side = detectedSide(bitmap, rect, bg)
            if (side != null) {
                high.add(LineRecord(rect, raw, side))
            } else {
                val guess = guessedSide(rect, w, raw)
                if (guess == null) omitted++ else lower.add(LineRecord(rect, raw, guess))
            }
        }
        // In strict mode, never replace zero results with a hard error if OCR did read text.
        // Let the user inspect an explicitly labeled low-confidence draft instead.
        val fallback = high.isEmpty() && lower.isNotEmpty()
        val used = if (!strict || fallback) high + lower else high
        return ChatScreenshotStitcher.Page(group(used), omitted + (if (strict && !fallback) lower.size else 0),
            seenLines = seen, usedFallback = fallback, uncertainLines = if (!strict || fallback) lower.size else 0)
    }
}
