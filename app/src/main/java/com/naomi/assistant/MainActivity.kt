package com.naomi.assistant

import android.Manifest
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.app.KeyguardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.Animatable

// Voice-match strictness slider bounds (cosine similarity to the owner's voiceprint).
private const val VOICE_THRESHOLD_MIN = 0.20f
private const val VOICE_THRESHOLD_MAX = 0.80f
// Voice training: "Naomi"s collected per profile, and how long to wait for them.
private const val ENROLL_CLIPS = 8
private const val ENROLL_TIMEOUT_MS = 90_000L

/**
 * Naomi — voice assistant UI.
 *
 * Flow: wake word / tap -> listen (SpeechRecognizer) -> AssistantBrain (the cloud brain in
 * smart mode, else the offline router / on-device Gemma) -> speak the reply (TextToSpeech).
 *
 * The UI is a small five-screen app driven by [screen]:
 *   HOME     — the orb, live status and the conversation transcript.
 *   FACTS    — what Naomi remembers: named facts, what she's learned, notes on past talks.
 *   ADD_FACT — a dedicated page to add one fact (typed or spoken).
 *   SETTINGS — voice options + one-tap links to the OS permissions Naomi needs.
 *   BRAIN    — which AI thinks for Naomi in smart mode, its API key, and her personality.
 */
class MainActivity : ComponentActivity(), Conversation.Host {

    companion object {
        /** Whether the app is on screen — then a "Naomi" goes to it, not to the floating orb. */
        @Volatile var inFront = false
            private set
    }

    /** The visible page. Everything but HOME has a back arrow. */
    enum class Screen { HOME, FACTS, ADD_FACT, SETTINGS, BRAIN }

    // The conversation, shared with the floating orb: its recognizer, voice and brain, and what
    // it shows — the orb's mood, the status line, the last exchange.
    private lateinit var session: Conversation
    private lateinit var voice: VoiceInput
    private lateinit var speaker: Speaker
    private lateinit var brain: AssistantBrain

    private var status: String
        get() = session.status
        set(value) { session.status = value }
    private var transcript: String
        get() = session.transcript
        set(value) { session.transcript = value }
    private val mood: Mood get() = session.mood
    private var wakeEnabled by mutableStateOf(false)
    private var showSplash by mutableStateOf(false) // set in onCreate based on launch type
    private var voiceTrained by mutableStateOf(false)
    private var smartMode by mutableStateOf(false)
    private var floating by mutableStateOf(true)
    private var factMode by mutableStateOf(false)

