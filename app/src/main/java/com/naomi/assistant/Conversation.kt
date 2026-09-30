package com.naomi.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What Naomi is doing right now — drives the animated orb. */
enum class Mood { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * The conversation with Naomi: hearing a sentence, telling whose voice it was, the brain's turn,
 * speaking the reply and listening for the answer. It lives apart from any screen, so the full
 * app and the floating orb ([FloatingOrb]) carry on the same one — a question asked over another
 * app can be finished in hers. One per process ([get]); its state (the orb's [mood], the [status]
 * line, the last exchange in [transcript]) is Compose state that either can show.
 *
 * What only a screen can do — a system dialog to turn location on, keeping its window awake,
 * stepping aside so another app's screen can be driven — goes through [host], whichever of them
 * is showing Naomi at the time.
 */
class Conversation private constructor(private val app: Context) {

    /** What only the screen showing Naomi can do. */
    interface Host {
        /** Keeps the screen on while she's busy. */
        fun keepScreenOn(on: Boolean) {}

        /** Offers to turn location on (a system dialog), then runs [then], either way. */
        fun ensureLocationOn(then: () -> Unit) = then()

        /** Gets out of the way of the app whose screen is about to be driven; true if it moved. */
        fun stepAside(): Boolean = false
    }

    val voice = VoiceInput(app)
    val speaker = Speaker(app)
    val brain = AssistantBrain(app)
    private val enrollment = VoiceEnrollment.get(app)
    private val prefs = app.getSharedPreferences("naomi", Context.MODE_PRIVATE)

    var mood by mutableStateOf(Mood.IDLE)
        private set
    var status by mutableStateOf(idleStatus())
    var transcript by mutableStateOf("")
    /** The last per-sentence voice check's score, or -1 for none yet. */
    var lastSpeakerSim by mutableStateOf(prefs.getFloat(PREF_LAST_SPEAKER_SIM, -1f))
        private set

    /** The screen showing Naomi: the app, or the floating orb. */
    var host: Host? = null
        set(value) {
            field = value
            value?.keepScreenOn(mood != Mood.IDLE)
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var turnJob: Job? = null // the turn in flight, so a reset can cancel it
    private var listenStarting: Job? = null // a listen waiting for the mic, so it isn't started twice
    private var lastWakeAt = 0L // when the last wake was acted on, to drop its duplicate
    private var ignoredInARow = 0
    // The next sentence opens a turn the owner just started — by a verified "Naomi", or a tap on
    // an unlocked phone — so it's theirs, however noise scores it.
    private var trustNextSentence = false
    /** Guards a follow-up so only the first answer (barge-in OR after-question) is processed. */
    private val answerConsumed = AtomicBoolean(true)

    // Whether "Naomi" listening is on: the app's switch, kept in the same preferences.
    private val wakeEnabled: Boolean get() = prefs.getBoolean("wake", false)

    init {
        // When the user starts talking (e.g. answering a follow-up), cut Naomi off immediately.
        voice.onSpeechStart = { speaker.stop() }
        // "Let me check." while she looks something up, so the wait isn't silent.
        brain.onAside = { line -> speaker.aside(line) }
        brain.smartMode = prefs.getBoolean("smart", false)
        WakeService.similarityThreshold = prefs.getFloat(WakeService.PREF_THRESHOLD, WakeService.DEFAULT_SIMILARITY_THRESHOLD)
        // A bare follow-up answer heard over her question (via the AEC wake mic).
        WakeService.answerCallback = { word -> onSpokenAnswer(word) }
        WakeService.deniedCallback = { if (mood == Mood.IDLE) status = "Voice not recognized — access denied" }
    }

    /** Sets what she's doing, keeping the screen awake while it isn't nothing. */
    fun enterMood(m: Mood) {
        mood = m
        host?.keepScreenOn(m != Mood.IDLE)
    }

    /** True when Naomi is busy with something the user might want to abort. */
    val busy: Boolean get() = brain.hasPending || mood != Mood.IDLE

    /** The status line when she's waiting to be called. */
    fun idleStatus(): String = if (wakeEnabled) "Say \"Naomi\" anytime…" else "Tap the orb or say \"Naomi\""

    /**
     * The owner's "Naomi" (the wake service only wakes her for them, or for anyone, untrained):
     * cut off whatever she was saying and listen. Each wake can reach a screen twice, a moment
     * apart; the second is dropped, since a second listen would restart the recognizer and hear
     * nothing. False for such a duplicate.
     */
    fun wake(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWakeAt < WAKE_REPEAT_MS) return false
        lastWakeAt = now
        speaker.stop()
        trustNextSentence = true
        startListening()
        return true
    }

