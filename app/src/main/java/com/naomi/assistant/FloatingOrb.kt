package com.naomi.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Naomi floating over other apps: her orb, small, and a line of the conversation, in an overlay
 * window — so a "Naomi" heard while another app is open answers there instead of taking the
 * screen from it. It carries on the same [Conversation] as the app (the arrow opens it there),
 * can be dragged out of the way, and goes once the conversation is over.
 *
 * It needs "display over other apps", and only floats on an unlocked phone that's awake: over the
 * lock screen, Android shows no overlays, so the app opens there as before (see [canShow]).
 * Main thread only.
 */
object FloatingOrb : Conversation.Host {

    private const val PREF = "floating_orb"
    // Where it was dragged to last: offsets from the bottom centre of the screen, in pixels.
    private const val PREF_X = "floating_orb_x"
    private const val PREF_Y = "floating_orb_y"

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var windows: WindowManager? = null
    private var watcher: Job? = null
    private val scope = MainScope()

    /** The choice in Settings: float over other apps (on by default), or open the app every time. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(PREF, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PREF, on).apply()

    /** Whether Naomi may float over other apps: allowed, wanted, and on an awake, unlocked phone. */
    fun canShow(context: Context): Boolean =
        enabled(context) && Settings.canDrawOverlays(context) &&
            context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    /** Shows the orb (if it isn't up already) as the conversation's screen. */
    fun show(context: Context) {
        val app = context.applicationContext
        val session = Conversation.get(app)
        session.host = this
        if (view != null) return
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val density = app.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the keyboard or touches outside it from the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Where the user last left it — kept on screen — or low in the middle.
            val screen = app.resources.displayMetrics
            val prefs = prefs(app)
            x = prefs.getInt(PREF_X, 0).coerceIn(-screen.widthPixels / 2, screen.widthPixels / 2)
            y = prefs.getInt(PREF_Y, (BOTTOM_MARGIN_DP * density).roundToInt())
                .coerceIn(0, (screen.heightPixels - MIN_ROOM_DP * density).roundToInt().coerceAtLeast(0))
        }
        val lifecycle = OverlayOwner().also { it.start() }
        val compose = ComposeView(app).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                FloatingOrbUi(
                    session = session,
                    onDrag = { dx, dy -> moveBy(dx, dy) },
                    onDragEnd = { params?.let { prefs(app).edit().putInt(PREF_X, it.x).putInt(PREF_Y, it.y).apply() } },
                    onOpen = { openApp(app) },
                    onClose = { session.reset(); hide() },
                )
            }
        }
        runCatching { wm.addView(compose, lp) }.onFailure {
            android.util.Log.w("Naomi", "Floating orb not shown: ${it.message}")
            lifecycle.stop()
            if (session.host === this) session.host = null
            return
        }
        view = compose
        owner = lifecycle
        params = lp
        windows = wm
        keepScreenOn(session.mood != Mood.IDLE)
        // Gone a moment after the conversation is: a reply said, nothing asked back.
        watcher = scope.launch {
            snapshotFlow { session.mood }.collectLatest { mood ->
                if (mood == Mood.IDLE) {
                    delay(LINGER_MS)
                    if (!session.busy) hide()
                }
            }
        }
    }

    /** Takes the orb down; the conversation, if any, stays with whichever screen shows it next. */
    fun hide() {
        val v = view ?: return
        watcher?.cancel(); watcher = null
        runCatching { windows?.removeView(v) }
        owner?.stop()
        view = null; owner = null; params = null; windows = null
        Conversation.existing()?.let { if (it.host === this) it.host = null }
    }

    override fun keepScreenOn(on: Boolean) {
        val lp = params ?: return
        val flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (flags == lp.flags) return
        lp.flags = flags
        runCatching { windows?.updateViewLayout(view, lp) }
    }

    /** Dragged by [dx], [dy] pixels (it hangs from the bottom, so up is a bigger y). */
    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        lp.x += dx.roundToInt()
        lp.y -= dy.roundToInt()
        runCatching { windows?.updateViewLayout(view, lp) }
    }

    /** The conversation, carried on in the full app. */
    private fun openApp(context: Context) {
        hide()
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun prefs(context: Context) = context.getSharedPreferences("naomi", Context.MODE_PRIVATE)

    // How far above the bottom of the screen it floats, clear of the navigation bar.
    private const val BOTTOM_MARGIN_DP = 72
    // Room kept above it when put back where it was left, so a turned screen can't lose it.
    private const val MIN_ROOM_DP = 200
    // How long it stays after the conversation ends, so the last reply can be read.
    private const val LINGER_MS = 2_500L

    /** What a ComposeView needs around it outside an activity: a lifecycle and saved state. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

/**
 * The floating orb: a line of the conversation over a small orb. The line is what she's doing
 * while she listens, what she heard while she thinks, and her reply once she has one.
 */
@Composable
private fun FloatingOrbUi(
    session: Conversation,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val exchange = session.transcript
    val line = when (session.mood) {
        Mood.LISTENING -> session.status
        Mood.THINKING -> exchange.substringBefore("\n\n")
        else -> exchange.substringAfter("\n\n", exchange)
    }.ifBlank { session.status }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .widthIn(max = 320.dp)
                .background(BgColor.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
                .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                line,
                color = OnSurface,
                fontFamily = InterFamily,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp),
            )
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open Naomi", tint = OnSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = OnSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.pointerInput(Unit) {
            detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                change.consume()
                onDrag(drag.x, drag.y)
            }
        }) {
            // A tap cuts her off and listens, as on the app's orb.
            NaomiOrb(mood = session.mood, isRecording = VoiceRecorder.isRecording, onTap = { session.tapped() }, orbSize = 60.dp)
        }
    }
}
