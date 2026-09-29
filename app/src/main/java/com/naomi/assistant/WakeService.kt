package com.naomi.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * "Hey Naomi" wake word — stable backbone + noise suppression + optional voice verification.
 *
 * Lifecycle (unchanged, proven stable across lock/doze/power-saving):
 *  - Foreground mic service. Loads Vosk once, listens continuously.
 *  - On detect: light the screen if off, bring the app up via full-screen intent, run the turn.
 *  - PAUSE/RESUME/STOP from the Activity around its own mic turns; 10s safety re-arm.
 *
 * Audio engine: we own the AudioRecord (instead of Vosk's SpeechService) so we can
 *  (a) attach AcousticEchoCanceler + NoiseSuppressor — cancels the phone's own music/echo
 *      so "Naomi" is still heard while a song plays, and
 *  (b) keep a rolling buffer to cut the wake phrase out of and verify the speaker's voice
 *      (VoiceEnrollment / CAM++).
 * If the user enrolled their voice, we only fire when the audio matches — anyone else gets a
 * spoken refusal and nothing happens. Without a profile, anyone fires.
 */
class WakeService : Service() {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioThread: Thread? = null
    @Volatile private var listening = false
    @Volatile private var firing = false // guards against repeat triggers mid-turn
    @Volatile private var answerMode = false // listening for a follow-up answer, not "Naomi"
    @Volatile private var enrollMode = false // collecting the owner's "Naomi"s for their voiceprint
    private var wakeLock: PowerManager.WakeLock? = null

    // Proximity sensor: blocks wake word firing when the phone is face-down or in a pocket.
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    @Volatile private var pocketBlocked = false
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // values[0] < maximumRange means the sensor is covered (pocket / face-down)
            pocketBlocked = event.values[0] < event.sensor.maximumRange
            if (pocketBlocked) android.util.Log.d("Naomi", "Proximity: covered — wake blocked")
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Watchdog: detect when the mic feed stalls (Doze/OEM freeze) and restart the recorder.
    @Volatile private var lastReadMs = 0L     // last time AudioRecord.read() returned data
    @Volatile private var lastSignalMs = 0L   // last time the buffer had any non-zero sample

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cooldownUntil = 0L

    private lateinit var enrollment: VoiceEnrollment

    // Voiceprint work runs here — off the audio loop (which must keep draining the mic) and
    // off the main thread.
    private val verifier = Executors.newSingleThreadExecutor()

    // Speaks the refusal when a stranger says the wake phrase — no UI needed, works locked.
    private lateinit var speaker: Speaker
    @Volatile private var denyQuietUntil = 0L

    // Rolling buffer of the most recent audio. Vosk finalizes ~1 s after the phrase ends, so
    // 4 s comfortably still holds the whole phrase when we cut it out for verification.
    private val ring = ShortArray(SAMPLE_RATE * RING_SECONDS)
    private var ringPos = 0
    private var ringFilled = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        enrollment = VoiceEnrollment.get(this)
        speaker = Speaker(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        // Restore the user's tuned voice-match threshold.
        similarityThreshold = getSharedPreferences("naomi", MODE_PRIVATE)
            .getFloat(PREF_THRESHOLD, DEFAULT_SIMILARITY_THRESHOLD)
        // Load the speaker model now so the first "Naomi" isn't slowed by it.
        if (enrollment.isEnrolled) verifier.execute { enrollment.warmUp() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { enrollMode = false; stopWake() } // Activity is about to use the mic itself
            ACTION_ANSWER -> { answerMode = true; enrollMode = false; startForegroundCompat(); armWhenReady() }
            ACTION_ENROLL -> {
                answerMode = false; enrollMode = true
                startForegroundCompat()
                verifier.execute { enrollment.warmUp() }
                armWhenReady()
            }
            ACTION_RESUME -> { answerMode = false; enrollMode = false; startForegroundCompat(); armWhenReady() }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> { answerMode = false; enrollMode = false; startForegroundCompat(); armWhenReady() }
        }
        return START_STICKY
    }

    /** Load the model if needed, then start listening. */
    private fun armWhenReady() {
        val m = model
        if (m != null) { startWake(m); return }
        StorageService.unpack(
            this, "vosk-model-en", "model",
            { loaded -> model = loaded; startWake(loaded) },
            { e -> android.util.Log.e("Naomi", "Vosk model load failed: ${e.message}") }
        )
    }

    @Suppress("MissingPermission") // RECORD_AUDIO is required before wake is enabled
    private fun startWake(m: Model) {
        stopWake() // ensure no double recognizer/record
        try {
            // Answer mode uses a restricted grammar of confirmation/choice words so it endpoints
            // instantly and stays robust in noise; wake mode uses the "naomi" grammar.
            val grammar = if (answerMode) ANSWER_GRAMMAR else WAKE_GRAMMAR
            recognizer = Recognizer(m, SAMPLE_RATE.toFloat(), grammar).apply { setWords(true) }
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // VOICE_RECOGNITION: same source Vosk's SpeechService used in the proven-stable
            // build; it survives screen-off here and is tuned for speech.
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE)
            )
            ringPos = 0; ringFilled = 0
            firing = false
            // Answer mode reacts fast (AEC handles the TTS); wake mode waits out arming transient.
            cooldownUntil = System.currentTimeMillis() + if (answerMode) 300 else 1200

            // Noise/echo cancellation: removes the phone's own audio output (music, TTS) and
            // steady background noise from the mic input, so "Naomi" is heard over a song.
            val sessionId = record!!.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
            }

            record?.startRecording()
            listening = true
            // Register proximity sensor so pocket/face-down blocks false wake word triggers.
            proximitySensor?.let {
                sensorManager?.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            // CPU wake lock scoped to active listening so the audio loop survives screen-off.
            acquireWakeLock()
            val now = System.currentTimeMillis()
            lastReadMs = now; lastSignalMs = now
            audioThread = thread(name = "naomi-wake") { audioLoop() }
            // Self-heal: if the feed stalls (freeze) or goes pure-silent, restart the recorder.
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
            android.util.Log.d("Naomi", "Wake listening ON (enrolled=${enrollment.isEnrolled}, aec=${echoCanceler?.enabled}, ns=${noiseSuppressor?.enabled})")
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Wake start failed: ${e.message}")
        }
    }

    private fun audioLoop() {
        val rec = record ?: return
        val reco = recognizer ?: return
        val buf = ShortArray(3200) // 200ms
        while (listening) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val now = System.currentTimeMillis()
            lastReadMs = now
            if (hasSignal(buf, n)) lastSignalMs = now
            appendRing(buf, n)
            // Finals only — partials are speculative and cause false triggers.
            if (!reco.acceptWaveForm(buf, n)) continue
            try {
                val json = JSONObject(reco.result)
                val text = json.optString("text").trim().lowercase()
                // Answer mode: any recognized answer word (grammar-restricted) is the reply.
                // No "Naomi" needed; AEC keeps Naomi's own voice from triggering it.
                if (answerMode) {
                    // Filter [unk] — Vosk's "uncertain" token; not a real answer.
                    if (text.isEmpty() || text == "[unk]" || firing || System.currentTimeMillis() < cooldownUntil) continue
                    firing = true
                    cooldownUntil = System.currentTimeMillis() + 1500
                    android.util.Log.i("Naomi", "Answer heard: \"$text\"")
                    main.post { answerCallback?.invoke(text) }
                    continue
                }
                if (text.isEmpty() || text !in WAKE_PHRASES) continue
                if (System.currentTimeMillis() < cooldownUntil) continue

                // ── Noise filters ─────────────────────────────────────────────
                val wordArray = json.optJSONArray("result")
                val words = (0 until (wordArray?.length() ?: 0))
                    .mapNotNull { wordArray?.optJSONObject(it) }

                // 1. Confidence gate: noise mis-recognitions land at 0.80–0.87;
                //    a cleanly spoken "naomi" is reliably ≥ 0.88.
                val minConf = words.mapNotNull { it.optDouble("conf").takeIf { !it.isNaN() } }
                    .minOrNull() ?: 0.0

                // 2. Duration gate: real "naomi" takes 0.25–0.80 s.
                //    Sub-0.20 s spikes are noise; >0.90 s suggests garbled speech.
                val naomiWord = words.firstOrNull { it.optString("word") == "naomi" }
                val duration = if (naomiWord != null)
                    naomiWord.optDouble("end") - naomiWord.optDouble("start") else -1.0

                android.util.Log.i("Naomi",
                    "Wake candidate: \"$text\" conf=${"%.2f".format(minConf)} dur=${"%.2f".format(duration)}s")

                val confOk     = minConf >= MIN_CONFIDENCE
                val durationOk = duration < 0 || duration in 0.20..0.90   // -1 = not parseable, allow
                if (confOk && durationOk) onWakeCandidate(text, phraseClip(words))
                else android.util.Log.d("Naomi",
                    "Wake rejected: conf=${"%.2f".format(minConf)} (need≥$MIN_CONFIDENCE) dur=${"%.2f".format(duration)}s")
            } catch (e: Exception) {
                android.util.Log.e("Naomi", "Wake parse error: ${e.message}")
            }
        }
    }

    /** Cheap check: does the buffer contain any non-zero sample (i.e. a live mic feed)? */
    private fun hasSignal(buf: ShortArray, n: Int): Boolean {
        var i = 0
        while (i < n) { if (buf[i].toInt() != 0) return true; i += 7 }
        return false
    }

    /**
     * Restarts the recorder if the mic feed has stalled (no reads — process was frozen) or has
     * been pure-digital-silence far longer than any real room (a dead/suspended feed). Runs on the
     * main thread, so if the OS froze us, this fires right after we thaw and self-heals.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!listening) return
            val now = System.currentTimeMillis()
            val stalledRead = now - lastReadMs > READ_STALL_MS
            val deadFeed = now - lastSignalMs > FEED_DEAD_MS
            if (stalledRead || deadFeed) {
                android.util.Log.w("Naomi", "Watchdog: ${if (stalledRead) "read stalled" else "dead mic feed"} — restarting recorder")
                model?.let { startWake(it) } // startWake reschedules the watchdog
                return
            }
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun appendRing(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[ringPos] = buf[i]
            ringPos = (ringPos + 1) % ring.size
            if (ringFilled < ring.size) ringFilled++
        }
    }

    /** Last [samples] samples from the ring — the wake word sits at the tail. */
    private fun snapshotLastN(samples: Int): ShortArray {
        val n = minOf(samples, ringFilled)
        val out = ShortArray(n)
        val start = (ringPos - n + ring.size) % ring.size
        for (i in 0 until n) out[i] = ring[(start + i) % ring.size]
        return out
    }

    /**
     * The wake phrase itself, cut from the ring. Its length comes from Vosk's word timings, but
     * not where it sits — on Android, Vosk's timestamps restart with every utterance — so the
     * end comes from the audio: the last stretch of speech, just before the silence that made
     * Vosk finalize. Enrollment and verification both use this cut, so the two sides of the
     * voice comparison hear the same thing, without trailing silence diluting the voiceprint.
     * Falls back to the newest 1.5 s if the phrase can't be located.
     */
    private fun phraseClip(words: List<JSONObject>): ShortArray {
        val start = words.minOfOrNull { it.optDouble("start", Double.NaN) } ?: Double.NaN
        val end = words.maxOfOrNull { it.optDouble("end", Double.NaN) } ?: Double.NaN
        val recent = snapshotLastN(SEARCH_SAMPLES)
        val speechEnd = lastSpeechEnd(recent, SAMPLE_RATE)
        if (start.isNaN() || end.isNaN() || end <= start || speechEnd == null) {
            android.util.Log.w("Naomi", "Wake clip: couldn't locate the phrase — using the newest audio")
            return snapshotLastN(FALLBACK_CLIP_SAMPLES)
        }
        val margin = (CLIP_MARGIN_S * SAMPLE_RATE).toInt()
        val from = maxOf(0, speechEnd - ((end - start) * SAMPLE_RATE).toInt() - margin)
        val to = minOf(recent.size, speechEnd + margin)
        return recent.copyOfRange(from, to)
    }

    /**
     * Wake phrase recognized. In enroll mode the clip goes to the trainer; otherwise, if a voice
     * profile exists, the speaker is verified off-thread and we fire or refuse.
     */
    private fun onWakeCandidate(phrase: String, clip: ShortArray) {
        if (firing) return
        // Training: the phone is in hand (maybe near the face), so skip the pocket check.
        if (enrollMode) {
            cooldownUntil = System.currentTimeMillis() + ENROLL_GAP_MS
            main.post { enrollCallback?.invoke(clip) }
            return
        }
        // Proximity sensor: if the screen is covered (pocket, face-down), don't fire.
        if (pocketBlocked) {
            android.util.Log.d("Naomi", "Wake suppressed: phone in pocket (proximity blocked)")
            return
        }
        cooldownUntil = System.currentTimeMillis() + 3000
        firing = true // hold further triggers while we decide
        if (!enrollment.isEnrolled) {
            main.post { onWakeAccepted(phrase) }
            return
        }
        // Always verify — also with the UI open, or anyone could barge in on an open Naomi.
        verifier.execute {
            val sim = enrollment.similarity(clip)
            main.post {
                if (sim != null) {
                    lastSimilarity = sim
                    lastSimilarityAt = System.currentTimeMillis()
                    // Persist so the Settings screen can show the score after this turn.
                    getSharedPreferences("naomi", MODE_PRIVATE).edit()
                        .putFloat("last_similarity", sim)
                        .putLong("last_similarity_at", lastSimilarityAt)
                        .apply()
                }
                val score = sim?.let { "%.3f".format(it) } ?: "none"
                android.util.Log.d("Naomi", "voice similarity=$score (threshold $similarityThreshold, clip ${clip.size * 1000 / SAMPLE_RATE}ms)")
                if (sim != null && sim >= similarityThreshold) {
                    onWakeAccepted(phrase)
                } else {
                    firing = false
                    denyAccess()
                }
            }
        }
    }

    private fun onWakeAccepted(phrase: String) {
        android.util.Log.d("Naomi", "Wake word detected: \"$phrase\"")
        onWake()
    }

    /**
     * Someone who isn't the enrolled owner said the wake phrase (or the voice couldn't be
     * checked — we fail closed): refuse out loud and do nothing else. One refusal per burst of
     * attempts, so repeated tries don't turn into a nagging loop.
     */
    private fun denyAccess() {
        // While Naomi is talking, an unrecognized "Naomi" is most likely her own voice leaking
        // past the echo canceller — ignore it rather than refuse herself mid-sentence.
        if (Speaker.speaking) {
            android.util.Log.d("Naomi", "Unverified wake while speaking — ignored as self-echo")
            return
        }
        deniedCallback?.invoke()
        val now = System.currentTimeMillis()
        if (now < denyQuietUntil) return
        denyQuietUntil = now + DENY_REPEAT_MS
        speaker.speak(DENY_MESSAGE)
    }

    private fun onWake() {
        cooldownUntil = System.currentTimeMillis() + 5_000 // don't re-fire during the command turn
        stopWake() // free the mic for the command recognizer
        bringUpAppForCommand()
        // Safety net: if the Activity never resumes us, re-arm after 10s.
        main.postDelayed({ if (!listening) model?.let { startWake(it) } }, 10_000)
    }

    private fun stopWake() {
        listening = false
        main.removeCallbacks(watchdog)
        sensorManager?.unregisterListener(proximityListener)
        try { audioThread?.join(500) } catch (e: Exception) {}
        audioThread = null
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
        try { record?.stop() } catch (e: Exception) {}
        record?.release(); record = null
        recognizer?.close(); recognizer = null
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Naomi:WakeAudio")
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    /** Bring MainActivity to the front (works locked/closed) via a full-screen intent. */
    private fun bringUpAppForCommand() {
        // If the display is off, light it up first — otherwise the user can't see the UI.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "Naomi:ScreenOn"
            ).acquire(5000L)
        }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_WAKE, true)
        }
        val pi = PendingIntent.getActivity(
            this, 2, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = getSystemService(NotificationManager::class.java)
        val ch = "naomi_wake_trigger"
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Naomi wake", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.notify(2, Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Naomi").setContentText("Listening…")
            .setFullScreenIntent(pi, true).setAutoCancel(true).build())
        try { startActivity(activityIntent) } catch (e: Exception) { /* covered by full-screen intent */ }
    }

    private fun startForegroundCompat() {
        val channelId = "naomi_wake"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Naomi wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Naomi is listening")
            .setContentText("Say \"Naomi\" to wake me.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopWake()
        verifier.shutdown()
        speaker.shutdown()
        model?.close()
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_RATE = 16000

        // Watchdog timings: check every 10s; restart if no audio read for 12s (frozen) or
        // pure-silence for 90s (dead/suspended feed — a real room is never digital zero this long).
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val READ_STALL_MS = 12_000L
        private const val FEED_DEAD_MS = 90_000L

        // Vosk word confidence threshold. Noise false-positives cluster at 0.80–0.87;
        // clean speech lands ≥ 0.88. Raise if noise still triggers; lower if Naomi misses you.
        private const val MIN_CONFIDENCE = 0.88

        // Cosine similarity to the owner's voiceprint needed to accept a wake — user-tunable from
        // Settings (persisted in prefs). On LibriSpeech clips the length of a wake phrase, CAM++
        // gave the owner ≈0.55–0.65 and strangers ≈0.10 ± 0.11. Raise if others get through;
        // lower if it rejects you.
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.40f
        const val PREF_THRESHOLD = "voice_threshold"
        @Volatile var similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD

        /** Most recent voice-match score + when it happened (for the Settings read-out). */
        @Volatile var lastSimilarity = -1f
        @Volatile var lastSimilarityAt = 0L

        private const val RING_SECONDS = 4
        // Audio kept on each side of the phrase when cutting it out.
        private const val CLIP_MARGIN_S = 0.15
        // How far back to look for the phrase: it ends ~1 s before Vosk finalizes.
        private const val SEARCH_SAMPLES = SAMPLE_RATE * 3
        private const val FALLBACK_CLIP_SAMPLES = SAMPLE_RATE * 3 / 2
        // Pause between accepted enrollment clips, so one "Naomi" can't count twice.
        private const val ENROLL_GAP_MS = 800L
        private const val DENY_REPEAT_MS = 5000L

        const val DENY_MESSAGE = "Sorry, I don't recognize your voice. You're not authorized to use me."

        private const val WAKE_GRAMMAR =
            "[\"hey naomi\", \"hi naomi\", \"hello naomi\", \"good morning naomi\", " +
            "\"good night naomi\", \"okay naomi\", \"naomi\", \"[unk]\"]"

        private val WAKE_PHRASES = setOf(
            "naomi", "hey naomi", "hi naomi", "hello naomi",
            "good morning naomi", "good night naomi", "okay naomi"
        )

        // Restricted grammar for follow-up answers — lets the user reply over Naomi's question
        // with no wake word. Covers confirm/decline, app choice, edits, and contact picks.
        private const val ANSWER_GRAMMAR =
            "[\"yes\", \"yeah\", \"yep\", \"okay\", \"ok\", \"sure\", \"send\", \"send it\", \"correct\", " +
            "\"no\", \"nope\", \"cancel\", \"stop\", " +
            "\"whatsapp\", \"whats app\", \"text\", \"message\", \"sms\", " +
            "\"change message\", \"change the message\", \"change contact\", \"change the contact\", " +
            "\"one\", \"two\", \"three\", \"[unk]\"]"

        /** Set by MainActivity — receives a recognized follow-up answer (on the main thread). */
        @Volatile var answerCallback: ((String) -> Unit)? = null

        /** Set by MainActivity during voice training — receives each "Naomi" clip (main thread). */
        @Volatile var enrollCallback: ((ShortArray) -> Unit)? = null

        /** Set by MainActivity — told when a stranger's wake was refused (main thread). */
        @Volatile var deniedCallback: (() -> Unit)? = null

        const val EXTRA_WAKE = "naomi_wake"
        const val ACTION_RESUME = "com.naomi.assistant.RESUME_WAKE"
        const val ACTION_PAUSE = "com.naomi.assistant.PAUSE_WAKE"
        const val ACTION_STOP = "com.naomi.assistant.STOP_WAKE"
        const val ACTION_ANSWER = "com.naomi.assistant.LISTEN_ANSWER"
        const val ACTION_ENROLL = "com.naomi.assistant.ENROLL_VOICE"

        fun start(context: Context) {
            val i = Intent(context, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun resume(context: Context) =
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_RESUME))
        /** Collect "Naomi" clips for voice training (see [enrollCallback]); resume/pause ends it.
         *  Starts the service in the foreground if wake listening was off. */
        fun startEnroll(context: Context) {
            val i = Intent(context, WakeService::class.java).setAction(ACTION_ENROLL)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        /** Arm AEC-backed listening for a bare follow-up answer (no wake word). */
        fun listenForAnswer(context: Context) =
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_ANSWER))
        fun pause(context: Context) =
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_PAUSE))
        fun stop(context: Context) =
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_STOP))
    }
}
