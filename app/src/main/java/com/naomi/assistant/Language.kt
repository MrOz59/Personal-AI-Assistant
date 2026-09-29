package com.naomi.assistant

import android.content.Context
import java.util.Locale

/**
 * The language Naomi hears and speaks, as picked in Settings: English, Brazilian Portuguese, or
 * Automatic — the phone's own language (Portuguese if the phone is in Portuguese, else English).
 * The recognizer, her voice and the cloud brain's replies all follow it.
 */
enum class Language(val label: String) {
    AUTO("Automatic"),
    ENGLISH("English"),
    PORTUGUESE("Português (Brasil)");

    /** What the brain is told to speak ("Brazilian Portuguese"), or null for English, its default. */
    val replyIn: String? get() = if (this == PORTUGUESE) "Brazilian Portuguese" else null

    companion object {
        private const val PREF = "speech_language"

        /** The choice in Settings (possibly [AUTO]). */
        fun chosen(context: Context): Language =
            prefs(context).getString(PREF, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: AUTO

        fun choose(context: Context, language: Language) = prefs(context).edit().putString(PREF, language.name).apply()

        /** The language she speaks right now: the choice, or for [AUTO], the phone's. Never [AUTO]. */
        fun current(context: Context): Language = resolve(chosen(context), Locale.getDefault())

        internal fun resolve(chosen: Language, phone: Locale): Language = when (chosen) {
            AUTO -> if (phone.language == "pt") PORTUGUESE else ENGLISH
            else -> chosen
        }

        /**
         * The speech recognizer's language: "pt-BR", or English as spoken where the phone is
         * ("en-AU" in Australia, see [VoiceInput.englishHere]).
         */
        fun speechTag(context: Context): String =
            if (current(context) == PORTUGUESE) "pt-BR" else VoiceInput.englishHere(context)

        private fun prefs(context: Context) = context.getSharedPreferences("naomi", Context.MODE_PRIVATE)
    }
}
