package com.naomi.assistant

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, persistent trail of what the voice pipeline did: each "Naomi" heard or passed over,
 * each voice check and its score, each sentence answered or ignored, and recognizer errors.
 * Settings shows it, so a failure out on the street can be diagnosed from a screenshot — the
 * phone says which step dropped it, no cable needed.
 */
object VoiceLog {
    private const val FILE = "voice_log.txt"
    // Enough for a whole outing's worth of attempts, small enough to read on one screen.
    private const val KEEP = 60

    private val lines = ArrayDeque<String>()
    private var loaded = false

    /** Called, on whichever thread logged, after each change — so an open Settings screen refreshes. */
    @Volatile var onChange: (() -> Unit)? = null

    fun add(context: Context, event: String) {
        synchronized(this) {
            load(context)
            lines.addLast(SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date()) + "  " + event)
            while (lines.size > KEEP) lines.removeFirst()
            runCatching { File(context.filesDir, FILE).writeText(lines.joinToString("\n")) }
        }
        android.util.Log.i("Naomi", "voice log: $event")
        onChange?.invoke()
    }

    /** The entries, newest first. */
    fun recent(context: Context): List<String> = synchronized(this) {
        load(context)
        lines.reversed()
    }

    fun clear(context: Context) {
        synchronized(this) {
            lines.clear()
            File(context.filesDir, FILE).delete()
        }
        onChange?.invoke()
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            File(context.filesDir, FILE).takeIf { it.exists() }?.readLines()?.takeLast(KEEP)?.let { lines.addAll(it) }
        }
    }
}
