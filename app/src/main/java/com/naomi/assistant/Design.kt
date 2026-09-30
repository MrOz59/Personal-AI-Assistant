package com.naomi.assistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont

// Naomi's look, shared by the app and the floating orb.

// ── Design Tokens ────────────────────────────────────────────────────────────
internal val BgColor          = Color(0xFF0F131F)
internal val PrimaryViolet    = Color(0xFFC6BFFF)
internal val CyanAccent       = Color(0xFF00D9FF)
internal val MagentaAccent    = Color(0xFFDE4DFF)
internal val RecordingRed     = Color(0xFFFF4444)
internal val SuccessGreen     = Color(0xFF3DD68C)
internal val SurfaceHigh      = Color(0xFF262A37)
internal val OnSurface        = Color(0xFFDFE2F3)
internal val OnSurfaceVariant = Color(0xFFC8C4D7)
internal val OutlineColor     = Color(0xFF928EA0)

internal val GlassFill   = Color.White.copy(alpha = 0.08f)
internal val GlassBorder = Color.White.copy(alpha = 0.12f)

// ── Fonts ─────────────────────────────────────────────────────────────────────
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs
)
internal val SpaceGrotesk = FontFamily(
    Font(GoogleFont("Space Grotesk"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Space Grotesk"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Space Grotesk"), fontProvider, FontWeight.Bold),
)
internal val InterFamily = FontFamily(
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Medium),
)
