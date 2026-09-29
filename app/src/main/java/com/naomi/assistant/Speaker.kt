package com.naomi.assistant

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * Wraps Android's built-in TextToSpeech — Naomi's "mouth".
 * Zero dependencies, works offline once the system voice data is installed.
 *
 * Supports an [onDone] callback (delivered on the main thread) so the app can, e.g.,
 * start listening again right after Naomi finishes asking a question.
 */
class Speaker(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingOnDone: (() -> Unit)? = null
    private var ready = false
    // A synthesis to file in progress ([synthesize]), told whether it worked.
    @Volatile private var pendingSynth: ((Boolean) -> Unit)? = null
    // An aside ("Let me check.") still being said: a reply that comes meanwhile waits for it.
    @Volatile private var asideTalking = false

    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) { ready = true; pickVoice() }
    }.also {
        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId != SYNTH_ID) speaking = true }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == SYNTH_ID) { synthesized(true); return }
                speaking = false
                // A reply queued behind an aside has its own end to wait for.
                if (utteranceId == ASIDE_ID) { asideTalking = false; return }
                pendingOnDone?.let { cb -> pendingOnDone = null; mainHandler.post(cb) }
            }
            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) {
                if (utteranceId == SYNTH_ID) { synthesized(false); return }
                speaking = false
                if (utteranceId == ASIDE_ID) { asideTalking = false; return }
                pendingOnDone = null
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == SYNTH_ID) { synthesized(false); return }
                speaking = false
                if (utteranceId == ASIDE_ID) { asideTalking = false; return }
                pendingOnDone = null
            }
        })
    }

    private fun pickVoice() {
        val voices = tts.voices ?: run {
            tts.language = Locale("en", "IN")
            return
        }
        // Prefer: offline, English, female label, highest quality. en-IN > en-US > any English.
        fun score(v: Voice): Int {
            if (v.isNetworkConnectionRequired) return -1
            if (v.locale.language != "en") return -1
            val n = v.name.lowercase(Locale.getDefault())
            val isFemale = n.contains("female") || n.contains("-f-") || n.contains("_f_") ||
                n.contains("f-local") || n.contains("sfg") || n.contains("sfc")
            if (!isFemale) return -1
            val localeBonus = when (v.locale.country.uppercase()) {
                "IN" -> 200
                "US" -> 100
                "GB" -> 50
                else -> 0
            }
            return v.quality + localeBonus
        }
        val best = voices.maxByOrNull { score(it) }?.takeIf { score(it) >= 0 }
        if (best != null) {
            tts.voice = best
            android.util.Log.d("Naomi", "TTS voice: ${best.name} quality=${best.quality}")
        } else {
            tts.language = Locale("en", "IN")
            android.util.Log.d("Naomi", "TTS: no female voice found, using en-IN locale")
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) { onDone?.let { mainHandler.post(it) }; return }
        pendingOnDone = onDone
        tts.speak(text, if (asideTalking) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "naomi-utterance")
    }

    /**
     * A short line said while she works on an answer ("Let me check."). The next [speak] follows
     * it instead of cutting it off, and its onDone waits for both.
     */
    fun aside(text: String) {
        if (!ready || text.isBlank()) return
        asideTalking = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, ASIDE_ID)
    }

    /**
     * Debug: [text] in her voice, as 16 kHz PCM for [onAudio] (main thread; null if it failed) —
     * speech to test the recognizer with when nobody's there to talk.
     */
    fun synthesize(text: String, file: File, onAudio: (ShortArray?) -> Unit, waited: Int = 0) {
        // The voice takes a moment to load when the app has just opened.
        if (!ready) {
            if (waited >= 20) mainHandler.post { onAudio(null) }
            else mainHandler.postDelayed({ synthesize(text, file, onAudio, waited + 1) }, 250)
            return
        }
        pendingSynth = { ok ->
            val audio = if (ok) VoiceDebug.readWav16k(file) else null
            mainHandler.post { onAudio(audio) }
        }
        tts.synthesizeToFile(text, Bundle(), file, SYNTH_ID)
    }

    private fun synthesized(ok: Boolean) {
        pendingSynth?.let { done -> pendingSynth = null; done(ok) }
    }

    fun stop() {
        pendingOnDone = null
        speaking = false
        asideTalking = false
        tts.stop()
    }

    fun shutdown() {
        speaking = false
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val SYNTH_ID = "naomi-synth"
        private const val ASIDE_ID = "naomi-aside"

        /** True while any Naomi voice is talking — lets the wake listener tell her own voice,
         *  leaking back into the mic, from a stranger's. */
        @Volatile var speaking = false
    }
}