    /** A tap on the orb of an unlocked phone: whoever tapped is taken as the owner for this sentence. */
    fun tapped() {
        speaker.stop()
        trustNextSentence = true
        startListening()
    }

    fun startListening() {
        // Already on its way — waiting for the mic.
        if (listenStarting?.isActive == true) return
        if (wakeEnabled) WakeService.pause(app) // free the mic from Vosk for this turn
        enterMood(Mood.LISTENING)
        status = "Listening…"
        // Get a local brain loading while the user speaks, not after.
        scope.launch(Dispatchers.IO) { brain.warmUp() }
        // The recognizer needs the mic to itself — Android silences it while the wake listener
        // still records — so wait (briefly) for the pause to land.
        listenStarting = scope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + MIC_HANDOVER_MS
            while (WakeService.micActive && android.os.SystemClock.elapsedRealtime() < deadline) delay(20)
            // Names she knows, for the recognizer to listen out for (contacts are read off the main thread).
            voice.vocabulary = withContext(Dispatchers.IO) { brain.vocabulary() }
            if (mood == Mood.LISTENING) listenNow()
        }
    }

    private fun listenNow() {
        voice.listen(
            onResult = { heard ->
                val spoken = heard.text
                android.util.Log.d("Naomi", "Heard: \"$spoken\"" +
                    if (heard.alternatives.isEmpty()) "" else " (or: ${heard.alternatives.joinToString(" | ")})")
                enterMood(Mood.THINKING)
                val trusted = trustNextSentence
                trustNextSentence = false
                scope.launch { onHeard(spoken, identifySpeaker(spoken, heard.audio, trusted), heard.alternatives) }
            },
            onError = { message ->
                android.util.Log.e("Naomi", "STT error: $message")
                VoiceLog.add(app, "Speech recognizer: $message")
                trustNextSentence = false
                enterMood(Mood.IDLE)
                if (brain.hasPending) {
                    // Silence after Naomi spoke just ends the conversation — nothing to report,
                    // and no stale question left to hijack the next command.
                    brain.endFollowUp()
                    status = idleStatus()
                } else {
                    status = message
                }
                if (wakeEnabled) WakeService.resume(app)
            }
        )
    }

    /**
     * Speaks a reply that expects an answer, then listens for it:
     *  - For a one-word choice ("WhatsApp or text?"), the AEC wake mic listens DURING the question
     *    for a bare answer ("yes/cancel/whatsapp…"), so the user can talk over Naomi.
     *  - In free conversation that grammar would cut sentences short, so only the (voice-verified)
     *    wake word can interrupt her mid-reply.
     *  - If they stay silent, the normal recognizer opens the moment she finishes.
     * Whichever fires first wins (guarded by [answerConsumed]).
     */
    private fun askFollowUp(text: String) {
        answerConsumed.set(false)
        enterMood(Mood.SPEAKING)
        status = "Listening after I speak…"
        if (brain.awaitingChoice) {
            // AEC answer mode catches barge-in ("yes/whatsapp/cancel") WHILE Naomi is speaking.
            WakeService.listenForAnswer(app)
        } else if (wakeEnabled) {
            WakeService.resume(app)
        }
        speaker.speak(text) {
            // ALWAYS open the full recognizer after the question — even if AEC fired on noise.
            // If the pending follow-up was already handled via barge-in, brain.hasPending is false
            // and startListening() won't try to process a stale answer.
            WakeService.pause(app)
            scope.launch {
                delay(80)
                if (brain.hasPending) startListening()
            }
        }
    }