    // Power button (screen off) mid-interaction → stop everything and reset to idle.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isInteracting()) resetToInitial()
        }
    }

    private var screen by mutableStateOf(Screen.HOME)
    private var facts by mutableStateOf<Map<String, String>>(emptyMap())
    // What she's learned and her notes on past conversations, and whether she learns as she talks.
    private var memories by mutableStateOf<List<MemoryBank.Memory>>(emptyList())
    private var learning by mutableStateOf(true)
    // She learns in the background, mid-conversation: this keeps the memory screen current.
    private val memoriesChanged: () -> Unit = { runOnUiThread { memories = brain.memories.all() } }
    private var setup by mutableStateOf(SetupStatus())

    // Voice-match tuning (persisted): the ECAPA similarity threshold + the last observed score.
    private var voiceThreshold by mutableStateOf(WakeService.DEFAULT_SIMILARITY_THRESHOLD)
    private var lastSim by mutableStateOf(-1f)
    private var lastSimAt by mutableStateOf(0L)
    // The last per-sentence voice check, and whether the speech service lets us make it at all.
    private val lastSpeakerSim: Float get() = session.lastSpeakerSim
    private var speakerCheck by mutableStateOf<Boolean?>(null)
    // What the voice pipeline did lately (see VoiceLog), for the Settings screen.
    private var voiceLog by mutableStateOf<List<String>>(emptyList())
    private val voiceLogChanged: () -> Unit = { runOnUiThread { voiceLog = VoiceLog.recent(this) } }

    private val enrollment by lazy { VoiceEnrollment.get(this) }

    // Voice training in progress: the "Naomi" clips collected so far, and its give-up timer.
    private var enrollClips: MutableList<ShortArray>? = null
    private var enrollTimeout: Job? = null

    /** Blocks orb taps while Naomi is speaking the post-recording confirmation. */
    @Volatile private var recordingCooldown = false

    private val neededPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    /** Action to run once the "turn on location" dialog closes (e.g. open Uber). */
    private var pendingAfterLocation: (() -> Unit)? = null

    /** Receives the result of the one-tap "turn on location" system dialog. */
    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Proceed regardless of allow/deny — the target app will prompt again if still off.
            pendingAfterLocation?.invoke()
            pendingAfterLocation = null
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
            status = if (micGranted) "Tap the orb or say \"Naomi\"" else "Microphone permission is required"
            refreshSetup()
        }

    private fun enterMood(m: Mood) = session.enterMood(m)

    /** Keep the screen awake while a conversation turn is in progress. */
    override fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Reveals the app that was open behind Naomi, so its screen can be driven. */
    override fun stepAside(): Boolean = moveTaskToBack(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show Naomi ON TOP of the lock screen and turn the display on when woken.
        // No requestDismissKeyguard — that pushes the PIN screen in front instead.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // The recognizer, her voice and the brain (set up in-app, on BRAIN) belong to the
        // conversation, which the floating orb carries on too.
        session = Conversation.get(this)
        session.host = this
        voice = session.voice
        speaker = session.speaker
        brain = session.brain
        if (session.mood == Mood.IDLE && session.transcript.isEmpty()) status = session.idleStatus()
        facts = brain.memory.all()
        memories = brain.memories.all()
        learning = brain.settings.learnMemories
        brain.memories.onChange = memoriesChanged
        voiceLog = VoiceLog.recent(this)
        VoiceLog.onChange = voiceLogChanged
        refreshBrainUi()
        PortugueseModel.onChange = answersModelChanged
        // Speaking Portuguese: fetch the model that hears quick answers, if it isn't here yet.
        if (Language.current(this) == Language.PORTUGUESE) PortugueseModel.download(this)
        refreshLanguageUi()

        // Ask for everything Naomi needs up front (mic to listen, contacts + phone to call).
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        voiceTrained = enrollment.isEnrolled
        floating = FloatingOrb.enabled(this)

        // Show the splash only when the user taps the launcher icon, not on voice wake or when
        // launched as the assistant.
        showSplash = !intent.getBooleanExtra(WakeService.EXTRA_WAKE, false) &&
            intent.action != Intent.ACTION_ASSIST

        // Restore the smart-mode (cloud) preference and apply it to the brain.
        val prefs = getSharedPreferences("naomi", MODE_PRIVATE)
        smartMode = prefs.getBoolean("smart", false)
        brain.smartMode = smartMode

        // Restore the tuned voice-match threshold and hand it to the wake service.
        voiceThreshold = prefs.getFloat(WakeService.PREF_THRESHOLD, WakeService.DEFAULT_SIMILARITY_THRESHOLD)
        WakeService.similarityThreshold = voiceThreshold
        lastSim = prefs.getFloat("last_similarity", -1f)
        lastSimAt = prefs.getLong("last_similarity_at", 0L)

        // Restore the "Hey Naomi" setting: if it was on (and mic granted), re-arm listening.
        // This also covers resuming after a reboot via the BootReceiver notification.
        if (getSharedPreferences("naomi", MODE_PRIVATE).getBoolean("wake", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            WakeService.start(this)
            wakeEnabled = true
            status = "Say \"Naomi\" anytime…"
        }

        // Back button: mid-interaction it means "stop and reset", not "leave the app".
        onBackPressedDispatcher.addCallback(this) {
            if (isInteracting()) {
                resetToInitial()
                return@addCallback
            }
            when (screen) {
                Screen.ADD_FACT -> screen = Screen.FACTS
                Screen.BRAIN -> screen = Screen.SETTINGS
                Screen.FACTS, Screen.SETTINGS -> screen = Screen.HOME
                Screen.HOME -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        // Catch the power button (screen off) so leaving mid-interaction resets Naomi.
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            NaomiTheme {
                Box(Modifier.fillMaxSize()) {
                    NaomiApp(
                        screen        = screen,
                        mood          = mood,
                        status        = status,
                        transcript    = transcript,
                        wakeEnabled   = wakeEnabled,
                        smartMode     = smartMode,
                        voiceTrained  = voiceTrained,
                        factMode      = factMode,
                        facts         = facts,
                        memories      = memories,
                        learning      = learning,
                        setup         = setup,
                        voiceThreshold = voiceThreshold,
                        lastSim       = lastSim,
                        lastSimAt     = lastSimAt,
                        lastSpeakerSim = lastSpeakerSim,
                        speakerCheck  = speakerCheck,
                        voiceLog      = voiceLog,
                        onClearVoiceLog = { VoiceLog.clear(this@MainActivity) },
                        languageUi    = languageUi,
                        onCycleLanguage = { cycleLanguage() },
                        onDownloadAnswers = { PortugueseModel.download(this@MainActivity) },
                        onThresholdChange = { onThresholdChange(it) },
                        onNavigate    = { screen = it },
                        onOrbTap      = { onMicTapped() },
                        onWakeToggle  = { toggleWake() },
                        floating      = floating,
                        onFloatingToggle = { toggleFloating() },
                        onSmartToggle = { onSmartToggle(it) },
                        onTrain       = { trainVoice() },
                        onForgetVoice = { forgetVoice() },
                        onVoiceFact   = { startFactMode() },
                        onSaveFact    = { key, value -> saveFact(key, value); screen = Screen.FACTS },
                        onUpdateFact  = { old, key, value -> updateFact(old, key, value) },
                        onDeleteFact  = { key -> deleteFact(key) },
                        onToggleLearning = { on -> brain.settings.learnMemories = on; learning = on },
                        onUpdateMemory = { id, text -> brain.memories.update(id, text) },
                        onDeleteMemory = { id -> brain.memories.delete(listOf(id)) },
                        onForgetAll   = { brain.memories.clear() },
                        onOpenSetup   = { openSetup(it) },
                        brainUi       = brainUi,
                        brainActions  = brainActions,
                        onOpenBrain   = { refreshBrainUi(); screen = Screen.BRAIN },
                    )
                    // Splash overlay — shown on normal launcher open, skipped on voice wake.
                    if (showSplash) {
                        NaomiSplash(onComplete = { showSplash = false })
                    }
                }
            }
        }

        handleWakeIntent(intent)
        handleAssistIntent(intent)
        handleSttTest(intent)
    }

    override fun onResume() {
        super.onResume()
        // The app shows Naomi now: a conversation the floating orb was holding carries on here.
        inFront = true
        session.host = this
        FloatingOrb.hide()
        // Coming back from a system settings screen — refresh the setup checklist + facts.
        refreshSetup()
        // On Automatic, the phone's language may have changed meanwhile.
        refreshLanguageUi()
        facts = brain.memory.all()
        memories = brain.memories.all()
        // Freshest voice-match score (WakeService writes it live in this same process).
        if (WakeService.lastSimilarity >= 0f) {
            lastSim = WakeService.lastSimilarity
            lastSimAt = WakeService.lastSimilarityAt
        } else {
            val prefs = getSharedPreferences("naomi", MODE_PRIVATE)
            lastSim = prefs.getFloat("last_similarity", -1f)
            lastSimAt = prefs.getLong("last_similarity_at", 0L)
        }
        speakerCheck = voice.speakerCheckSupported
        voiceLog = VoiceLog.recent(this)
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    /** Persist and apply a new voice-match threshold from the Settings slider. */
    private fun onThresholdChange(value: Float) {
        val v = value.coerceIn(VOICE_THRESHOLD_MIN, VOICE_THRESHOLD_MAX)
        voiceThreshold = v
        WakeService.similarityThreshold = v
        getSharedPreferences("naomi", MODE_PRIVATE).edit().putFloat(WakeService.PREF_THRESHOLD, v).apply()
    }

    /** Home or Recents — the user deliberately left. Stop everything and reset to idle.
     *  Note: this fires ONLY for a user-initiated leave, NOT when Naomi launches a target app
     *  (dialer, Spotify, etc.), so confirmation speech for those isn't cut off. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInteracting()) resetToInitial()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
        handleAssistIntent(intent)
        handleSttTest(intent)
    }

    /**
     * Debug builds: `adb shell am start -n com.naomi.assistant/.MainActivity --es naomi_stt_test
     * "what time is it" --ei lead_ms 1000` says the text into a file in her voice and has the
     * recognizer hear it through the same pipe as the mic, after [lead_ms] of quiet — the outcome
     * goes to the voice log. Tests the speech service with nobody talking.
     */
    private fun handleSttTest(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        // `--es naomi_turns "first|second~its runner-up|third"`: a conversation from typed words, as the owner —
        // each reply logged, none spoken.
        intent?.getStringExtra("naomi_turns")?.let { typed ->
            intent.removeExtra("naomi_turns")
            lifecycleScope.launch {
                // "heard~other guess~another" stands in for the recognizer's runner-up guesses.
                for (line in typed.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val guesses = line.split('~').map { it.trim() }
                    val reply = brain.handle(guesses.first(), Who.OWNER, guesses.drop(1))
                    android.util.Log.d("Naomi", "Turn test: \"$line\" → \"${reply.text}\"")
                }
            }
            return
        }
        // `--es naomi_float "what's the weather"`: the floating orb over the home screen, on a turn
        // from typed words (or, empty, listening) — as a "Naomi" over another app would.
        intent?.getStringExtra("naomi_float")?.let { typed ->
            intent.removeExtra("naomi_float")
            moveTaskToBack(true)
            // After this activity's resume, which takes the orb down, has come and gone.
            Handler(Looper.getMainLooper()).postDelayed({
                FloatingOrb.show(this)
                if (typed.isBlank()) session.wake() else session.runTurn(typed, Who.OWNER)
            }, 800)
            return
        }
        // `--es naomi_turn "where's the closest bus stop"`: a whole turn from typed words, as the owner.
        intent?.getStringExtra("naomi_turn")?.let { typed ->
            intent.removeExtra("naomi_turn")
            if (wakeEnabled) WakeService.pause(this)
            session.runTurn(typed, Who.OWNER)
            return
        }
        val text = intent?.getStringExtra("naomi_stt_test") ?: return
        intent.removeExtra("naomi_stt_test")
        val leadMs = intent.getIntExtra("lead_ms", 0)
        if (wakeEnabled) WakeService.pause(this)
        if (intent.getBooleanExtra("aloud", false)) {
            // Out loud: the service listens on its own mic, our copy alongside, while she says it.
            val bargeIn = voice.onSpeechStart
            voice.onSpeechStart = null
            VoiceLog.add(this, "STT test aloud: \"$text\"")
            voice.listen(
                onResult = { heard ->
                    voice.onSpeechStart = bargeIn
                    VoiceLog.add(this, "STT test aloud heard: \"${heard.text}\"" +
                        if (heard.alternatives.isEmpty()) "" else " (or: ${heard.alternatives.joinToString(" | ")})")
                },
                onError = { message -> voice.onSpeechStart = bargeIn; VoiceLog.add(this, "STT test aloud failed: $message") })
            Handler(Looper.getMainLooper()).postDelayed({ speaker.speak(text) }, 900)
            return
        }
        speaker.synthesize(text, java.io.File(cacheDir, "stt_test.wav"), onAudio = { speech ->
            if (speech == null) {
                VoiceLog.add(this, "STT test: couldn't make the test speech")
                return@synthesize
            }
            val hiss = java.util.Random(5)
            val audio = ShortArray(leadMs * 16) { (hiss.nextInt(7) - 3).toShort() } + speech
            VoiceLog.add(this, "STT test: \"$text\" after $leadMs ms of quiet (${speech.size / 16} ms of speech, " +
                "${intent.getStringExtra("lang") ?: "en-IN"})")
            voice.listenToAudio(audio, intent.getStringExtra("lang"),
                onResult = { heard -> VoiceLog.add(this, "STT test heard: \"${heard.text}\"") },
                onError = { message -> VoiceLog.add(this, "STT test failed: $message") })
        })
    }

    /** Launched as the system's digital assistant (long-press power / corner swipe): start a
     *  turn exactly like an orb tap — so on a locked phone the voice lock asks for the unlock. */
    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_ASSIST) return
        intent.action = Intent.ACTION_MAIN // handled — don't re-run if the activity is recreated
        screen = Screen.HOME
        onMicTapped()
    }

    /** If the wake service launched us (or we were woken mid-speech), start a command turn. */
    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeService.EXTRA_WAKE, false) != true) return
        intent.removeExtra(WakeService.EXTRA_WAKE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        // Barge-in and listen; a wake's duplicate (it can come twice) is dropped.
        if (session.wake()) screen = Screen.HOME // voice wake always lands on the orb
    }

    // ── Facts ──────────────────────────────────────────────────────────────────

    private fun saveFact(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        brain.memory.put(key, value)
        facts = brain.memory.all()
    }

    private fun updateFact(oldKey: String, key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        brain.memory.update(oldKey, key, value)
        facts = brain.memory.all()
    }

    private fun deleteFact(key: String) {
        brain.memory.remove(key)
        facts = brain.memory.all()
    }

    // ── System setup deep-links ──────────────────────────────────────────────────

    /** Snapshot of which OS-level permissions/access Naomi currently has. */
    data class SetupStatus(
        val mic: Boolean = false,
        val accessibility: Boolean = false,
        val battery: Boolean = false,
        val overlay: Boolean = false,
        val notifications: Boolean = false,
    )

    /** Which setup item a "Set up" button targets. */
    enum class Setup { MIC, ACCESSIBILITY, BATTERY, OVERLAY, NOTIFICATIONS, ALL_PERMISSIONS, ASSISTANT }

    private fun refreshSetup() {
        val pm = getSystemService(PowerManager::class.java)
        setup = SetupStatus(
            mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
            accessibility = isAccessibilityEnabled(),
            battery = pm?.isIgnoringBatteryOptimizations(packageName) ?: false,
            overlay = Settings.canDrawOverlays(this),
            notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (WhatsAppSender.isEnabled) return true
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.split(':').any { it.contains(packageName, ignoreCase = true) }
    }

    /** Opens the relevant system screen so the user can grant one piece of access. */
    private fun openSetup(which: Setup) {
        val appUri = Uri.parse("package:$packageName")
        val intent = when (which) {
            Setup.MIC -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(neededPermissions)
                    return
                }
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
            }
            // Handled entirely by openAccessibility() with its own fallback chain (which never
            // lands on the app-info page), so we return before the generic launcher below.
            Setup.ACCESSIBILITY -> { openAccessibility(); return }
            Setup.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri)
            Setup.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri)
            Setup.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Setup.ALL_PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
            Setup.ASSISTANT -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Fall back to the app's own settings page if the specific screen is unavailable.
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Opens the accessibility settings, preferring Naomi's own service page.
     * Cascades so a normal app can always get *somewhere* useful:
     *   1. The service's detail page (stock Android 11+ only — some OEMs block this for apps).
     *   2. The accessibility list, scrolled/highlighted to Naomi.
     *   3. The plain accessibility list.
     * Never falls through to the app-info page.
     */
    private fun openAccessibility() {
        val component = ComponentName(this, WhatsAppSender::class.java).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(":settings:fragment_args_key", component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {}
        }
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", component)
                    .putExtra(":settings:show_fragment_args", args)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Exception) {}
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    /** Floating over other apps on "Naomi", or opening the app; turning it on may need the permission first. */
    private fun toggleFloating() {
        if (!Settings.canDrawOverlays(this)) {
            FloatingOrb.setEnabled(this, true)
            floating = true
            openSetup(Setup.OVERLAY)
            return
        }
        floating = !floating
        FloatingOrb.setEnabled(this, floating)
    }

    private fun toggleWake() {
        val prefs = getSharedPreferences("naomi", MODE_PRIVATE)
        if (wakeEnabled) {
            WakeService.stop(this)
            wakeEnabled = false
            status = "Tap the orb or say \"Naomi\""
            prefs.edit().putBoolean("wake", false).apply()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(neededPermissions)
            return
        }
        WakeService.start(this)
        wakeEnabled = true
        status = "Say \"Naomi\" anytime…"
        // Remember the choice so BootReceiver re-arms listening after a reboot.
        prefs.edit().putBoolean("wake", true).apply()
        // Ask to be exempt from battery optimization so the OS doesn't freeze the wake service.
        requestBatteryExemption()
    }

    /** Prompts to exclude Naomi from battery optimization (Doze freezes the wake mic otherwise). */
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun onSmartToggle(on: Boolean) {
        // Smart mode needs a brain: send the user to set one up instead of failing every turn.
        if (on && !brain.settings.isConfigured()) {
            refreshBrainUi("Pick a brain and add its API key to turn on smart mode.")
            screen = Screen.BRAIN
            return
        }
        smartMode = on
        brain.smartMode = on
        getSharedPreferences("naomi", MODE_PRIVATE).edit().putBoolean("smart", on).apply()
        status = if (on) "Smart mode on — ${brain.settings.provider.label} is my brain" else "On-device only"
    }

    // ── Language: what Naomi hears and speaks ──────────────────────────────────────

    /**
     * The Language row in Settings, and — while she speaks Portuguese — the row for the model
     * that hears a bare "sim" or "não" over her question ([PortugueseModel]).
     */
    data class LanguageUi(
        val summary: String = "",
        /** How the Portuguese answers model is doing; null unless she speaks Portuguese. */
        val answersModel: String? = null,
        val answersReady: Boolean = false,
    )

    private var languageUi by mutableStateOf(LanguageUi())
    private val answersModelChanged: () -> Unit = { runOnUiThread { refreshLanguageUi() } }

    private fun refreshLanguageUi() {
        val chosen = Language.chosen(this)
        val current = Language.current(this)
        val model = PortugueseModel.state(this)
        languageUi = LanguageUi(
            summary = if (chosen == Language.AUTO) "Automatic — ${current.label}, like your phone" else chosen.label,
            answersModel = if (current != Language.PORTUGUESE) null else when (model) {
                PortugueseModel.State.READY -> "Ready — say \"sim\" or \"não\" while she's still asking"
                PortugueseModel.State.DOWNLOADING -> "Downloading… ${PortugueseModel.percent}%"
                PortugueseModel.State.FAILED -> "Download failed — tap to try again"
                PortugueseModel.State.MISSING -> "Tap to download (about 31 MB)"
            },
            answersReady = model == PortugueseModel.State.READY,
        )
    }

    /** The Language row: Automatic → English → Português (Brasil) → Automatic. */
    private fun cycleLanguage() {
        val next = Language.entries[(Language.chosen(this).ordinal + 1) % Language.entries.size]
        Language.choose(this, next)
        if (Language.current(this) == Language.PORTUGUESE) PortugueseModel.download(this)
        refreshLanguageUi()
    }

    // ── Brain: the smart-mode AI, its key, and Naomi's personality ─────────────────

    /** Snapshot of the brain settings for the Brain screen. */
    data class BrainUi(
        val provider: Provider = Provider.GROQ,
        val hasKey: Boolean = false,
        val model: String = "",
        val baseUrl: String = "",
        val configured: Boolean = false,
        val userName: String = "",
        val persona: String = "",
        val conversation: Boolean = true,
        /** Outcome of the last save/test, shown under the buttons. */
        val notice: String? = null,
        val testing: Boolean = false,
        /** The user's SearXNG server, blank for DuckDuckGo; and the outcome of its last save/test. */
        val searchUrl: String = "",
        val searchNotice: String? = null,
        val testingSearch: Boolean = false,
    ) {
        /** One line for the Settings screen. */
        val summary: String
            get() = if (configured) "${provider.label} · $model" else "Pick a brain and add its API key"
    }

    /** Everything the Brain screen can do, bundled so it passes through the UI tree as one. */
    class BrainActions(
        val onSelectProvider: (Provider) -> Unit,
        val onSave: (key: String, model: String, baseUrl: String) -> Unit,
        val onForgetKey: () -> Unit,
        val onTest: (key: String, model: String, baseUrl: String) -> Unit,
        val onSavePersona: (name: String, persona: String) -> Unit,
        val onResetPersona: () -> Unit,
        val onConversationToggle: (Boolean) -> Unit,
        val onSaveSearch: (url: String) -> Unit,
        val onTestSearch: (url: String) -> Unit,
    )

    private var brainUi by mutableStateOf(BrainUi())

    private val brainActions = BrainActions(
        onSelectProvider = { brain.settings.provider = it; refreshBrainUi() },
        onSave = { key, model, url -> saveBrain(key, model, url) },
        onForgetKey = { brain.settings.setApiKey(brain.settings.provider, ""); refreshBrainUi("Key removed.") },
        onTest = { key, model, url -> testBrain(key, model, url) },
        onSavePersona = { name, persona -> savePersona(name, persona) },
        onResetPersona = { brain.settings.persona = ""; refreshBrainUi("✓ Personality reset") },
        onConversationToggle = { brain.settings.conversationMode = it; refreshBrainUi() },
        onSaveSearch = { url ->
            brain.settings.searchUrl = url
            val pc = BrainSettings.pcSearch(brain.settings.customBaseUrl)
            brainUi = brainUi.copy(searchUrl = brain.settings.searchUrl,
                searchNotice = when {
                    url.isNotBlank() -> "✓ Saved"
                    pc.isNotBlank() -> "✓ Saved — she'll search your PC's SearXNG ($pc), or DuckDuckGo when it's off"
                    else -> "✓ Saved — she'll search DuckDuckGo"
                })
        },
        onTestSearch = { url -> testSearch(url) },
    )

    private fun refreshBrainUi(notice: String? = null) {
        val s = brain.settings
        val p = s.provider
        brainUi = BrainUi(
            provider = p,
            hasKey = s.apiKey(p).isNotBlank(),
            model = s.model(p),
            baseUrl = s.customBaseUrl,
            configured = s.isConfigured(p),
            userName = brain.memory.get("name").orEmpty(),
            persona = s.persona,
            conversation = s.conversationMode,
            notice = notice,
            searchUrl = s.searchUrl,
        )
    }

    /** One search through the SearXNG on screen (saved or not) — or, blank, the one on the brain's PC — reporting how it went. */
    private fun testSearch(typed: String) {
        val url = typed.ifBlank { BrainSettings.pcSearch(brain.settings.customBaseUrl) }
        if (url.isBlank()) {
            brainUi = brainUi.copy(searchNotice = "✗ Add your SearXNG's address first.")
            return
        }
        brainUi = brainUi.copy(testingSearch = true, searchNotice = null)
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val notice = try {
                val results = SearchClient().trySearxng(url, "weather", Language.speechTag(this@MainActivity))
                if (results.isEmpty()) "✗ It answered, but with no results — are its engines working?"
                else "✓ ${results.size} results in ${System.currentTimeMillis() - started} ms" + if (typed.isBlank()) " from $url" else ""
            } catch (e: Exception) {
                "✗ ${e.message ?: e.javaClass.simpleName}"
            }
            brainUi = brainUi.copy(testingSearch = false, searchNotice = notice)
        }
    }

    /** Saves the selected provider's fields; a blank [key] keeps the saved one. */
    private fun saveBrain(key: String, model: String, baseUrl: String) {
        val s = brain.settings
        val p = s.provider
        if (key.isNotBlank()) s.setApiKey(p, key)
        s.setModel(p, model)
        if (p == Provider.CUSTOM) s.customBaseUrl = baseUrl
        refreshBrainUi(
            if (s.isConfigured(p)) "✓ Saved"
            else "Saved — still needs ${if (p == Provider.CUSTOM) "the server's base URL" else "an API key"}."
        )
    }

    /** One tiny request with the values on screen (saved or not), reporting the round trip. */
    private fun testBrain(key: String, model: String, baseUrl: String) {
        val s = brain.settings
        val p = s.provider
        val client = s.clientFor(p, key.ifBlank { s.apiKey(p) }, model, baseUrl)
        if (client == null) {
            brainUi = brainUi.copy(notice = if (p == Provider.CUSTOM) "✗ Add the server's base URL first." else "✗ Add an API key first.")
            return
        }
        brainUi = brainUi.copy(testing = true, notice = null)
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val notice = withContext(Dispatchers.IO) {
                try {
                    client.complete("You are a connection test. Reply with just the word: ready", listOf(Turn(fromUser = true, text = "ping")))
                    "✓ Connected — answered in ${System.currentTimeMillis() - started} ms"
                } catch (e: LlmException) {
                    "✗ ${e.message}"
                }
            }
            brainUi = brainUi.copy(testing = false, notice = notice)
        }
    }

    private fun savePersona(name: String, persona: String) {
        if (name.isBlank()) brain.memory.remove("name") else brain.memory.put("name", name)
        brain.settings.persona = persona
        facts = brain.memory.all()
        refreshBrainUi("✓ Personality saved")
    }

    /**
     * Ensures location is on, then runs [onReady]. If it's already on, [onReady] runs
     * immediately; if off, the one-tap system dialog shows and [onReady] runs once it closes —
     * so the caller can open the target app (Uber/Maps) AFTER the user enables location.
     */
    override fun ensureLocationOn(then: () -> Unit) = ensureLocation(then)

    private fun ensureLocation(onReady: () -> Unit = {}) {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
            ).build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(request)
            .addOnSuccessListener { onReady() } // already on → proceed straight away
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    pendingAfterLocation = onReady
                    try {
                        locationSettingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                    } catch (_: Exception) {
                        pendingAfterLocation = null
                        onReady()
                    }
                } else {
                    onReady() // can't resolve here → just proceed
                }
            }
    }

    private fun onMicTapped() {
        // If we're mid-recording, the orb tap means "stop".
        if (VoiceRecorder.isRecording) {
            stopRecordingNow()
            return
        }
        // Ignore taps while speaking the post-recording confirmation.
        if (recordingCooldown) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        // Voice lock: once a voiceprint exists, a locked phone takes commands only through the
        // verified wake word — a tap on the lock screen has to unlock first.
        if (voiceTrained && isDeviceLocked()) {
            status = "Unlock to continue — or just say \"Naomi\""
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { onMicTapped() }
                })
            return
        }
        // Interrupts any speech; whoever unlocked the phone and tapped is taken to be the owner.
        session.tapped()
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun stopRecordingNow() {
        val msg = VoiceRecorder.stop()
        transcript = "Naomi: $msg"
        enterMood(Mood.SPEAKING)
        recordingCooldown = true
        status = if (wakeEnabled) "Say \"Naomi\" anytime…" else "Tap the orb or say \"Naomi\""
        speaker.speak(msg) {
            recordingCooldown = false
            enterMood(Mood.IDLE)
            if (wakeEnabled) WakeService.resume(this@MainActivity)
        }
    }

    /** True when Naomi is actively doing something the user might want to abort. */
    private fun isInteracting(): Boolean = factMode || enrollClips != null || session.busy

    /**
     * Hard stop: abort whatever Naomi is doing (speaking, listening, thinking, a pending
     * follow-up, fact entry) and return to the idle home state. Triggered by Back, Home/Recents,
     * and the power button (screen off) so leaving mid-interaction always cleanly resets.
     */
    private fun resetToInitial() {
        if (enrollClips != null) stopEnrollment()
        factMode = false
        session.reset()
        screen = Screen.HOME
    }

    /**
     * One-shot fact-entry mode: listen once, try to parse a fact statement, store it.
     * Example: "my mom is Amma" → stores mom→Amma. Triggered from the Add Fact page.
     */
    private fun startFactMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (factMode) return // already listening
        factMode = true
        if (wakeEnabled) WakeService.pause(this)
        speaker.stop()
        enterMood(Mood.LISTENING)
        status = "Say a fact — e.g. \"my mom is Amma\""
        voice.listen(
            onResult = { heard ->
                val spoken = heard.text
                factMode = false
                enterMood(Mood.SPEAKING)
                val confirmation = brain.memory.learnFromSpeech(spoken)
                    ?: "Sorry, I didn't understand that as a fact. Try saying \"my mom is Amma\"."
                android.util.Log.i("Naomi", "Fact input: \"$spoken\" → $confirmation")
                facts = brain.memory.all()
                if (facts.isNotEmpty()) screen = Screen.FACTS
                transcript = "You: $spoken\n\nNaomi: $confirmation"
                status = if (wakeEnabled) "Say \"Naomi\" anytime…" else "Tap the orb or say \"Naomi\""
                speaker.speak(confirmation) { enterMood(Mood.IDLE) }
                if (wakeEnabled) WakeService.resume(this)
            },
            onError = { message ->
                factMode = false
                enterMood(Mood.IDLE)
                status = message
                if (wakeEnabled) WakeService.resume(this)
            }
        )
    }

    /**
     * Guided voice training: the user says "Naomi" [ENROLL_CLIPS] times — WakeService spots each
     * one and cuts it out exactly as it will at wake time. (Only "Naomi" is checked: sentences
     * reach us without their audio — see VoiceInput — so a print of free speech would go unused.)
     */
    private fun trainVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (enrollClips != null) return // already training
        screen = Screen.HOME // show the orb during the guided enrollment
        val clips = mutableListOf<ShortArray>()
        enrollClips = clips
        if (wakeEnabled) WakeService.pause(this) // no wake-ups while Naomi explains
        enterMood(Mood.SPEAKING)
        status = "Voice training"
        // The prompt never says "Naomi" and capture starts only once it ends, so Naomi's own
        // voice can't end up in the owner's voiceprint.
        speaker.speak("Let's learn your voice. Say my name $ENROLL_CLIPS times, pausing between each.") {
            if (enrollClips !== clips) return@speak // cancelled while speaking
            WakeService.enrollCallback = { clip -> onEnrollClip(clips, clip) }
            WakeService.startEnroll(this)
            enterMood(Mood.LISTENING)
            status = "Say \"Naomi\" (1/$ENROLL_CLIPS)"
        }
        enrollTimeout = lifecycleScope.launch {
            delay(ENROLL_TIMEOUT_MS)
            finishEnrollment(clips)
        }
    }

    /** One "Naomi" captured during training. */
    private fun onEnrollClip(clips: MutableList<ShortArray>, clip: ShortArray) {
        if (enrollClips !== clips) return
        clips.add(clip)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (clips.size >= ENROLL_CLIPS) finishEnrollment(clips)
        else status = "Got it — again (${clips.size + 1}/$ENROLL_CLIPS)"
    }

    /** Ends training (all clips in, or timed out) and builds the voiceprint from what we have. */
    private fun finishEnrollment(clips: List<ShortArray>) {
        if (enrollClips !== clips) return
        if (clips.size < VoiceEnrollment.MIN_CLIPS) {
            stopEnrollment()
            enterMood(Mood.SPEAKING)
            status = "Training timed out — try again"
            speaker.speak("I didn't hear my name enough times. Let's try again later.") { enterMood(Mood.IDLE) }
            return
        }
        stopEnrollment(resumeWake = false) // back on once the voice is learned
        enterMood(Mood.THINKING)
        status = "Learning your voice…"
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) { enrollment.enroll(clips) }
            voiceTrained = enrollment.isEnrolled
            VoiceLog.add(this@MainActivity, outcome?.let {
                "Training: ${it.clips} \"Naomi\"s, agreeing ${"%.2f".format(it.consistency)} on average, weakest " +
                    "${"%.2f".format(it.weakest)}" + if (it.dropped > 0) " (${it.dropped} left out: silence, not voice)" else ""
            } ?: "Training failed: too few usable \"Naomi\"s")
            enterMood(Mood.SPEAKING)
            if (outcome == null) {
                status = "Training failed, try again"
                speaker.speak("Sorry, I couldn't learn your voice. Please try again.") { enterMood(Mood.IDLE) }
            } else {
                status = "Voice trained ✓ — only you can wake me"
                speaker.speak("Perfect. I know your voice now. I'll only wake up for you.") { enterMood(Mood.IDLE) }
            }
            if (wakeEnabled) WakeService.resume(this@MainActivity) else WakeService.stop(this@MainActivity)
        }
    }

    /** Stops clip collection and, unless training goes on, hands the mic back to wake listening. */
    private fun stopEnrollment(resumeWake: Boolean = true) {
        enrollClips = null
        enrollTimeout?.cancel(); enrollTimeout = null
        WakeService.enrollCallback = null
        when {
            !resumeWake -> WakeService.pause(this)
            wakeEnabled -> WakeService.resume(this)
            else -> WakeService.stop(this)
        }
    }

    /** Deletes the voiceprint — Naomi answers anyone again until re-trained. */
    private fun forgetVoice() {
        enrollment.clear()
        voiceTrained = false
        status = "Voice lock off — anyone can wake Naomi"
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        if (brain.memories.onChange === memoriesChanged) brain.memories.onChange = null
        if (VoiceLog.onChange === voiceLogChanged) VoiceLog.onChange = null
        if (PortugueseModel.onChange === answersModelChanged) PortugueseModel.onChange = null
        // The recognizer and her voice are the conversation's, which the floating orb may still be
        // using. Closing the app ends a conversation it was showing (and notes it down).
        if (session.host === this) {
            session.host = null
            if (isFinishing) session.reset()
        }
        super.onDestroy()
    }
}

