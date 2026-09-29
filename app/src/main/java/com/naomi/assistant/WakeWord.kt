package com.naomi.assistant

import org.json.JSONException
import org.json.JSONObject

// Reading "Naomi" out of Vosk's results: which word it was, how sure Vosk was, and where it sits
// in the audio. Kept apart from WakeService so it can be tested on the JVM.

/** The wake phrases in Vosk's grammar, as whole utterances. */
internal val WAKE_PHRASES = setOf(
    "naomi", "hey naomi", "hi naomi", "hello naomi",
    "good morning naomi", "good night naomi", "okay naomi"
)

/**
 * "Naomi" as Vosk heard it in one utterance: how sure it was of the word, where the word sits
 * (seconds from the utterance's start), and whether the utterance was a wake phrase and nothing
 * else — as it is in a quiet place. Not [clean]: other sounds came with it (street noise,
 * someone talking, or the command said straight after), which Vosk reports as "[unk]".
 */
internal data class WakeHit(val conf: Double, val start: Double, val end: Double, val clean: Boolean) {
    val duration: Double get() = end - start
}

/** The last "naomi" in a Vosk result, or null if it has none (or no word details). */
internal fun findWake(result: JSONObject): WakeHit? {
    val words = result.optJSONArray("result") ?: return null
    val naomi = (0 until words.length()).mapNotNull { words.optJSONObject(it) }
        .lastOrNull { it.optString("word") == "naomi" } ?: return null
    return WakeHit(
        conf = naomi.optDouble("conf", 0.0),
        start = naomi.optDouble("start", Double.NaN),
        end = naomi.optDouble("end", Double.NaN),
        clean = result.optString("text").trim().lowercase() in WAKE_PHRASES,
    )
}

/** Whether Vosk's running guess — {"partial": "…"} — has "naomi" in it yet. */
internal fun mentionsWake(partial: String): Boolean = try {
    JSONObject(partial).optString("partial").split(' ').contains("naomi")
} catch (e: JSONException) {
    false
}

/**
 * Where [hit] sits among the newest [available] samples, as indices into them, with [margin]
 * samples either side where they're still there — or null if the word itself has left them, or
 * its timing is unknown. Vosk times words from the start of their utterance (on Android its
 * clock restarts with each one), which began at sample [utteranceStart] of the [fed] so far.
 */
internal fun wakeWindow(hit: WakeHit, utteranceStart: Long, fed: Long, available: Int, margin: Int, sampleRate: Int): IntRange? {
    if (hit.start.isNaN() || hit.end.isNaN() || hit.end <= hit.start) return null
    val oldest = fed - available
    val wordFrom = utteranceStart + (hit.start * sampleRate).toLong() - oldest
    val wordTo = utteranceStart + (hit.end * sampleRate).toLong() - oldest
    if (wordFrom < 0 || wordTo > available) return null
    return maxOf(0L, wordFrom - margin).toInt() until minOf(available.toLong(), wordTo + margin).toInt()
}
