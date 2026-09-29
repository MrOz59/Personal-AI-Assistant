package com.naomi.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire formats of the three protocols, checked without touching the network. */
class LlmClientTest {

    private val turns = listOf(
        Turn(fromUser = true, text = "hi"),
        Turn(fromUser = false, text = """{"say":"Hey!","action":null}"""),
        Turn(fromUser = true, text = "set a timer"),
    )

    @Test
    fun openAiRequestPutsSystemFirstThenAlternates() {
        val body = JSONObject(OpenAiCompatibleClient.requestBody("llama-3.3-70b-versatile", "SYS", turns))
        val messages = body.getJSONArray("messages")
        assertEquals("llama-3.3-70b-versatile", body.getString("model"))
        assertEquals(listOf("system", "user", "assistant", "user"),
            (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        // Hosted: no sampling knobs or schema — some OpenAI-compatible models reject them.
        assertFalse(body.has("temperature"))
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("response_format"))
    }

    @Test
    fun selfHostedRequestKeepsSchemaKeyOrder() {
        val raw = OpenAiCompatibleClient.requestBody("assistente-leve", "SYS", turns, CloudBrain.REPLY_SCHEMA, 0.4)
        val body = JSONObject(raw) // still valid JSON after the splice
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        // Constrained decoding writes keys in schema order: action before say, type before its fields.
        assertTrue(raw.contains(CloudBrain.REPLY_SCHEMA))
        assertTrue(raw.indexOf("\"action\":{\"anyOf\"") < raw.indexOf("\"say\":{\"type\":\"string\"}"))
        assertTrue(raw.contains("""{"type":{"const":"set_timer"},"hours":{"type":"integer"}"""))
    }

    @Test
    fun openAiResponse() {
        val json = JSONObject("""{"choices":[{"message":{"role":"assistant","content":"  ready "}}]}""")
        assertEquals("ready", OpenAiCompatibleClient.parseResponse(json))
    }

    @Test(expected = LlmException::class)
    fun openAiEmptyResponseThrows() {
        OpenAiCompatibleClient.parseResponse(JSONObject("""{"choices":[]}"""))
    }

    @Test
    fun anthropicRequestHasTopLevelSystemAndMaxTokens() {
        val body = AnthropicClient.requestBody("claude-haiku-4-5-20251001", "SYS", turns)
        assertEquals("SYS", body.getString("system"))
        assertTrue(body.getInt("max_tokens") > 0)
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "user"),
            (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
    }

    @Test
    fun anthropicResponseJoinsTextBlocksOnly() {
        val json = JSONObject("""{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"rea"},{"type":"text","text":"dy"}]}""")
        assertEquals("ready", AnthropicClient.parseResponse(json))
    }

    @Test
    fun geminiRequestUsesModelRoleAndSystemInstruction() {
        val body = GeminiClient.requestBody("SYS", turns)
        assertEquals("SYS", body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"),
            (0 until contents.length()).map { contents.getJSONObject(it).getString("role") })
    }

    @Test
    fun geminiResponseAndBlockReason() {
        val ok = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"ready"}]}}]}""")
        assertEquals("ready", GeminiClient.parseResponse(ok))
        val blocked = JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        val e = runCatching { GeminiClient.parseResponse(blocked) }.exceptionOrNull()
        assertTrue(e is LlmException && e.message!!.contains("SAFETY"))
    }

    @Test
    fun httpErrorsReadable() {
        assertEquals("The API key was rejected: Invalid API Key",
            describeHttpError(401, """{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}"""))
        assertEquals("Rate limit or quota reached.", describeHttpError(429, "not json"))
        assertTrue(describeHttpError(500, "{}").startsWith("The server answered HTTP 500"))
    }
}