// ── Theme wrapper ─────────────────────────────────────────────────────────────
@Composable
private fun NaomiTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

// ── App shell: routes between the four screens ─────────────────────────────────
@Composable
private fun NaomiApp(
    screen: MainActivity.Screen,
    mood: Mood,
    status: String,
    transcript: String,
    wakeEnabled: Boolean,
    smartMode: Boolean,
    voiceTrained: Boolean,
    factMode: Boolean,
    facts: Map<String, String>,
    memories: List<MemoryBank.Memory>,
    learning: Boolean,
    setup: MainActivity.SetupStatus,
    voiceThreshold: Float,
    lastSim: Float,
    lastSimAt: Long,
    lastSpeakerSim: Float,
    speakerCheck: Boolean?,
    voiceLog: List<String>,
    onClearVoiceLog: () -> Unit,
    languageUi: MainActivity.LanguageUi,
    onCycleLanguage: () -> Unit,
    onDownloadAnswers: () -> Unit,
    onThresholdChange: (Float) -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
    onOrbTap: () -> Unit,
    onWakeToggle: () -> Unit,
    floating: Boolean,
    onFloatingToggle: () -> Unit,
    onSmartToggle: (Boolean) -> Unit,
    onTrain: () -> Unit,
    onForgetVoice: () -> Unit,
    onVoiceFact: () -> Unit,
    onSaveFact: (String, String) -> Unit,
    onUpdateFact: (String, String, String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onToggleLearning: (Boolean) -> Unit,
    onUpdateMemory: (Long, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onForgetAll: () -> Unit,
    onOpenSetup: (MainActivity.Setup) -> Unit,
    brainUi: MainActivity.BrainUi,
    brainActions: MainActivity.BrainActions,
    onOpenBrain: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        AmbientBackground()

        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "screen"
        ) { current ->
            when (current) {
                MainActivity.Screen.HOME -> HomeScreen(
                    mood = mood, status = status, transcript = transcript,
                    factCount = facts.size + memories.size,
                    onOrbTap = onOrbTap,
                    onNavigate = onNavigate
                )
                MainActivity.Screen.FACTS -> FactsScreen(
                    facts = facts,
                    memories = memories,
                    learning = learning,
                    onBack = { onNavigate(MainActivity.Screen.HOME) },
                    onAdd = { onNavigate(MainActivity.Screen.ADD_FACT) },
                    onUpdate = onUpdateFact,
                    onDelete = onDeleteFact,
                    onToggleLearning = onToggleLearning,
                    onUpdateMemory = onUpdateMemory,
                    onDeleteMemory = onDeleteMemory,
                    onForgetAll = onForgetAll,
                    onNavigate = onNavigate,
                )
                MainActivity.Screen.ADD_FACT -> AddFactScreen(
                    factMode = factMode,
                    onBack = { onNavigate(MainActivity.Screen.FACTS) },
                    onSave = onSaveFact,
                    onVoiceFact = onVoiceFact,
                )
                MainActivity.Screen.SETTINGS -> SettingsScreen(
                    wakeEnabled = wakeEnabled,
                    smartMode = smartMode,
                    voiceTrained = voiceTrained,
                    setup = setup,
                    voiceThreshold = voiceThreshold,
                    lastSim = lastSim,
                    lastSimAt = lastSimAt,
                    lastSpeakerSim = lastSpeakerSim,
                    speakerCheck = speakerCheck,
                    voiceLog = voiceLog,
                    onClearVoiceLog = onClearVoiceLog,
                    languageUi = languageUi,
                    onCycleLanguage = onCycleLanguage,
                    onDownloadAnswers = onDownloadAnswers,
                    onThresholdChange = onThresholdChange,
                    onBack = { onNavigate(MainActivity.Screen.HOME) },
                    onWakeToggle = onWakeToggle,
                    floating = floating,
                    onFloatingToggle = onFloatingToggle,
                    onSmartToggle = onSmartToggle,
                    onTrain = onTrain,
                    onForgetVoice = onForgetVoice,
                    onOpenSetup = onOpenSetup,
                    onNavigate = onNavigate,
                    brainSummary = brainUi.summary,
                    onOpenBrain = onOpenBrain,
                )
                MainActivity.Screen.BRAIN -> BrainScreen(
                    ui = brainUi,
                    smartMode = smartMode,
                    onBack = { onNavigate(MainActivity.Screen.SETTINGS) },
                    onSmartToggle = onSmartToggle,
                    actions = brainActions,
                )
            }
        }
    }
}

