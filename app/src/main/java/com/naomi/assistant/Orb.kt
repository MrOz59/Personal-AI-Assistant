package com.naomi.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// ── Orbit stars ───────────────────────────────────────────────────────────────
private val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

private data class OrbitStar(
    val rMult: Float, val a0Deg: Float, val yScale: Float,
    val dotR: Float, val alpha: Float,
    val tint: Color = Color.White, val reversed: Boolean = false
)

private val ORBIT_STARS = listOf(
    // inner ring (clockwise)
    OrbitStar(1.32f,   0f, 0.40f, 2.5f, 0.85f, Color(0xFF00D9FF)),
    OrbitStar(1.40f,  45f, 0.60f, 1.8f, 0.60f),
    OrbitStar(1.35f,  90f, 0.35f, 3.0f, 0.80f, Color(0xFFC6BFFF)),
    OrbitStar(1.44f, 135f, 0.55f, 2.0f, 0.55f),
    OrbitStar(1.38f, 180f, 0.45f, 2.5f, 0.90f, Color(0xFF00D9FF)),
    OrbitStar(1.42f, 225f, 0.50f, 1.5f, 0.60f, Color(0xFFC6BFFF)),
    OrbitStar(1.30f, 270f, 0.65f, 3.0f, 0.85f),
    OrbitStar(1.46f, 315f, 0.30f, 2.0f, 0.65f),
    // outer ring (counter-clockwise)
    OrbitStar(1.55f,  22f, 0.50f, 1.8f, 0.35f, Color(0xFF00D9FF), reversed = true),
    OrbitStar(1.60f, 112f, 0.40f, 2.0f, 0.30f, reversed = true),
    OrbitStar(1.52f, 202f, 0.55f, 1.5f, 0.35f, Color(0xFFC6BFFF), reversed = true),
    OrbitStar(1.58f, 292f, 0.45f, 1.8f, 0.28f, reversed = true),
)

// ── Voice Orb ─────────────────────────────────────────────────────────────────
/**
 * Naomi's orb, drawn [orbSize] across (180 dp in the app, smaller floating over other apps): its
 * colour and motion say what she's doing — rings while listening, a spinning arc while
 * thinking, a slow breath otherwise — with stars orbiting it all the time.
 */
@Composable
internal fun NaomiOrb(
    mood: Mood,
    isRecording: Boolean,
    onTap: () -> Unit,
    orbSize: Dp = 180.dp,
) {
    val orbColor by animateColorAsState(
        targetValue = when {
            isRecording                          -> RecordingRed
            mood == Mood.LISTENING -> CyanAccent
            mood == Mood.THINKING  -> PrimaryViolet
            mood == Mood.SPEAKING  -> MagentaAccent
            else                                -> PrimaryViolet
        },
        animationSpec = tween(400),
        label = "orbColor"
    )

    val inf = rememberInfiniteTransition(label = "orb")

    // Idle / speaking / recording: gentle breathe
    val breathe by inf.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse),
        label = "breathe"
    )

    // Listening: three ring expansion values (staggered)
    val ring1 by inf.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "ring1"
    )
    val ring2 by inf.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, delayMillis = 600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring2"
    )
    val ring3 by inf.animateFloat(
        initialValue = 1f, targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1800, delayMillis = 1200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring3"
    )

    // Thinking: spinning arc
    val arcRotation by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "arcRot"
    )

    // Stars orbiting the orb (always active)
    val orbitAngle by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit"
    )

    // Everything is drawn to the app's orb and scaled from it.
    val zoom = orbSize / 180.dp
    val area = orbSize * (300f / 180f)

    Box(
        Modifier.size(area),
        contentAlignment = Alignment.Center
    ) {
        // Rings (LISTENING only)
        if (mood == Mood.LISTENING) {
            listOf(ring1, ring2, ring3).forEach { s ->
                val alpha = (1f - (s - 1f) / 1.4f).coerceIn(0f, 0.6f)
                Box(
                    Modifier
                        .size(orbSize)
                        .scale(s)
                        .border(2.dp, CyanAccent.copy(alpha = alpha), CircleShape)
                )
            }
        }

        // Ambient glow halo
        Box(
            Modifier
                .size(orbSize + 40.dp * zoom)
                .background(
                    Brush.radialGradient(
                        listOf(orbColor.copy(alpha = 0.18f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        // Orbiting stars
        Canvas(Modifier.size(area)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val orbPx = (orbSize / 2).toPx()
            ORBIT_STARS.forEach { star ->
                val a = (if (star.reversed) -orbitAngle + star.a0Deg
                         else orbitAngle + star.a0Deg) * DEG_TO_RAD
                val r = orbPx * star.rMult
                val x = cx + r * cos(a)
                val y = cy + r * sin(a) * star.yScale
                drawCircle(
                    color = star.tint.copy(alpha = star.alpha),
                    radius = (star.dotR.dp * zoom).toPx(),
                    center = Offset(x, y)
                )
            }
        }

        // Thinking: spinning arc on canvas
        if (mood == Mood.THINKING) {
            Canvas(Modifier.size(orbSize + 20.dp * zoom)) {
                rotate(arcRotation) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, PrimaryViolet, Color.Transparent)),
                        startAngle = 0f,
                        sweepAngle = 160f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        // Core orb
        val coreScale = when (mood) {
            Mood.LISTENING -> 1f
            else                        -> breathe
        }
        Box(
            Modifier
                .size(orbSize)
                .scale(coreScale)
                .background(
                    Brush.radialGradient(
                        listOf(orbColor.copy(alpha = 0.9f), orbColor.copy(alpha = 0.5f))
                    ),
                    CircleShape
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.05f))
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner glass highlight
            Box(
                Modifier
                    .size(orbSize * 0.55f)
                    .offset(x = (-12).dp * zoom, y = (-16).dp * zoom)
                    .background(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)),
                        CircleShape
                    )
            )
        }
    }
}
