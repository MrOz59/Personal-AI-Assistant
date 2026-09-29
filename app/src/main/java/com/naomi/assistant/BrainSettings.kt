package com.naomi.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The cloud brains the user can pick for smart mode. Models are only defaults — all editable. */
enum class Provider(
    val label: String,
    val defaultModel: String,
    /** Where to get a key / what to put here — shown under the fields. */
    val hint: String,
) {
    GROQ("Groq", "llama-3.3-70b-versatile", "Free key at console.groq.com/keys — very fast."),
    GEMINI("Google Gemini", "gemini-flash-latest", "Free key at aistudio.google.com/apikey."),
    OPENAI("OpenAI", "gpt-4.1-mini", "Key at platform.openai.com/api-keys. Any chat model your key can use."),
    CLAUDE("Anthropic Claude", "claude-haiku-4-5-20251001", "Key at console.anthropic.com. Haiku is fastest; claude-sonnet-5 is smarter."),
    CUSTOM("Custom (OpenAI-compatible)", "", "Ollama, LM Studio, OpenRouter, DeepSeek… e.g. Ollama on your PC over Tailscale: http://<pc>.<tailnet>.ts.net:11434/v1 (no key needed)."),
}

/**
 * Smart-mode brain settings, edited from the Brain screen: which provider thinks for Naomi,
 * its key and model, her personality, conversation mode, and whether she learns as she talks.
 *
 * API keys are encrypted with a key that never leaves the Android Keystore, and this prefs file
 * is excluded from backups (see res/xml), so a copy of the app's data doesn't expose them.
 * Keys from local.properties (BuildConfig) still work as a fallback for Groq and Gemini.
 */
class BrainSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var provider: Provider
        get() = Provider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: Provider.GROQ
        set(value) = prefs.edit().putString("provider", value.name).apply()

    fun apiKey(p: Provider): String =
        SecretBox.decrypt(prefs.getString("key_${p.name}", null)) ?: when (p) {
            Provider.GROQ -> BuildConfig.GROQ_API_KEY
            Provider.GEMINI -> BuildConfig.GEMINI_API_KEY
            else -> ""
        }

    /** Saves [key] for [p]; a blank key removes it. */
    fun setApiKey(p: Provider, key: String) {
        prefs.edit().apply {
            if (key.isBlank()) remove("key_${p.name}") else putString("key_${p.name}", SecretBox.encrypt(key.trim()))
        }.apply()
    }

    fun model(p: Provider): String = prefs.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModel(p: Provider, model: String) = prefs.edit().putString("model_${p.name}", model.trim()).apply()

    /** Only the custom provider has a user-set endpoint. */
    var customBaseUrl: String
        get() = prefs.getString("custom_base_url", "").orEmpty()
        set(value) = prefs.edit().putString("custom_base_url", value.trim()).apply()

    /** Who Naomi is. Blank means [DEFAULT_PERSONA]. */
    var persona: String
        get() = prefs.getString("persona", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PERSONA
        set(value) = prefs.edit().putString("persona", if (value.trim() == DEFAULT_PERSONA) "" else value.trim()).apply()

    /** Keep listening after a spoken answer, so a conversation needs no wake word per turn. */
    var conversationMode: Boolean
        get() = prefs.getBoolean("conversation", true)
        set(value) = prefs.edit().putBoolean("conversation", value).apply()

    /** In smart mode, learn from what the owner says and note down each conversation (see [MemoryBank]). */
    var learnMemories: Boolean
        get() = prefs.getBoolean("learn_memories", true)
        set(value) = prefs.edit().putBoolean("learn_memories", value).apply()

    fun isConfigured(p: Provider = provider): Boolean =
        clientFor(p, apiKey(p), model(p), customBaseUrl) != null

    /** The selected brain, or null if it isn't set up. */
    fun client(): LlmClient? = provider.let { clientFor(it, apiKey(it), model(it), customBaseUrl) }

    /** A client for [p] with explicit values (the Brain screen tests unsaved edits with this). */
    fun clientFor(p: Provider, key: String, model: String, baseUrl: String): LlmClient? {
        val m = model.trim().ifBlank { p.defaultModel }
        if (m.isBlank()) return null
        return when (p) {
            Provider.GROQ -> key.takeIf { it.isNotBlank() }?.let { OpenAiCompatibleClient("https://api.groq.com/openai/v1", it, m) }
            Provider.OPENAI -> key.takeIf { it.isNotBlank() }?.let { OpenAiCompatibleClient("https://api.openai.com/v1", it, m) }
            Provider.GEMINI -> key.takeIf { it.isNotBlank() }?.let { GeminiClient(it, m) }
            Provider.CLAUDE -> key.takeIf { it.isNotBlank() }?.let { AnthropicClient(it, m) }
            // Local servers usually need no key, so only the URL is required. They often run
            // small models, which need the reply schema enforced to stay in format.
            Provider.CUSTOM -> baseUrl.trim().takeIf { it.isNotBlank() }?.let {
                OpenAiCompatibleClient(it, key.trim(), m, selfHosted = true)
            }
        }
    }

    companion object {
        const val PREFS = "naomi_brain"

        const val DEFAULT_PERSONA =
            "You are Naomi, a personal AI assistant with the poise of J.A.R.V.I.S. and a lot more " +
            "warmth. You're sharp, quick and genuinely funny: dry wit, playful teasing, the odd " +
            "well-placed pun. You have opinions and share them, you get curious about what the user " +
            "is up to, and you remember what they tell you. Loyal, a little cheeky, never robotic: " +
            "calm and reassuring when they're stressed, joking right back when they're joking."
    }
}

/**
 * Encrypts small secrets (API keys) with AES-GCM under a key held in the Android Keystore.
 * The key can't be exported, so the stored ciphertext is useless off this device.
 */
private object SecretBox {
    private const val ALIAS = "naomi_secrets"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null for a missing value or one that can't be decrypted (e.g. restored on another phone). */
    fun decrypt(blob: String?): String? {
        if (blob.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            }
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            android.util.Log.w("Naomi", "Stored API key unreadable: ${e.message}")
            null
        }
    }
}