// ── Ambient gradient blobs (shared background) ─────────────────────────────────
@Composable
private fun AmbientBackground() {
    Box(
        Modifier
            .size(380.dp)
            .offset((-60).dp, (-80).dp)
            .background(
                Brush.radialGradient(listOf(PrimaryViolet.copy(alpha = 0.07f), Color.Transparent)),
                CircleShape
            )
    )
    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            Modifier
                .size(300.dp)
                .offset(60.dp, 80.dp)
                .background(
                    Brush.radialGradient(listOf(CyanAccent.copy(alpha = 0.06f), Color.Transparent)),
                    CircleShape
                )
        )
    }
}

// ── HOME ────────────────────────────────────────────────────────────────────
@Composable
private fun HomeScreen(
    mood: Mood,
    status: String,
    transcript: String,
    factCount: Int,
    onOrbTap: () -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    val isRecording = status.startsWith("Recording")

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoBadge()
            Spacer(Modifier.weight(1f))
            Text(
                "NAOMI",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 4.sp,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(listOf(PrimaryViolet, CyanAccent))
                )
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onNavigate(MainActivity.Screen.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = OnSurfaceVariant)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NaomiOrb(mood = mood, isRecording = isRecording, onTap = onOrbTap)
                Spacer(Modifier.height(16.dp))
                Text(
                    status.uppercase(),
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = when {
                        isRecording                          -> RecordingRed
                        mood == Mood.LISTENING -> CyanAccent
                        mood == Mood.THINKING  -> PrimaryViolet
                        mood == Mood.SPEAKING  -> MagentaAccent
                        else                                -> OnSurfaceVariant.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TranscriptCard(transcript = transcript)
            Spacer(Modifier.height(4.dp))
        }

        NaomiBottomBar(
            current = MainActivity.Screen.HOME,
            factCount = factCount,
            onNavigate = onNavigate
        )
    }
}

