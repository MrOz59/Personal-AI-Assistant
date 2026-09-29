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

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingOnDone: (() -> Unit)? = null
    private var ready = false
    // A synthesis to file in progress ([synthesize]), told whether it worked.
    @Volatile private var pendingSynth: ((Boolean) -> Unit)? = null
    // An aside ("Let me check.") still being said: a reply that comes meanwhile waits for it.
    @Volatile private var asideTalking = false
    // The language her voice was picked for: picked again when it's changed in Settings.
    @Volatile private var voiceLanguage: Language? = null

    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) { ready = true; matchLanguage() }
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

    /** Picks her voice again if the language she speaks has changed since it was picked. */
    private fun matchLanguage() {
        val language = Language.current(appContext)
        if (language != voiceLanguage) pickVoice(language)
    }

    private fun pickVoice(language: Language) {
        voiceLanguage = language
        val portuguese = language == Language.PORTUGUESE
        // Without a female voice to pick, the engine's own voice for Indian English or Brazilian Portuguese.
        val fallback = if (portuguese) Locale("pt", "BR") else Locale("en", "IN")
        val voices = tts.voices ?: run {
            tts.language = fallback
            return
        }
        // Prefer: offline, in her language, female label, highest quality.
        // English: en-IN > en-US > en-GB > any English. Portuguese: pt-BR > any Portuguese.
        val countryBonus = if (portuguese) mapOf("BR" to 200) else mapOf("IN" to 200, "US" to 100, "GB" to 50)
        fun score(v: Voice): Int {
            if (v.isNetworkConnectionRequired) return -1
            if (v.locale.language != fallback.language) return -1
            val n = v.name.lowercase(Locale.getDefault())
            val isFemale = n.contains("female") || n.contains("-f-") || n.contains("_f_") ||
                n.contains("f-local") || n.contains("sfg") || n.contains("sfc")
            if (!isFemale) return -1
            return v.quality + (countryBonus[v.locale.country.uppercase()] ?: 0)
        }
        val best = voices.maxByOrNull { score(it) }?.takeIf { score(it) >= 0 }
        if (best != null) {
            tts.voice = best
            android.util.Log.d("Naomi", "TTS voice: ${best.name} quality=${best.quality}")
        } else {
            val result = tts.setLanguage(fallback)
            android.util.Log.d("Naomi", "TTS: no female voice found, using the $fallback locale (result $result)")
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) { onDone?.let { mainHandler.post(it) }; return }
        matchLanguage()
        pendingOnDone = onDone
        tts.speak(text, if (asideTalking) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "naomi-utterance")
    }

    /**
     * A short line said while she works on an answer ("Let me check."). The next [speak] follows
     * it instead of cutting it off, and its onDone waits for both.
     */
    fun aside(text: String) {
        if (!ready || text.isBlank()) return
        matchLanguage()
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
        matchLanguage()
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