    /**
     * Whose voice an utterance was, against the owner's voiceprint. UNKNOWN when there's no print
     * yet, or the speech service didn't let us hear the audio (then everyone is taken as before).
     * A [trusted] sentence — the first of a turn the owner just opened — is theirs. Otherwise a
     * noisy or short sentence that scores a little under the threshold is UNKNOWN, not someone
     * else: noise pulls the owner's score down, and ignoring them in a street is worse than
     * answering a passer-by.
     */
    private suspend fun identifySpeaker(spoken: String, audio: ShortArray?, trusted: Boolean): Who {
        if (!enrollment.isEnrolled) return Who.UNKNOWN
        val said = "\"${spoken.take(40)}${if (spoken.length > 40) "…" else ""}\""
        if (audio == null) {
            if (voice.speakerCheckSupported == true) VoiceLog.add(app, "$said: no audio for a voice check")
            return if (trusted) Who.OWNER else Who.UNKNOWN
        }
        val check = withContext(Dispatchers.Default) { enrollment.speakerCheck(audio) }
        if (check == null) {
            VoiceLog.add(app, "$said: too little voice to check, taken as you")
            return if (trusted) Who.OWNER else Who.UNKNOWN
        }
        val sim = check.similarity
        val threshold = WakeService.similarityThreshold
        val who = when {
            trusted || sim >= threshold -> Who.OWNER
            (check.noisy || check.short) && sim >= threshold - UNSURE_MARGIN -> Who.UNKNOWN
            else -> Who.GUEST
        }
        val conditions = listOfNotNull(
            check.snrDb?.let { "voice ${it.toInt()} dB over the background" },
            "${"%.1f".format(check.voicedSeconds)} s of voice",
        ).joinToString(", ")
        VoiceLog.add(app, "$said: voice ${"%.2f".format(sim)} ($conditions) → " + when (who) {
            Who.OWNER -> if (sim >= threshold) "you" else "you (turn just opened)"
            Who.UNKNOWN -> "probably you, too noisy to be sure"
            Who.GUEST -> "someone else"
        })
        android.util.Log.d("Naomi", "speaker similarity=${"%.3f".format(sim)} → $who")
        VoiceDebug.keep(app, "sentence", audio, org.json.JSONObject().put("text", spoken).put("similarity", sim.toDouble())
            .put("snr_db", check.snrDb ?: org.json.JSONObject.NULL).put("voiced_s", check.voicedSeconds)
            .put("trusted", trusted).put("who", who.name).put("threshold", threshold.toDouble()))
        lastSpeakerSim = sim
        prefs.edit().putFloat(PREF_LAST_SPEAKER_SIM, sim).apply()
        return who
    }

    /**
     * Acts on something heard. Someone other than the owner who isn't talking to Naomi — people
     * talking to the owner while they talk to her — is ignored, and she keeps listening for the
     * owner. A guest who addresses her by name ("Naomi, …") is answered, as a guest.
     */
    private fun onHeard(spoken: String, who: Who, heardAs: List<String> = emptyList()) {
        if (who == Who.GUEST && !Regex("\\bnaomi\\b", RegexOption.IGNORE_CASE).containsMatchIn(spoken)) {
            android.util.Log.d("Naomi", "Ignored someone else: \"$spoken\"")
            VoiceLog.add(app, "Ignored: someone else, not talking to me")
            if (++ignoredInARow <= MAX_IGNORED_IN_A_ROW) {
                status = "Listening for ${brain.memory.get("name") ?: "you"}…"
                startListening()
            } else {
                ignoredInARow = 0
                brain.endFollowUp()
                enterMood(Mood.IDLE)
                status = idleStatus()
                if (wakeEnabled) WakeService.resume(app)
            }
            return
        }
        ignoredInARow = 0
        transcript = "${if (who == Who.GUEST) "Guest" else "You"}: $spoken"
        val lower = spoken.lowercase()
        if (isTurnOnLocation(lower)) {
            // Pure "turn on location" — no app to redirect to, just enable it.
            val host = host
            enterMood(Mood.SPEAKING)
            status = "Turning on location…"
            speaker.speak("Turning on location.") { enterMood(Mood.IDLE) }
            host?.ensureLocationOn { }
            if (wakeEnabled) WakeService.resume(app)
            return
        }
        // Driving other apps' screens is the owner's alone; a guest's words go to the brain.
        val screenCmd = if (who == Who.GUEST) null else parseScreenControl(lower)
        if (screenCmd != null) {
            performScreenControl(screenCmd)
            return
        }
        val host = host
        if (needsLocation(lower) && host != null) host.ensureLocationOn { runTurn(spoken, who, heardAs) }
        else runTurn(spoken, who, heardAs)
    }

    /** A follow-up answer was spoken over the question (caught by the AEC wake mic). */
    private fun onSpokenAnswer(word: String) {
        if (!answerConsumed.compareAndSet(false, true)) return
        speaker.stop()
        WakeService.pause(app)
        runTurn(word)
    }

    /** Runs one assistant turn: think → act/answer → speak, re-arming wake afterward. */
    fun runTurn(spoken: String, who: Who = Who.UNKNOWN, heardAs: List<String> = emptyList()) {
        enterMood(Mood.THINKING)
        status = "Thinking…"
        turnJob = scope.launch {
            val reply = brain.handle(spoken, who, heardAs)
            android.util.Log.d("Naomi", "Reply: \"${reply.text}\" (listenAgain=${reply.listenAgain})")
            transcript = "${if (who == Who.GUEST) "Guest" else "You"}: $spoken\n\nNaomi: ${reply.text}"
            if (reply.listenAgain) {
                askFollowUp(reply.text)
            } else {
                enterMood(Mood.SPEAKING)
                // Keep wake paused if recording is active — mic belongs to MediaRecorder.
                val resumeWake = wakeEnabled && !VoiceRecorder.isRecording
                status = if (VoiceRecorder.isRecording) "Recording… tap the orb to stop" else idleStatus()
                speaker.speak(reply.text) { enterMood(Mood.IDLE) }
                if (resumeWake) WakeService.resume(app)
            }
        }
    }