// ── FACTS ─────────────────────────────────────────────────────────────────────
/** Everything Naomi remembers: named shortcuts, what she's learned about you, and past talks. */
@Composable
private fun FactsScreen(
    facts: Map<String, String>,
    memories: List<MemoryBank.Memory>,
    learning: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleLearning: (Boolean) -> Unit,
    onUpdateMemory: (Long, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onForgetAll: () -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    val learned = memories.filter { it.kind == MemoryBank.Kind.FACT }
    val notes = memories.filter { it.kind == MemoryBank.Kind.EPISODE }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ScreenTopBar(title = "What Naomi Remembers", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Add button
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(PrimaryViolet.copy(alpha = 0.22f), CyanAccent.copy(alpha = 0.18f))
                        )
                    )
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onAdd
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = OnSurface, modifier = Modifier.size(20.dp))
                Text(
                    "Add a fact",
                    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, color = OnSurface
                )
            }
            SettingToggleRow(
                title = "Learn as we talk",
                subtitle = "In smart mode, she keeps what you tell her about yourself and notes each conversation",
                icon = Icons.Outlined.Psychology,
                accent = PrimaryViolet,
                checked = learning,
                onToggle = { onToggleLearning(!learning) }
            )

            if (facts.isEmpty() && memories.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🧠", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing remembered yet",
                        fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, color = OnSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Teach Naomi shortcuts like \"mom → Amma\", say \"remember that…\", or just talk — " +
                            "in smart mode she picks things up as you go.",
                        fontFamily = InterFamily, fontSize = 13.sp,
                        color = OnSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.Center
                    )
                }
            } else {
                if (facts.isNotEmpty()) SectionLabel("Shortcuts")
                facts.forEach { (key, value) ->
                    FactCard(
                        factKey = key, factValue = value,
                        onSave = { k, v -> onUpdate(key, k, v) },
                        onDelete = { onDelete(key) }
                    )
                }
                if (learned.isNotEmpty()) SectionLabel("What she knows about you")
                learned.forEach { memory ->
                    key(memory.id) {
                        MemoryCard(memory, onSave = { onUpdateMemory(memory.id, it) }, onDelete = { onDeleteMemory(memory.id) })
                    }
                }
                if (notes.isNotEmpty()) SectionLabel("Past conversations")
                notes.forEach { memory ->
                    key(memory.id) {
                        MemoryCard(memory, onSave = { onUpdateMemory(memory.id, it) }, onDelete = { onDeleteMemory(memory.id) })
                    }
                }
                if (memories.isNotEmpty()) ForgetAllRow(memories.size, onForgetAll)
            }
            Spacer(Modifier.height(12.dp))
        }

        NaomiBottomBar(
            current = MainActivity.Screen.FACTS,
            factCount = facts.size,
            onNavigate = onNavigate
        )
    }
}

/** A single fact shown as a card that flips into an inline editor when tapped. */
@Composable
private fun FactCard(
    factKey: String,
    factValue: String,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(factKey, factValue) { mutableStateOf(false) }
    var keyText by remember(factKey) { mutableStateOf(factKey) }
    var valueText by remember(factValue) { mutableStateOf(factValue) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        factKey.uppercase(),
                        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
                        fontSize = 11.sp, letterSpacing = 1.sp, color = CyanAccent
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        factValue,
                        fontFamily = InterFamily, fontSize = 16.sp, color = OnSurface
                    )
                }
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = RecordingRed.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
            }
        } else {
            NaomiTextField(value = keyText, onValueChange = { keyText = it }, label = "Name (e.g. mom)")
            Spacer(Modifier.height(10.dp))
            NaomiTextField(value = valueText, onValueChange = { valueText = it }, label = "Value (e.g. Amma)")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Save", icon = Icons.Outlined.Check, accent = SuccessGreen,
                    modifier = Modifier.weight(1f),
                    enabled = keyText.isNotBlank() && valueText.isNotBlank()
                ) { onSave(keyText, valueText); editing = false }
                PillButton(
                    text = "Cancel", icon = Icons.Outlined.Close, accent = OutlineColor,
                    modifier = Modifier.weight(1f), filled = false
                ) { keyText = factKey; valueText = factValue; editing = false }
            }
        }
    }
}

/** Something she learned, or her note on a conversation: what it says and when — editable in place. */
@Composable
private fun MemoryCard(
    memory: MemoryBank.Memory,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(memory.id, memory.text) { mutableStateOf(false) }
    var text by remember(memory.id, memory.text) { mutableStateOf(memory.text) }
    val fact = memory.kind == MemoryBank.Kind.FACT
    val ago = DateUtils.getRelativeTimeSpanString(memory.created, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (fact) memory.topic.ifBlank { "learned" } + " · $ago" else ago).uppercase(),
                        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
                        fontSize = 11.sp, letterSpacing = 1.sp, color = if (fact) PrimaryViolet else CyanAccent
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(memory.text, fontFamily = InterFamily, fontSize = 15.sp, color = OnSurface, lineHeight = 21.sp)
                }
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Forget", tint = RecordingRed.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
            }
        } else {
            NaomiTextField(value = text, onValueChange = { text = it }, label = "What she remembers", singleLine = false)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Save", icon = Icons.Outlined.Check, accent = SuccessGreen,
                    modifier = Modifier.weight(1f), enabled = text.isNotBlank()
                ) { onSave(text); editing = false }
                PillButton(
                    text = "Cancel", icon = Icons.Outlined.Close, accent = OutlineColor,
                    modifier = Modifier.weight(1f), filled = false
                ) { text = memory.text; editing = false }
            }
        }
    }
}

