package com.reelect.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown in the app's Diagnostics section (helps tune detection). */
object DebugLog {
    private val buf = ArrayDeque<String>()
    @Synchronized fun add(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        buf.addFirst("$t  $s")
        while (buf.size > 40) buf.removeLast()
    }
    @Synchronized fun dump(): String = buf.joinToString("\n")
    @Synchronized fun clear() = buf.clear()
}