    /** Drives the app on screen through the accessibility service, once Naomi is out of its way. */
    private fun performScreenControl(sc: ScreenCmd) {
        if (!WhatsAppSender.isEnabled) {
            enterMood(Mood.SPEAKING)
            status = "Enable Naomi in Accessibility to control apps."
            speaker.speak("To control apps, please enable Naomi in your accessibility settings.") { enterMood(Mood.IDLE) }
            runCatching {
                app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (wakeEnabled) WakeService.resume(app)
            return
        }
        enterMood(Mood.IDLE)
        status = "On it…"
        if (wakeEnabled) WakeService.resume(app)
        // Reveal the app behind Naomi, then act on it once it's settled.
        val moved = host?.stepAside() == true
        main.postDelayed({ WhatsAppSender.perform(sc.action, sc.target) }, if (moved) 750L else 150L)
    }

    /**
     * Hard stop: abort whatever Naomi is doing (speaking, listening, thinking, a pending
     * follow-up) and go back to waiting to be called. The conversation that ends is noted down.
     */
    fun reset() {
        turnJob?.cancel(); turnJob = null
        listenStarting?.cancel(); listenStarting = null
        speaker.stop()
        voice.cancel()
        answerConsumed.set(true)
        trustNextSentence = false
        brain.cancel()
        enterMood(Mood.IDLE)
        transcript = ""
        if (wakeEnabled && !VoiceRecorder.isRecording) {
            WakeService.resume(app) // back to passive "Naomi" listening
            status = "Say \"Naomi\" anytime…"
        } else {
            WakeService.pause(app)
            status = if (VoiceRecorder.isRecording) "Recording… tap the orb to stop" else "Tap the orb or say \"Naomi\""
        }
    }

    /** A screen-control command: tap a labelled element, scroll, or type into a field. */
    data class ScreenCmd(val action: String, val target: String)

    companion object {
        @Volatile private var instance: Conversation? = null

        /** The conversation, made on first use. Main thread. */
        fun get(context: Context): Conversation =
            instance ?: Conversation(context.applicationContext).also { instance = it }

        /** The conversation if one was made already, for a service that shouldn't start one. */
        fun existing(): Conversation? = instance

        // Someone else's speech ignored this many times in a row ends the conversation.
        private const val MAX_IGNORED_IN_A_ROW = 2
        // Longest wait for the wake listener to let go of the mic before the recognizer starts.
        private const val MIC_HANDOVER_MS = 800L
        // A wake arriving this soon after the last one is its duplicate.
        private const val WAKE_REPEAT_MS = 2_000L
        // A noisy or short sentence scoring within this much below the voice-match threshold isn't
        // taken for someone else: noise pulls the owner's own score down that far.
        private const val UNSURE_MARGIN = 0.20f
        const val PREF_LAST_SPEAKER_SIM = "last_speaker_similarity"

        fun isTurnOnLocation(lower: String): Boolean =
            Regex("\\b(location|gps)\\b").containsMatchIn(lower) &&
                Regex("\\b(on|enable|turn|start)\\b").containsMatchIn(lower)

        fun needsLocation(lower: String): Boolean =
            ServiceApps.isRideRequest(lower) || ServiceApps.isFoodRequest(lower) ||
                Regex("\\b(cab|ride|taxi|navigate|directions|near me)\\b").containsMatchIn(lower) ||
                lower.contains("take me to")

        /** Recognizes "place order / tap X / select X / scroll down / type X" style commands. */
        fun parseScreenControl(lower: String): ScreenCmd? = when {
            Regex("\\bscroll\\b").containsMatchIn(lower) ->
                ScreenCmd("scroll", if (lower.contains("up")) "up" else "down")
            Regex("\\b(place (the )?order|place it|check ?out|confirm (the )?order|proceed)\\b").containsMatchIn(lower) ->
                ScreenCmd("tap", "place order|checkout|check out|proceed|confirm|place")
            Regex("\\b(tap|click|press|select|choose)\\b").containsMatchIn(lower) -> {
                val t = firstAfterVerb(lower, "tap", "click", "press", "select", "choose")
                if (t.isBlank()) null else ScreenCmd("tap", t)
            }
            lower.startsWith("type ") -> ScreenCmd("type", lower.removePrefix("type ").trim())
            else -> null
        }

        private fun firstAfterVerb(text: String, vararg verbs: String): String {
            for (v in verbs) {
                val m = Regex("\\b$v\\b\\s+(?:on |the )?(.+)").find(text)
                if (m != null) return m.groupValues[1]
                    .replace(Regex("\\b(button|option|please|now)\\b"), "").trim()
            }
            return ""
        }
    }
}
