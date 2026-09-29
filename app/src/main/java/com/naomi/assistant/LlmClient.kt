package com.naomi.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One side of the conversation, as sent to a cloud model. */
data class Turn(val fromUser: Boolean, val text: String)

/** A failed cloud call, with a message short enough to show (or speak) to the user. */
class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A cloud chat model: system prompt + conversation in, the model's text out.
 * Three wire protocols cover every brain the user can pick (see [Provider]).
 */
interface LlmClient {
    /**
     * Blocking network call — run it off the main thread. Throws [LlmException].
     * [schema] is a JSON Schema (as JSON text) the reply should follow; clients that can
     * enforce it do. [creative] asks for livelier sampling (conversation) over steadier
     * sampling (decisions), where the client controls sampling.
     */
    fun complete(system: String, turns: List<Turn>, schema: String? = null, creative: Boolean = false): String

    /**
     * A small self-hosted model: too weak to pick actions and hold a conversation in one reply,
     * so the brain splits the two (see CloudBrain.respond).
     */
    val small: Boolean get() = false

    /** Gets the model loaded ahead of a request, where that's a thing (a local server that
     *  unloads idle models). Blocking; failures are ignored. */
    fun warmUp() {}
}

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/** An HTTP error reply, kept distinct so callers can react to specific status codes. */
class LlmHttpException(val code: Int, message: String) : Exception(message)

/** POSTs [body] (JSON text) and returns the parsed JSON reply, mapping every failure to [LlmException]. */
private fun postJson(url: String, headers: Map<String, String>, body: String): JSONObject {
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
    val request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    try {
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException(describeHttpError(resp.code, raw), LlmHttpException(resp.code, raw))
            return JSONObject(raw)
        }
    } catch (e: LlmException) {
        throw e
    } catch (e: java.net.UnknownHostException) {
        throw LlmException("Can't reach $host — are you online?", e)
    } catch (e: java.net.SocketTimeoutException) {
        throw LlmException("$host took too long to answer.", e)
    } catch (e: Exception) {
        throw LlmException("Couldn't talk to $host: ${e.message}", e)
    }
}

/** A readable reason for an HTTP error. All three APIs put it in {"error": {"message": …}}. */
internal fun describeHttpError(code: Int, raw: String): String {
    val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
        .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
    val suffix = detail?.let { ": $it" } ?: "."
    return when (code) {
        401, 403 -> "The API key was rejected$suffix"
        404 -> "Model or endpoint not found$suffix"
        429 -> "Rate limit or quota reached$suffix"
        else -> "The server answered HTTP $code$suffix"
    }
}

/**
 * Any OpenAI-style /chat/completions endpoint: OpenAI, Groq, OpenRouter, DeepSeek, Ollama,
 * LM Studio… For hosted models no temperature, max_tokens or response_format is sent — some
 * models reject them, and big models follow the prompt anyway.
 *
 * [selfHosted] is for small models on the user's own server (Ollama, LM Studio, llama.cpp):
 * they get the reply schema enforced by the server's constrained decoding — without it, a
 * 1–3B model rarely keeps to the format — and a temperature per job: steady for decisions,
 * lively for conversation. If the server rejects the schema, the request is retried without
 * it (and it isn't sent again).
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val selfHosted: Boolean = false,
) : LlmClient {

    @Volatile private var schemaSupported = true

    override val small: Boolean get() = selfHosted

    /** A one-token request makes a local server (Ollama unloads idle models) load the model now. */
    override fun warmUp() {
        if (!selfHosted) return
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        val body = JSONObject().put("model", model).put("max_tokens", 1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        runCatching { postJson(baseUrl.trimEnd('/') + "/chat/completions", headers, body.toString()) }
    }

    override fun complete(system: String, turns: List<Turn>, schema: String?, creative: Boolean): String {
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val useSchema = selfHosted && schemaSupported && schema != null
        val temperature = if (!selfHosted) null else if (creative) CHAT_TEMPERATURE else DECISION_TEMPERATURE
        val body = requestBody(model, system, turns, if (useSchema) schema else null, temperature)
        return try {
            parseResponse(postJson(url, headers, body))
        } catch (e: LlmException) {
            val status = (e.cause as? LlmHttpException)?.code
            if (!useSchema || (status != 400 && status != 422)) throw e
            schemaSupported = false
            parseResponse(postJson(url, headers, requestBody(model, system, turns, null, temperature)))
        }
    }

    companion object {
        // Small models pick actions reliably only when sampled cool, and sound flat unless warm.
        private const val DECISION_TEMPERATURE = 0.3
        private const val CHAT_TEMPERATURE = 0.7
        private const val SCHEMA_SLOT = "\u0000schema\u0000"

        /** The request as JSON text; [schema] (JSON text) is spliced in verbatim to keep its key order. */
        fun requestBody(
            model: String,
            system: String,
            turns: List<Turn>,
            schema: String? = null,
            temperature: Double? = null,
        ): String {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            turns.forEach {
                messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
            }
            val body = JSONObject().put("model", model).put("messages", messages)
            if (temperature != null) body.put("temperature", temperature)
            if (schema == null) return body.toString()
            body.put("response_format", JSONObject()
                .put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "reply").put("schema", SCHEMA_SLOT)))
            return body.toString().replace(JSONObject.quote(SCHEMA_SLOT), schema)
        }

        fun parseResponse(json: JSONObject): String =
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw LlmException("The model sent back an empty answer.")
    }
}

/** Anthropic's Messages API (Claude). */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
) : LlmClient {

    override fun complete(system: String, turns: List<Turn>, schema: String?, creative: Boolean): String =
        parseResponse(postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            requestBody(model, system, turns).toString()
        ))

    companion object {
        // Spoken replies are a few sentences; this only caps a runaway answer.
        private const val MAX_TOKENS = 1024

        fun requestBody(model: String, system: String, turns: List<Turn>): JSONObject {
            val messages = JSONArray()
            turns.forEach {
                messages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
            }
            return JSONObject()
                .put("model", model)
                .put("max_tokens", MAX_TOKENS)
                .put("system", system)
                .put("messages", messages)
        }

        fun parseResponse(json: JSONObject): String {
            val blocks = json.optJSONArray("content") ?: JSONArray()
            val text = (0 until blocks.length())
                .mapNotNull { blocks.optJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }
                .trim()
            return text.ifEmpty { throw LlmException("The model sent back an empty answer.") }
        }
    }
}

/** Google's native Gemini API (generateContent). */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
) : LlmClient {

    override fun complete(system: String, turns: List<Turn>, schema: String?, creative: Boolean): String =
        parseResponse(postJson(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey),
            requestBody(system, turns).toString()
        ))

    companion object {
        fun requestBody(system: String, turns: List<Turn>): JSONObject {
            val contents = JSONArray()
            turns.forEach {
                contents.put(JSONObject()
                    .put("role", if (it.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
            }
            return JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
        }

        fun parseResponse(json: JSONObject): String {
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = (0 until parts.length())
                .mapNotNull { parts.optJSONObject(it) }
                .joinToString("") { it.optString("text") }
                .trim()
            if (text.isNotEmpty()) return text
            val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw LlmException(if (blocked.isNotEmpty()) "Gemini blocked the request ($blocked)." else "The model sent back an empty answer.")
        }
    }
}
