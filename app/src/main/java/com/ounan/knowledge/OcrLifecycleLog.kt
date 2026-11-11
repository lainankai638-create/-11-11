package com.ounan.knowledge

import android.content.Context
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only diagnostic events. No screenshots, OCR text, or contact/chat names. */
object OcrLifecycleLog {
    private const val STORE = "ocr_startup_diagnostics"
    private const val MAX_EVENTS = 32

    @Synchronized fun reset(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .remove("events").commit()
    }

    @Synchronized fun note(context: Context, message: String) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
        val line = "$time | PID=${Process.myPid()} | " + message.take(120)
        val old = prefs.getString("events", "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }.toList()
        val updated = (old + line).takeLast(MAX_EVENTS).joinToString("\n")
        // commit rather than apply: retain last known event when the process is killed.
        prefs.edit().putString("events", updated).commit()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString("events", "").orEmpty().ifBlank { "暂无服务事件" }
}
