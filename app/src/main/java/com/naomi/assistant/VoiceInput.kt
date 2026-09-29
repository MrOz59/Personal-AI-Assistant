package com.naomi.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Wraps Android's built-in SpeechRecognizer — Naomi's "ears".
 *
 * Free, on-device-capable (works offline if an offline language pack is installed),
 * and needs no API key.
 *
 * Important: we keep ONE recognizer alive for the whole app and just reset it between
 * turns. Destroying + recreating it on every tap caused intermittent "server disconnected"
 * (error 11) on the first tap after a turn, because the old instance was still tearing down.
 *
 * Who's talking: where the phone's speech service allows it (Android 13+ EXTRA_AUDIO_SOURCE),
 * we capture the mic ourselves and feed the recognizer through a pipe, keeping a copy of each
 * utterance so Naomi can tell whose voice it was. Whether the service really reads our audio is
 * checked on first use and remembered — if it doesn't, it gets its own mic and no copy is kept.
 *
 * Later upgrade: swap this for Vosk (the engine Dicio uses) for guaranteed offline STT.
 */
class VoiceInput(context: Context) {

    private var onResult: ((String, ShortArray?) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var triedFallback = false
    private var triedClientRetry = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("naomi", Context.MODE_PRIVATE)
    private var capture: Capture? = null

    /** Fires when the user actually starts talking — used to cut off Naomi's TTS (barge-in). */
    var onSpeechStart: (() -> Unit)? = null

    /** Whether the speech service reads the audio we hand it: true, false, or null (not yet known). */
    val speakerCheckSupported: Boolean?
        get() = if (Build.VERSION.SDK_INT < 33) false
                else if (prefs.contains(PREF_OWN_AUDIO)) prefs.getBoolean(PREF_OWN_AUDIO, false) else null

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            val audio = endCapture()
            if (text.isNullOrBlank()) onError?.invoke("I didn't catch that.")
            else onResult?.invoke(text, audio)
        }

