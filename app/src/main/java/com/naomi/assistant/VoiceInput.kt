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
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.Locale
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
 * The speech service gets the mic to itself, so sentences can't be voice-checked here. Both ways
 * of keeping a copy were tried on a Redmi (HyperOS, Google's speech service): recording alongside
 * it, Android silenced the service's mic in favour of ours — Naomi is the assistant on screen —
 * and it heard nothing; feeding it our own recording (EXTRA_AUDIO_SOURCE), it heard speech in it
 * and returned no words. The owner's voice is still checked on "Naomi" (WakeService). The piping
 * is kept for tests ([listenToAudio]).
 *
 * Later upgrade: swap this for Vosk (the engine Dicio uses) for guaranteed offline STT.
 */
class VoiceInput(context: Context) {

    /**
     * What was said: the recognizer's best guess, its other guesses when it wasn't sure (for a
     * sentence heard in one stretch), and the audio, when a voice check can have it.
     */
    data class Heard(val text: String, val alternatives: List<String> = emptyList(), val audio: ShortArray? = null)

    private val appContext = context.applicationContext
    // English as spoken where the phone is — the recognizer then knows local names (Woolworths,
    // not "wolves worth") and accents.
    private val language = englishHere(context)
    private var onResult: ((Heard) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var triedFallback = false
    private var triedClientRetry = false
    private val handler = Handler(Looper.getMainLooper())
    private var capture: Capture? = null
    // Debug: audio to recognize instead of the mic's ([listenToAudio]), and a language to use for it.
    private var testAudio: ShortArray? = null
    private var testLanguage: String? = null
    // This recognition's timeline: when it began, and when the service said it was ready and
    // heard speech start (0 = not yet) — logged when something goes wrong.
    private var startedAt = 0L
    private var readyAt = 0L
    private var speechAt = 0L

    // The service can answer one listen in segments — when the speaker pauses mid-sentence, two
    // results a moment apart ("I don't have a car", "so"). They're one sentence: gathered, and
    // passed on together once no more come.
    private val heard = StringBuilder()
    private var segments = 0
    private var alternatives: List<String> = emptyList()
    private val passOn = Runnable {
        val text = heard.toString().trim()
        val others = alternatives
        heard.clear()
        segments = 0
        alternatives = emptyList()
        if (text.isEmpty()) onError?.invoke("I didn't catch that.") else onResult?.invoke(Heard(text, others))
    }

    /**
     * Words to listen out for — names, places, the owner's own vocabulary — handed to the service
     * as a hint (Android 13+; services may ignore it). Set before [listen].
     */
    var vocabulary: List<String> = emptyList()

    /** Fires when the user actually starts talking — used to cut off Naomi's TTS (barge-in). */
    var onSpeechStart: (() -> Unit)? = null

    /** Whether sentences come with their audio, for a voice check: not while the service has the mic to itself. */
    val speakerCheckSupported: Boolean? = false

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val guesses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val text = guesses.firstOrNull()
            val c = capture
            val audio = endCapture()
            if (c != null && audio != null) report(if (text.isNullOrBlank()) "no words" else "heard \"${text.take(40)}\"", audio, c)
            if (!text.isNullOrBlank()) {
                heard.append(if (heard.isEmpty()) "" else " ").append(text)
                // Other guesses only make sense for a sentence heard whole, not stitched from segments.
                segments++
                alternatives = if (segments > 1) emptyList()
                    else guesses.drop(1).filterNot { it.equals(text, ignoreCase = true) }.distinct().take(MAX_ALTERNATIVES)
            }
            handler.removeCallbacks(passOn)
            handler.postDelayed(passOn, SEGMENT_WAIT_MS)
        }

        override fun onError(error: Int) {
            // Whatever came before the error is still what was said.
            if (heard.isNotEmpty()) {
                handler.removeCallbacks(passOn)
                passOn.run()
                return
            }
            val c = capture
            endCapture()?.let { audio -> if (c != null) report("error $error (${describeError(error)})", audio, c) }
            // ERROR_CLIENT (5): mic not fully released yet — retry after a short pause.
            if (error == SpeechRecognizer.ERROR_CLIENT && !triedClientRetry) {
                triedClientRetry = true
                recognizer?.cancel()
                handler.postDelayed({ start(useRegionalEnglish = true) }, 350)
                return
            }
            // If the local English isn't available (12/13), retry once in the device's default language.
            if (error in intArrayOf(12, 13) && !triedFallback) {
                triedFallback = true
                recognizer?.cancel()
                start(useRegionalEnglish = false)
                return
            }
            onError?.invoke(describeError(error))
        }

        override fun onReadyForSpeech(params: Bundle?) { readyAt = SystemClock.elapsedRealtime() }
        override fun onBeginningOfSpeech() {
            speechAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(noSpeech)
            onSpeechStart?.invoke()
        }

        // Fed our own audio, the service only answers once that audio ends, which a mic never
        // does — its end of speech is the cue to stop.
        override fun onEndOfSpeech() { capture?.endInput() }

        // Unused callbacks
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        else null

    private fun buildIntent(useRegionalEnglish: Boolean, source: ParcelFileDescriptor?) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Its other guesses too: the brain picks the one that makes sense in the conversation.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1 + MAX_ALTERNATIVES)
            if (Build.VERSION.SDK_INT >= 33 && vocabulary.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(vocabulary))
            }
            // A pause to think mid-sentence shouldn't end it (a hint: services may not honour it).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_000L)
            // The local English; the device's default language if the service doesn't have it.
            val tag = testLanguage ?: language.takeIf { useRegionalEnglish }
            if (tag != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
            if (source != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            }
        }

    /**
     * Start listening. [onResult] fires with the recognised text (and, were a voice check possible,
     * the utterance's audio — null here); [onError] with a message. Both on the main thread.
     */
    fun listen(onResult: (Heard) -> Unit, onError: (String) -> Unit) {
        if (recognizer == null) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        this.onResult = onResult
        this.onError = onError
        triedFallback = false
        triedClientRetry = false
        // Reset any lingering state from the previous turn, then start fresh.
        handler.removeCallbacks(passOn)
        heard.clear()
        segments = 0
        alternatives = emptyList()
        recognizer.cancel()
        endCapture()
        start(useRegionalEnglish = true)
    }

    /**
     * Debug: recognizes [audio] instead of the mic, fed through EXTRA_AUDIO_SOURCE at the pace a
     * live mic would, then quiet — to test how the speech service takes our audio.
     */
    fun listenToAudio(audio: ShortArray, language: String?, onResult: (Heard) -> Unit, onError: (String) -> Unit) {
        testAudio = audio
        testLanguage = language
        listen(onResult, onError)
    }

    /** Starts recognition on the service's own mic (debug: on the test audio, fed to it instead). */
    private fun start(useRegionalEnglish: Boolean) {
        val rec = recognizer ?: return
        val own = testAudio?.let { Capture(it) }
        capture = own
        startedAt = SystemClock.elapsedRealtime()
        readyAt = 0L
        speechAt = 0L
        rec.startListening(buildIntent(useRegionalEnglish, own?.source))
        // Fed our audio, the service never gives up by itself when nobody speaks.
        if (own?.testing == true) handler.postDelayed(noSpeech, NO_SPEECH_MS)
    }

    /** Nobody spoke on the test audio: end the listen as the service would with its own mic. */
    private val noSpeech = Runnable {
        val c = capture ?: return@Runnable
        recognizer?.cancel()
        endCapture()?.let { report("nothing said", it, c) }
        onError?.invoke(describeError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    /** How a recognition on test audio went, with its timeline — to the voice log, and the audio to VoiceDebug. */
    private fun report(outcome: String, audio: ShortArray, c: Capture) {
        fun at(t: Long) = if (t == 0L) "never" else "${t - startedAt} ms"
        val took = SystemClock.elapsedRealtime() - startedAt
        val peak = audio.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        VoiceLog.add(appContext, "Recognizer on test audio: $outcome after $took ms " +
            "(ready ${at(readyAt)}, speech ${at(speechAt)}, fed ${"%.1f".format(audio.size.toDouble() / SAMPLE_RATE)} s, peak $peak)")
        VoiceDebug.keep(appContext, "stt-test", audio, JSONObject().put("outcome", outcome)
            .put("took_ms", took)
            .put("ready_ms", if (readyAt == 0L) -1 else readyAt - startedAt)
            .put("speech_ms", if (speechAt == 0L) -1 else speechAt - startedAt)
            .put("peak", peak))
    }

    /** Stops our capture, if any, and hands back the audio it kept. */
    private fun endCapture(): ShortArray? {
        handler.removeCallbacks(noSpeech)
        val c = capture ?: return null
        capture = null
        return c.finish().also { if (c.testing) { testAudio = null; testLanguage = null } }
    }

    /** Stop any in-flight recognition and drop callbacks so a late result can't fire after a reset. */
    fun cancel() {
        handler.removeCallbacks(passOn)
        heard.clear()
        segments = 0
        alternatives = emptyList()
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
     * Debug: [test] audio written into a pipe the speech service reads (EXTRA_AUDIO_SOURCE) in
     * place of its mic, paced like one, then a quiet room's faint hiss — with a copy kept (up to
     * [MAX_KEEP_SECONDS]) for the log. (With [test] null it would record the mic instead — which,
     * alongside the service, silences it; see the class notes.)
     */
    @SuppressLint("MissingPermission") // listen() is only used once RECORD_AUDIO is granted
    private class Capture(private val test: ShortArray? = null) {
        val testing: Boolean get() = test != null
        private val record = if (test != null) null else AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE)
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("mic busy") }
        }

        private val pipe = if (test != null) ParcelFileDescriptor.createPipe() else null
        /** The end the service reads, when we feed it. */
        val source: ParcelFileDescriptor? get() = pipe?.get(0)

        private val kept = ShortArray(SAMPLE_RATE * MAX_KEEP_SECONDS)
        private var keptLength = 0
        @Volatile private var running = true

        private val worker = thread(name = "naomi-ears") {
            val out = pipe?.let { ParcelFileDescriptor.AutoCloseOutputStream(it[1]) }
            val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val bytes = ByteArray(buf.size * 2)
            val hiss = java.util.Random(3)
            var played = 0
            try {
                record?.startRecording()
                while (running) {
                    val n = if (test == null) record!!.read(buf, 0, buf.size) else {
                        SystemClock.sleep(100)
                        for (i in buf.indices) buf[i] = if (played + i < test.size) test[played + i] else (hiss.nextInt(7) - 3).toShort()
                        played += buf.size
                        buf.size
                    }
                    if (n <= 0) continue
                    synchronized(kept) {
                        val take = minOf(n, kept.size - keptLength)
                        System.arraycopy(buf, 0, kept, keptLength, take)
                        keptLength += take
                    }
                    if (out != null) {
                        for (i in 0 until n) {
                            bytes[2 * i] = buf[i].toByte()                       // little-endian PCM
                            bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                        }
                        out.write(bytes, 0, n * 2)
                    }
                }
            } catch (e: IOException) {
                // The pipe was closed — recognition is over.
            } catch (e: Exception) {
                android.util.Log.w("Naomi", "Audio copy stopped: ${e.message}")
            } finally {
                runCatching { record?.stop() }
                record?.release()
                runCatching { out?.close() }
            }
        }

        /** Stops recording (or feeding, when the service reads us: it sees the audio end), keeping what was captured. */
        fun endInput() {
            running = false
        }

        /** Stops capturing and returns the audio kept so far. */
        fun finish(): ShortArray {
            running = false
            runCatching { pipe?.get(0)?.close() } // unblocks a write stuck on a full pipe
            worker.join(500)
            return synchronized(kept) { kept.copyOf(keptLength) }
        }
    }

    companion object {
        // Where English is spoken with its own words and accent — and Google's recognizer has it.
        private val ENGLISH_REGIONS = setOf("AU", "NZ", "GB", "IE", "US", "CA", "IN", "ZA", "SG", "PH", "NG", "KE", "PK", "GH", "TZ")

        /**
         * English as spoken where the phone is: by its mobile network's country, else its SIM's,
         * else the device's region — "en-AU" in Australia — or US English elsewhere.
         */
        fun englishHere(context: Context): String {
            val phone = context.getSystemService(TelephonyManager::class.java)
            val country = listOfNotNull(phone?.networkCountryIso, phone?.simCountryIso, Locale.getDefault().country)
                .firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT)
            return if (country in ENGLISH_REGIONS) "en-$country" else "en-US"
        }

        private const val SAMPLE_RATE = 16000
        private const val MAX_KEEP_SECONDS = 15
        // How long to wait for another segment of the same listen before passing the sentence on.
        private const val SEGMENT_WAIT_MS = 400L
        // Other guesses asked of the recognizer, for the brain to choose from.
        private const val MAX_ALTERNATIVES = 3
        // How long to wait for someone to start talking on test audio, as the service does with its mic.
        private const val NO_SPEECH_MS = 8_000L
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