/** Wipes everything she's learned — on a second tap within a few seconds, so never by accident. */
@Composable
private fun ForgetAllRow(count: Int, onForgetAll: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    Text(
        if (armed) "Tap again to forget all $count" else "Forget everything she's learned",
        fontFamily = InterFamily, fontSize = 13.sp,
        color = RecordingRed.copy(alpha = 0.85f),
        modifier = Modifier
            .clickable { if (armed) { armed = false; onForgetAll() } else armed = true }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    )
}

// ── ADD FACT ────────────────────────────────────────────────────────────────
@Composable
private fun AddFactScreen(
    factMode: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    onVoiceFact: () -> Unit,
) {
    var keyText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        ScreenTopBar(title = "Add a Fact", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Teach Naomi something to remember. Use a short name and what it maps to.",
                fontFamily = InterFamily, fontSize = 14.sp,
                color = OnSurfaceVariant.copy(alpha = 0.7f)
            )

            NaomiTextField(value = keyText, onValueChange = { keyText = it }, label = "Name — e.g. mom, home, music app")
            NaomiTextField(value = valueText, onValueChange = { valueText = it }, label = "Value — e.g. Amma, HSR Layout, Wynk")

            PillButton(
                text = "Save fact", icon = Icons.Outlined.Check, accent = PrimaryViolet,
                modifier = Modifier.fillMaxWidth(),
                enabled = keyText.isNotBlank() && valueText.isNotBlank()
            ) { onSave(keyText, valueText) }

            // Divider "or"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(GlassBorder))
                Text("  OR  ", fontFamily = SpaceGrotesk, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
                Box(Modifier.weight(1f).height(1.dp).background(GlassBorder))
            }

            PillButton(
                text = if (factMode) "Listening…" else "Say it out loud",
                icon = Icons.Outlined.Mic,
                accent = CyanAccent,
                modifier = Modifier.fillMaxWidth(),
                filled = false
            ) { onVoiceFact() }
            Text(
                "Tip: say \"my mom is Amma\" and Naomi will store it for you.",
                fontFamily = InterFamily, fontSize = 12.sp,
                color = OnSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// ── SETTINGS ────────────────────────────────────────────────────────────────
@Composable
private fun SettingsScreen(
    wakeEnabled: Boolean,
    smartMode: Boolean,
    voiceTrained: Boolean,
    setup: MainActivity.SetupStatus,
    voiceThreshold: Float,
    lastSim: Float,
    lastSimAt: Long,
    lastSpeakerSim: Float,
    speakerCheck: Boolean?,
    voiceLog: List<String>,
    onClearVoiceLog: () -> Unit,
    languageUi: MainActivity.LanguageUi,
    onCycleLanguage: () -> Unit,
    onDownloadAnswers: () -> Unit,
    onThresholdChange: (Float) -> Unit,
    onBack: () -> Unit,
    onWakeToggle: () -> Unit,
    floating: Boolean,
    onFloatingToggle: () -> Unit,
    onSmartToggle: (Boolean) -> Unit,
    onTrain: () -> Unit,
    onForgetVoice: () -> Unit,
    onOpenSetup: (MainActivity.Setup) -> Unit,
    onNavigate: (MainActivity.Screen) -> Unit,
    brainSummary: String,
    onOpenBrain: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ScreenTopBar(title = "Settings", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SectionLabel("Voice")
            SettingActionRow(
                title = "Language",
                subtitle = languageUi.summary,
                icon = Icons.Outlined.Translate,
                accent = CyanAccent,
                done = false,
                onClick = onCycleLanguage
            )
            languageUi.answersModel?.let { answers ->
                SettingActionRow(
                    title = "Quick answers in Portuguese",
                    subtitle = answers,
                    icon = Icons.Outlined.Download,
                    accent = CyanAccent,
                    done = languageUi.answersReady,
                    onClick = onDownloadAnswers
                )
            }
            SettingToggleRow(
                title = "Hey Naomi",
                subtitle = "Always-listening wake word",
                icon = Icons.Outlined.MicNone,
                accent = CyanAccent,
                checked = wakeEnabled,
                onToggle = { onWakeToggle() }
            )
            SettingToggleRow(
                title = "Float over other apps",
                subtitle = when {
                    !setup.overlay -> "Needs \"Display over other apps\" — tap to allow"
                    floating -> "\"Naomi\" over another app answers there, in a small orb"
                    else -> "\"Naomi\" always opens the app"
                },
                icon = Icons.Outlined.PictureInPictureAlt,
                accent = CyanAccent,
                checked = floating && setup.overlay,
                onToggle = { onFloatingToggle() }
            )
            SettingToggleRow(
                title = "Smart Mode",
                subtitle = brainSummary,
                icon = Icons.Outlined.AutoAwesome,
                accent = MagentaAccent,
                checked = smartMode,
                onToggle = { onSmartToggle(!smartMode) }
            )
            SettingActionRow(
                title = "Brain & personality",
                subtitle = "Pick the AI, add its key, shape who Naomi is",
                icon = Icons.Outlined.Psychology,
                accent = MagentaAccent,
                done = false,
                onClick = onOpenBrain
            )
            SettingActionRow(
                title = if (voiceTrained) "Re-train my voice" else "Train my voice",
                subtitle = if (voiceTrained) "Voice lock on — strangers are refused" else "So only you can wake Naomi",
                icon = Icons.Outlined.RecordVoiceOver,
                accent = PrimaryViolet,
                done = voiceTrained,
                onClick = onTrain
            )
            if (voiceTrained) {
                SettingActionRow(
                    title = "Remove my voice",
                    subtitle = "Turns the voice lock off — anyone can wake Naomi",
                    icon = Icons.Outlined.Delete,
                    accent = RecordingRed,
                    done = false,
                    onClick = onForgetVoice
                )
            }
            VoiceMatchCard(
                threshold = voiceThreshold,
                lastSim = lastSim,
                lastSimAt = lastSimAt,
                lastSpeakerSim = lastSpeakerSim,
                speakerCheck = speakerCheck,
                onThresholdChange = onThresholdChange
            )
            VoiceLogCard(lines = voiceLog, onClear = onClearVoiceLog)

            Spacer(Modifier.height(8.dp))
            SectionLabel("Setup — permissions Naomi needs")
            SetupRow(
                title = "Microphone",
                subtitle = "Required to hear you",
                icon = Icons.Outlined.Mic,
                granted = setup.mic,
                onClick = { onOpenSetup(MainActivity.Setup.MIC) }
            )
            SetupRow(
                title = "App control",
                subtitle = "Accessibility — lets Naomi tap & type in apps",
                icon = Icons.Outlined.Accessibility,
                granted = setup.accessibility,
                onClick = { onOpenSetup(MainActivity.Setup.ACCESSIBILITY) }
            )
            SetupRow(
                title = "Unrestricted battery",
                subtitle = "Keeps \"Hey Naomi\" alive in the background",
                icon = Icons.Outlined.BatteryChargingFull,
                granted = setup.battery,
                onClick = { onOpenSetup(MainActivity.Setup.BATTERY) }
            )
            SetupRow(
                title = "Display over apps",
                subtitle = "Pops Naomi up when you call her",
                icon = Icons.Outlined.Layers,
                granted = setup.overlay,
                onClick = { onOpenSetup(MainActivity.Setup.OVERLAY) }
            )
            SetupRow(
                title = "Notifications",
                subtitle = "Shows the listening status",
                icon = Icons.Outlined.Notifications,
                granted = setup.notifications,
                onClick = { onOpenSetup(MainActivity.Setup.NOTIFICATIONS) }
            )
            SetupRow(
                title = "All app permissions",
                subtitle = "Contacts, phone, SMS, calendar, location",
                icon = Icons.Outlined.Lock,
                granted = null,
                onClick = { onOpenSetup(MainActivity.Setup.ALL_PERMISSIONS) }
            )
            SetupRow(
                title = "Set as default assistant",
                subtitle = "Optional — launch Naomi with the home gesture",
                icon = Icons.Outlined.AutoAwesome,
                granted = null,
                onClick = { onOpenSetup(MainActivity.Setup.ASSISTANT) }
            )
            Spacer(Modifier.height(12.dp))
        }

        NaomiBottomBar(
            current = MainActivity.Screen.SETTINGS,
            factCount = -1,
            onNavigate = onNavigate
        )
    }
}

// ── BRAIN ─────────────────────────────────────────────────────────────────────
/** Who thinks for Naomi in smart mode (provider, key, model) and who she is (personality). */
@Composable
private fun BrainScreen(
    ui: MainActivity.BrainUi,
    smartMode: Boolean,
    onBack: () -> Unit,
    onSmartToggle: (Boolean) -> Unit,
    actions: MainActivity.BrainActions,
) {
    // Drafts, re-seeded whenever the provider or its saved values change. The key field starts
    // empty — a saved key is never shown back.
    var key by remember(ui.provider) { mutableStateOf("") }
    var model by remember(ui.provider, ui.model) { mutableStateOf(ui.model) }
    var baseUrl by remember(ui.baseUrl) { mutableStateOf(ui.baseUrl) }
    var searchUrl by remember(ui.searchUrl) { mutableStateOf(ui.searchUrl) }
    var name by remember(ui.userName) { mutableStateOf(ui.userName) }
    var persona by remember(ui.persona) { mutableStateOf(ui.persona) }
    // Keys, model ids and URLs: no auto-capitalisation or autocorrect.
    val plain = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri
    )

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        ScreenTopBar(title = "Brain & Personality", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SettingToggleRow(
                title = "Smart Mode",
                subtitle = if (ui.configured) "Talk through ${ui.provider.label}" else "Set up a brain below first",
                icon = Icons.Outlined.AutoAwesome,
                accent = MagentaAccent,
                checked = smartMode,
                onToggle = { onSmartToggle(!smartMode) }
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("Who's the brain")
            Provider.entries.forEach { p ->
                val selected = p == ui.provider
                SettingActionRow(
                    title = p.label,
                    subtitle = if (selected) ui.model.ifBlank { "Set a model below" }
                               else p.defaultModel.ifBlank { "Your own server" },
                    icon = Icons.Outlined.Psychology,
                    accent = if (selected) CyanAccent else OutlineColor,
                    done = selected,
                    onClick = { actions.onSelectProvider(p) }
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("${ui.provider.label} setup")
            if (ui.provider == Provider.CUSTOM) {
                NaomiTextField(baseUrl, { baseUrl = it }, "Base URL (ends in /v1)", keyboardOptions = plain)
            }
            NaomiTextField(
                value = key,
                onValueChange = { key = it },
                label = when {
                    ui.hasKey -> "API key — saved (type to replace)"
                    ui.provider == Provider.CUSTOM -> "API key (only if the server needs one)"
                    else -> "API key"
                },
                password = true,
                keyboardOptions = plain.copy(keyboardType = KeyboardType.Password)
            )
            NaomiTextField(model, { model = it }, "Model", keyboardOptions = plain)
            Text(
                ui.provider.hint,
                fontFamily = InterFamily, fontSize = 12.sp,
                color = OnSurfaceVariant.copy(alpha = 0.6f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Save", icon = Icons.Outlined.Check, accent = SuccessGreen,
                    modifier = Modifier.weight(1f)
                ) { actions.onSave(key, model, baseUrl); key = "" }
                PillButton(
                    text = if (ui.testing) "Testing…" else "Test", icon = Icons.Outlined.GraphicEq,
                    accent = CyanAccent, modifier = Modifier.weight(1f), filled = false,
                    enabled = !ui.testing
                ) { actions.onTest(key, model, baseUrl) }
            }
            ui.notice?.let { notice ->
                Text(
                    notice,
                    fontFamily = InterFamily, fontSize = 13.sp,
                    color = when {
                        notice.startsWith("✓") -> SuccessGreen
                        notice.startsWith("✗") -> RecordingRed
                        else -> OnSurfaceVariant
                    }
                )
            }
            if (ui.hasKey) {
                Text(
                    "Forget the saved key",
                    fontFamily = InterFamily, fontSize = 13.sp,
                    color = RecordingRed.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clickable(onClick = actions.onForgetKey)
                        .padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("Looking things up")
            NaomiTextField(searchUrl, { searchUrl = it }, "SearXNG server (optional)", keyboardOptions = plain)
            Text(
                "In smart mode she answers questions about news, scores, prices and the like by searching first, " +
                    "and reads the top pages when the results alone don't say. Your own SearXNG with JSON output on, " +
                    "e.g. http://<pc>.<tailnet>.ts.net:8888 (see pc/searxng in the repo). Blank: the one on your Custom " +
                    "brain's PC at port 8888, if there is one. When it can't be reached, she searches DuckDuckGo from the phone.",
                fontFamily = InterFamily, fontSize = 12.sp,
                color = OnSurfaceVariant.copy(alpha = 0.6f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Save", icon = Icons.Outlined.Check, accent = SuccessGreen,
                    modifier = Modifier.weight(1f)
                ) { actions.onSaveSearch(searchUrl) }
                PillButton(
                    text = if (ui.testingSearch) "Testing…" else "Test", icon = Icons.Outlined.GraphicEq,
                    accent = CyanAccent, modifier = Modifier.weight(1f), filled = false,
                    enabled = !ui.testingSearch
                ) { actions.onTestSearch(searchUrl) }
            }
            ui.searchNotice?.let { notice ->
                Text(
                    notice,
                    fontFamily = InterFamily, fontSize = 13.sp,
                    color = if (notice.startsWith("✓")) SuccessGreen else RecordingRed
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("Personality")
            NaomiTextField(name, { name = it }, "What should Naomi call you?")
            NaomiTextField(persona, { persona = it }, "Who Naomi is", singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Save", icon = Icons.Outlined.Check, accent = PrimaryViolet,
                    modifier = Modifier.weight(1f)
                ) { actions.onSavePersona(name, persona) }
                PillButton(
                    text = "Reset", icon = Icons.Outlined.Close, accent = OutlineColor,
                    modifier = Modifier.weight(1f), filled = false
                ) { actions.onResetPersona() }
            }
            SettingToggleRow(
                title = "Conversation mode",
                subtitle = "Keep listening after I answer — no \"Naomi\" needed",
                icon = Icons.Outlined.RecordVoiceOver,
                accent = CyanAccent,
                checked = ui.conversation,
                onToggle = { actions.onConversationToggle(!ui.conversation) }
            )
            Text(
                "In smart mode, what you say, the recent conversation, your saved facts and the memories " +
                    "related to it go to ${ui.provider.label}, and what she looks up goes to your SearXNG or DuckDuckGo. " +
                    "With it off, nothing leaves the phone.",
                fontFamily = InterFamily, fontSize = 12.sp,
                color = OnSurfaceVariant.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ── Shared building blocks ────────────────────────────────────────────────────

@Composable
private fun LogoBadge() {
    Box(
        Modifier
            .size(32.dp)
            .background(Brush.linearGradient(listOf(PrimaryViolet, CyanAccent)), CircleShape)
            .border(1.dp, GlassBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color(0xFF0F131F), fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = OnSurface)
        }
        Text(
            title,
            fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp, color = OnSurface,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 1.5.sp,
        color = OnSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, if (checked) accent.copy(alpha = 0.4f) else GlassBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onToggle
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, if (checked) accent else OutlineColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
            Text(subtitle, fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent.copy(alpha = 0.8f),
                uncheckedThumbColor = OutlineColor,
                uncheckedTrackColor = SurfaceHigh
            )
        )
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    done: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
            Text(subtitle, fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        if (done) Icon(Icons.Outlined.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
    }
}

/**
 * Voice-match tuning: a slider for the ECAPA similarity threshold plus a read-out of the last
 * observed score, so the user can fine-tune it themselves. The last score is compared live
 * against the slider position, so dragging shows whether that attempt would now pass.
 */
@Composable
private fun VoiceMatchCard(
    threshold: Float,
    lastSim: Float,
    lastSimAt: Long,
    lastSpeakerSim: Float,
    speakerCheck: Boolean?,
    onThresholdChange: (Float) -> Unit,
) {
    var pos by remember(threshold) { mutableStateOf(threshold) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Outlined.GraphicEq, CyanAccent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice match strictness", fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
                Text("Higher = only your voice wakes Naomi", fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
            Text(
                String.format(java.util.Locale.US, "%.2f", pos),
                fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CyanAccent
            )
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onThresholdChange(pos) },
            valueRange = VOICE_THRESHOLD_MIN..VOICE_THRESHOLD_MAX,
            colors = SliderDefaults.colors(
                thumbColor = CyanAccent,
                activeTrackColor = CyanAccent.copy(alpha = 0.85f),
                inactiveTrackColor = SurfaceHigh
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lenient", fontFamily = InterFamily, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
            Text("Strict", fontFamily = InterFamily, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(10.dp))
        if (lastSim >= 0f) {
            val passed = lastSim >= pos
            val rel = DateUtils.getRelativeTimeSpanString(
                lastSimAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Last wake attempt:", fontFamily = InterFamily, fontSize = 13.sp, color = OnSurfaceVariant)
                Text(
                    String.format(java.util.Locale.US, "%.2f", lastSim) + (if (passed) " · would match" else " · would reject"),
                    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = if (passed) SuccessGreen else RecordingRed
                )
            }
            Text(rel, fontFamily = InterFamily, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
        } else {
            Text(
                "No verified wake yet. Say \"Naomi\" — the score shows here.",
                fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.height(8.dp))
        // Every sentence is voice-checked too, when the speech service lets us hear it.
        Text(
            when (speakerCheck) {
                false -> "Voice check on each sentence: not possible with this phone's speech service."
                null -> "Voice check on each sentence: starts with your next command."
                true -> if (lastSpeakerSim < 0f) "Voice check on each sentence: on."
                        else "Last sentence: " + (if (lastSpeakerSim >= pos) "you" else "someone else") +
                            String.format(java.util.Locale.US, " (%.2f)", lastSpeakerSim)
            },
            fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * What the voice pipeline did lately, newest first: each "Naomi" heard or passed over and why,
 * each voice check's score, each sentence answered or ignored, recognizer errors. When she
 * doesn't respond somewhere, this says which step dropped it.
 */
@Composable
private fun VoiceLogCard(lines: List<String>, onClear: () -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Outlined.RecordVoiceOver, PrimaryViolet)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice log", fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
                Text("What happened to each \"Naomi\" and sentence", fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
            if (lines.isNotEmpty()) {
                Text(
                    "Clear", fontFamily = InterFamily, fontSize = 13.sp, color = OutlineColor,
                    modifier = Modifier.clickable(onClick = onClear).padding(8.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text(
                "Nothing yet. Say \"Naomi\" and what happens shows up here.",
                fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.55f)
            )
        } else {
            (if (showAll) lines else lines.take(12)).forEach { line ->
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    color = OnSurfaceVariant.copy(alpha = 0.85f), modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            if (lines.size > 12) {
                Text(
                    if (showAll) "Show less" else "Show all ${lines.size}",
                    fontFamily = InterFamily, fontSize = 13.sp, color = CyanAccent,
                    modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * A permission/access row: shows a green tick when granted, or a "Set up" chip when not.
 * [granted] = null means we can't reliably detect it (e.g. runtime permission bundles).
 */
@Composable
private fun SetupRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    granted: Boolean?,
    onClick: () -> Unit,
) {
    val borderColor = when (granted) {
        true -> SuccessGreen.copy(alpha = 0.35f)
        else -> GlassBorder
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(GlassFill, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, if (granted == true) SuccessGreen else PrimaryViolet)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = OnSurface)
            Text(subtitle, fontFamily = InterFamily, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        if (granted == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = "Enabled", tint = SuccessGreen, modifier = Modifier.size(18.dp))
            }
        } else {
            Text(
                "Set up",
                fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                color = BgColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PrimaryViolet)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(38.dp)
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** A rounded action button — filled (gradient-ish tint) or outlined. */
@Composable
private fun PillButton(
    text: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (filled) Modifier.background(accent.copy(alpha = 0.9f * alpha))
                else Modifier.border(1.5.dp, accent.copy(alpha = 0.6f * alpha), RoundedCornerShape(50))
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = (if (filled) BgColor else accent).copy(alpha = alpha),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = (if (filled) BgColor else accent).copy(alpha = alpha)
        )
    }
}

@Composable
private fun NaomiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    password: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = InterFamily, fontSize = 13.sp) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanAccent.copy(alpha = 0.7f),
            unfocusedBorderColor = GlassBorder,
            focusedTextColor = OnSurface,
            unfocusedTextColor = OnSurface,
            cursorColor = CyanAccent,
            focusedLabelColor = CyanAccent,
            unfocusedLabelColor = OnSurfaceVariant.copy(alpha = 0.6f),
            focusedContainerColor = GlassFill,
            unfocusedContainerColor = GlassFill,
        )
    )
}

// ── Bottom navigation bar (Home / Memory / Settings) ───────────────────────────
@Composable
private fun NaomiBottomBar(
    current: MainActivity.Screen,
    factCount: Int,
    onNavigate: (MainActivity.Screen) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BgColor.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(GlassBorder, Color.Transparent)),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavItem(
            icon = Icons.Outlined.Home, label = "Home",
            selected = current == MainActivity.Screen.HOME,
            onClick = { onNavigate(MainActivity.Screen.HOME) }
        )
        BottomNavItem(
            icon = Icons.Outlined.Bookmark,
            label = if (factCount > 0) "Memory ($factCount)" else "Memory",
            selected = current == MainActivity.Screen.FACTS,
            onClick = { onNavigate(MainActivity.Screen.FACTS) }
        )
        BottomNavItem(
            icon = Icons.Outlined.Settings, label = "Settings",
            selected = current == MainActivity.Screen.SETTINGS,
            onClick = { onNavigate(MainActivity.Screen.SETTINGS) }
        )
    }
}

@Composable
private fun BottomNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) CyanAccent else OutlineColor
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp, color = tint)
    }
}

// ── Transcript card ───────────────────────────────────────────────────────────
@Composable
private fun TranscriptCard(transcript: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 240.dp)
            .background(GlassFill, RoundedCornerShape(20.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        contentAlignment = if (transcript.isBlank()) Alignment.Center else Alignment.TopStart
    ) {
        if (transcript.isBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("💬", fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your conversation appears here",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = OnSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val lines = transcript.split("\n\n")
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                lines.forEach { line ->
                    val isNaomi = line.startsWith("Naomi:")
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isNaomi) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .offset(y = 5.dp)
                                    .background(PrimaryViolet, CircleShape)
                            )
                        }
                        Text(
                            line,
                            fontFamily = InterFamily,
                            fontSize = 15.sp,
                            color = if (isNaomi) OnSurface else OnSurface.copy(alpha = 0.75f),
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Splash screen ─────────────────────────────────────────────────────────────
/**
 * One vertical strip of the "N" — the glyph is sliced into upright columns (Netflix-ribbon
 * style). Positions are offsets from the canvas centre, in px.
 */
private data class NStrip(
    val cx: Float,          // target centre X (relative to canvas centre)
    val yc: Float,          // target centre Y
    val halfH: Float,       // target half-height of the column
    val strokeW: Float,     // draw width of the column
    val flightColor: Color, // vivid colour while swirling in
    val restColor: Color,   // brand colour once assembled into the solid N
    val turns: Float,       // spiral revolutions on the way in (signed)
    val distNorm: Float,    // |cx| / (halfWidth): 0 at centre → 1 at outer edge
    val entryStart: Float,  // 0..1 stagger for the swirl-in
    val exitStart: Float    // 0..1 stagger for the spread-out
)

/** Palette the strips flash through while swirling in from the ring. */
private val SHARD_COLORS = listOf(
    Color(0xFF00D9FF), // cyan
    Color(0xFF8D7FFF), // violet
    Color(0xFFDE4DFF), // magenta
    Color(0xFF3DD68C), // green
    Color(0xFFFFB454), // amber
    Color(0xFFFF5C8A), // pink
    Color(0xFF5AA9FF), // blue
    Color(0xFFFFFFFF), // white spark
)

/** Slice the "N" into [K] vertical strips: full-height on the two bars, a descending band
 *  through the diagonal in the middle. */
private fun buildNStrips(s: Float): List<NStrip> {
    val h   = s * 0.72f          // glyph height
    val w   = s * 0.56f          // glyph width (outer edge to outer edge)
    val bar = w * 0.24f          // width of each vertical bar
    val k   = 50                 // number of vertical strips
    val stripW = w / k
    val innerL = -w / 2f + bar   // inner edge of the left bar
    val innerR =  w / 2f - bar   // inner edge of the right bar
    val bandHalf = bar * 0.72f   // vertical half-thickness of the diagonal band
    val halfW = w / 2f

    val out = ArrayList<NStrip>()
    for (i in 0 until k) {
        val cx = -w / 2f + (i + 0.5f) * stripW
        val yc: Float
        val halfH: Float
        if (cx <= innerL || cx >= innerR) {
            yc = 0f; halfH = h / 2f                          // left / right bar — full height
        } else {
            val f = (cx - innerL) / (innerR - innerL)        // 0..1 across the middle
            yc = -h / 2f + bandHalf + f * (h - 2f * bandHalf) // diagonal descends top-left→bottom-right
            halfH = bandHalf
        }
        val g = ((yc + h / 2f) / h).coerceIn(0f, 1f)
        out += NStrip(
            cx = cx, yc = yc, halfH = halfH, strokeW = stripW,
            flightColor = SHARD_COLORS[i % SHARD_COLORS.size],
            restColor = lerpColor(Color(0xFF8D7FFF), Color(0xFF00D9FF), g),
            turns = if (i % 2 == 0) 1.6f else -2.0f,
            distNorm = (abs(cx) / halfW).coerceIn(0f, 1f),
            entryStart = (abs(cx) / halfW) * 0.35f,           // centre assembles first, edges last
            exitStart  = (1f - abs(cx) / halfW) * 0.16f       // edges spread first
        )
    }
    return out
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red   = a.red   + (b.red   - a.red)   * t,
    green = a.green + (b.green - a.green) * t,
    blue  = a.blue  + (b.blue  - a.blue)  * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

/**
 * Netflix-ribbon "N" splash:
 *  A) coloured vertical strips swirl out from a central ring and pack tightly into a solid N,
 *  B) hold as the colours settle into the brand gradient,
 *  C) the strips slide apart horizontally while growing taller — a 3D "exploding blinds" reveal.
 * Shown on manual launches only — not on voice wake, to avoid startup lag.
 */
@Composable
private fun NaomiSplash(onComplete: () -> Unit) {
    val entry   = remember { Animatable(0f) } // 0..1 swirl-in + assemble
    val settle  = remember { Animatable(0f) } // 0..1 flight→brand colour blend
    val exit    = remember { Animatable(0f) } // 0..1 spread-out + grow
    val screenAlpha = remember { Animatable(1f) }
    val glow    = remember { Animatable(0f) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        launch { glow.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing)) }
        entry.animateTo(1f, animationSpec = tween(1150, easing = FastOutSlowInEasing))
        settle.animateTo(1f, animationSpec = tween(340, easing = LinearEasing))
        delay(320)
        launch {
            delay(300)
            screenAlpha.animateTo(0f, animationSpec = tween(460, easing = LinearEasing))
        }
        exit.animateTo(1f, animationSpec = tween(720, easing = FastOutSlowInEasing))
        onComplete()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(BgColor),
        contentAlignment = Alignment.Center
    ) {
        // Ambient radial glow that blooms with the assembly, then dims as the strips fly apart.
        Box(
            Modifier
                .size((200 * (0.4f + glow.value * 0.8f)).dp)
                .alpha(0.35f * glow.value * (1f - exit.value))
                .background(
                    Brush.radialGradient(listOf(PrimaryViolet.copy(alpha = 0.30f), Color.Transparent)),
                    CircleShape
                )
        )

        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension * 0.5f
            val strips = buildNStrips(s)
            val cx0 = size.width / 2f
            val cy0 = size.height / 2f
            val twoPi = (Math.PI * 2f).toFloat()
            val ringR = s * 0.06f          // radius of the ring the strips start on

            // How far apart the strips fan, and how much taller they grow, during the exit.
            val spread = 2.4f
            val grow   = 2.2f

            for (st in strips) {
                // Phase A — swirl out from the ring and pack into the N (staggered per strip).
                val eRaw = ((entry.value - st.entryStart) / (1f - st.entryStart)).coerceIn(0f, 1f)
                val e = FastOutSlowInEasing.transform(eRaw)

                val targetRad = hypot(st.cx, st.yc)
                val targetAng = atan2(st.yc, st.cx)
                val rad = ringR + (targetRad - ringR) * e
                val ang = targetAng + (1f - e) * st.turns * twoPi

                var px = cx0 + rad * cos(ang)
                var py = cy0 + rad * sin(ang)
                var halfH = st.halfH * (0.05f + 0.95f * e)
                var alpha = eRaw
                val color = lerpColor(st.flightColor, st.restColor, settle.value)

                // Phase C — slide apart horizontally + grow taller (outer strips grow most → 3D).
                if (exit.value > 0f) {
                    val xRaw = ((exit.value - st.exitStart) / (1f - st.exitStart)).coerceIn(0f, 1f)
                    val xe = FastOutSlowInEasing.transform(xRaw)
                    px = cx0 + st.cx * (1f + spread * xe)
                    py = cy0 + st.yc
                    halfH = st.halfH * (1f + grow * xe * (0.6f + st.distNorm))
                    alpha = 1f - xe * xe
                }

                if (alpha <= 0.01f) continue
                drawLine(
                    color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                    start = Offset(px, py - halfH),
                    end   = Offset(px, py + halfH),
                    strokeWidth = st.strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