        override fun onError(error: Int) {
            endCapture()
            // ERROR_CLIENT (5): mic not fully released yet — retry after a short pause.
            if (error == SpeechRecognizer.ERROR_CLIENT && !triedClientRetry) {
                triedClientRetry = true
                recognizer?.cancel()
                handler.postDelayed({ start(useIndianEnglish = true) }, 350)
                return
            }
            // If Indian English isn't installed (12/13), retry once in device default language.
            if (error in intArrayOf(12, 13) && !triedFallback) {
                triedFallback = true
                recognizer?.cancel()
                start(useIndianEnglish = false)
                return
            }
            onError?.invoke(describeError(error))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { onSpeechStart?.invoke() }

        // Unused callbacks
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        else null

    private fun buildIntent(useIndianEnglish: Boolean, source: ParcelFileDescriptor?) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Prefer Indian English for accent accuracy; fall back to device default if absent.
            if (useIndianEnglish) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            }
            if (source != null && Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            }
        }

    /**
     * Start listening. [onResult] fires with the recognised text and — when we could capture it
     * ourselves — the utterance's audio; [onError] with a message. Both on the main thread.
     */
    fun listen(onResult: (String, ShortArray?) -> Unit, onError: (String) -> Unit) {
        if (recognizer == null) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        this.onResult = onResult
        this.onError = onError
        triedFallback = false
        triedClientRetry = false
        // Reset any lingering state from the previous turn, then start fresh.
        recognizer.cancel()
        endCapture()
        start(useIndianEnglish = true)
    }

    /** Starts recognition — on our own captured audio where the speech service takes it. */
    private fun start(useIndianEnglish: Boolean) {
        val rec = recognizer ?: return
        val own = if (speakerCheckSupported != false) {
            try {
                Capture()
            } catch (e: Exception) {
                android.util.Log.w("Naomi", "Own audio capture unavailable: ${e.message}")
                null
            }
        } else null
        capture = own
        rec.startListening(buildIntent(useIndianEnglish, own?.source))
        if (own != null) handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * Learns whether the speech service reads our pipe: once more than a pipe's worth of audio
     * has gone in, it's being read; if the pipe filled and our write has been stuck, it isn't —
     * then recognition restarts on the service's own mic, for this turn and every one after.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val c = capture ?: return
            when {
                c.written > PIPE_BYTES -> {
                    if (speakerCheckSupported != true) prefs.edit().putBoolean(PREF_OWN_AUDIO, true).apply()
                }
                c.stuckFor() > STUCK_MS -> {
                    android.util.Log.w("Naomi", "Speech service ignores our audio — using its own mic from now on")
                    prefs.edit().putBoolean(PREF_OWN_AUDIO, false).apply()
                    recognizer?.cancel()
                    endCapture()
                    start(useIndianEnglish = !triedFallback)
                }
                else -> handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    /** Stops our capture, if any, and hands back the audio it kept. */
    private fun endCapture(): ShortArray? {
        handler.removeCallbacks(watchdog)
        val c = capture ?: return null
        capture = null
        return c.finish()
    }

    /** Stop any in-flight recognition and drop callbacks so a late result can't fire after a reset. */
    fun cancel() {
        onResult = null
        onError = null
        recognizer?.cancel()
        endCapture()
    }

    fun destroy() {
        endCapture()
        recognizer?.destroy()
    }

    /**
     * Our own mic capture: written into a pipe the recognizer reads, with a copy kept (up to
     * [MAX_KEEP_SECONDS]) for the speaker check.
     */
    @SuppressLint("MissingPermission") // listen() is only used once RECORD_AUDIO is granted
    private class Capture {
        private val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE)
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("mic busy") }
        }

        private val pipe = ParcelFileDescriptor.createPipe()
        /** The end the recognizer reads. */
        val source: ParcelFileDescriptor get() = pipe[0]

        private val kept = ShortArray(SAMPLE_RATE * MAX_KEEP_SECONDS)
        private var keptLength = 0
        @Volatile private var running = true
        @Volatile private var writeStartedAt = 0L
        /** Bytes the recognizer has taken off our hands (or the pipe holds). */
        @Volatile var written = 0L
            private set

        private val worker = thread(name = "naomi-ears") {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val bytes = ByteArray(buf.size * 2)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(kept) {
                        val take = minOf(n, kept.size - keptLength)
                        System.arraycopy(buf, 0, kept, keptLength, take)
                        keptLength += take
                    }
                    for (i in 0 until n) {
                        bytes[2 * i] = buf[i].toByte()                       // little-endian PCM
                        bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                    }
                    writeStartedAt = SystemClock.elapsedRealtime()
                    out.write(bytes, 0, n * 2)
                    writeStartedAt = 0L
                    written += n * 2
                }
            } catch (e: IOException) {
                // The pipe was closed — recognition is over.
            } catch (e: Exception) {
                android.util.Log.w("Naomi", "Own audio capture stopped: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { out.close() }
            }
        }

        /** How long our current write has been stuck on a full pipe (0 if not writing). */
        fun stuckFor(): Long = writeStartedAt.let { if (it == 0L) 0L else SystemClock.elapsedRealtime() - it }

        /** Stops capturing and returns the audio kept so far. */
        fun finish(): ShortArray {
            running = false
            runCatching { pipe[0].close() } // unblocks a write stuck on a full pipe
            worker.join(500)
            return synchronized(kept) { kept.copyOf(keptLength) }
        }
    }

    private companion object {
        // Change to your preferred BCP-47 tag, e.g. "en-US", "hi-IN" for Hindi.
        const val LANGUAGE = "en-IN"

        const val SAMPLE_RATE = 16000
        const val MAX_KEEP_SECONDS = 15
        // A Linux pipe holds 64 KiB — 2 s of audio. More than that written means it's being read.
        const val PIPE_BYTES = 65_536L
        const val STUCK_MS = 1_000L
        const val WATCHDOG_MS = 250L
        const val PREF_OWN_AUDIO = "own_audio_capture"
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't understand that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during recognition."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        11 -> "One sec — try the mic again." // 11 = SERVER_DISCONNECTED
        12, 13 -> "My speech language pack isn't available — your phone may be low on storage."
        else -> "Speech recognition error ($code)."
    }
}
