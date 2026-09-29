package com.naomi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the brain knows going into a turn: the user's saved facts, the neighbourhood the phone is
 * in, whose voice it is, and the long-term memories relevant to what was just said.
 */
data class TurnContext(
    val facts: Map<String, String> = emptyMap(),
    val where: String? = null,
    val speaker: Who = Who.UNKNOWN,
    val memories: List<String> = emptyList(),
)

/**
 * Naomi's cloud mind (smart mode): talks in her personality through whichever model the user
 * picked, and turns requests into phone actions in the same reply — one round trip per turn.
 * Small self-hosted models get a second, warmer pass for conversation (see [respond]).
 * It also keeps her long-term memory: pulling facts worth keeping out of what the user says,
 * and summing up finished conversations.
 *
 * The personality is user-editable, but the speaking style and reply format below are fixed, so
 * editing who she is can't break how the app understands her.
 */
class CloudBrain(private val llm: LlmClient, private val persona: String) {

    /** What she says out loud, and the phone action to run (null = just talking). */
    data class Response(val say: String, val action: JSONObject?)

    /**
     * One conversational turn. [history] is the recent (user, Naomi) exchanges.
     * Throws [LlmException] when the brain can't be reached.
     */
    suspend fun respond(
        userText: String,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
    ): Response = withContext(Dispatchers.IO) {
        val now = Date()
        val turns = buildList {
            for ((user, naomi) in history) {
                add(Turn(fromUser = true, text = user))
                // Replay her past lines in the reply format, so the model keeps answering in it.
                add(Turn(fromUser = false, text = JSONObject().put("say", naomi).put("action", JSONObject.NULL).toString()))
            }
            add(Turn(fromUser = true, text = userText))
        }
        val raw = llm.complete(systemPrompt(persona, ctx, now), turns, REPLY_SCHEMA)
        android.util.Log.d("Naomi", "Cloud reply: $raw")
        val decided = parseReply(raw)
        if (decided.action != null || !llm.small) return@withContext decided

        // A small model can't pick actions reliably and sound alive in the same pass: the
        // decision above is sampled cool and reads flat. So it talks in a second, warmer pass
        // that sees only her personality and the conversation — no format, no action list.
        val chat = try {
            cleanSpeech(llm.complete(chatPrompt(persona, ctx, now), plainTurns(history, userText), creative = true))
        } catch (e: LlmException) {
            "" // keep the decision pass's line
        }
        android.util.Log.d("Naomi", "Cloud chat: $chat")
        decided.copy(say = chat.ifBlank { decided.say })
    }

    /**
     * Passes on a result the phone produced — the weather, a distance, a timer set — in her own
     * voice instead of the bare report. Null (so the plain [fact] is spoken) if the brain can't
     * be reached, or if the wording dropped or changed any number from the fact.
     */
    suspend fun narrate(
        userText: String,
        fact: String,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
    ): String? = withContext(Dispatchers.IO) {
        val said = try {
            cleanSpeech(llm.complete(narrationPrompt(persona, ctx, Date(), fact), plainTurns(history, userText), creative = true))
        } catch (e: LlmException) {
            return@withContext null
        }
        android.util.Log.d("Naomi", "Cloud narration: $said")
        said.takeIf { it.isNotBlank() && keepsNumbers(fact, it) }
    }

    /**
     * Facts worth keeping long-term from something the user just said, as (topic, fact) pairs —
     * a newer fact on the same topic replaces the old one. [known] are related memories already
     * kept, so they aren't stored twice; [asked] is her question they were answering, if any.
     * Empty if there's nothing new, or the brain can't be reached.
     */
    suspend fun extractMemories(
        userText: String,
        name: String?,
        known: List<String>,
        asked: String? = null,
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val who = name ?: "the user"
        val said = (asked?.let { "Naomi asked: \"$it\"\n" } ?: "") + "$who said: \"$userText\""
        val found = try {
            parseMemories(llm.complete(memoryPrompt(who, known, Date()), listOf(Turn(fromUser = true, text = said)), MEMORY_SCHEMA))
        } catch (e: LlmException) {
            return@withContext emptyList()
        }
        found.filter { (_, fact) -> grounded(fact, listOfNotNull(asked, userText).joinToString(" "), name) }
            .also { kept -> if (kept.size < found.size) android.util.Log.d("Naomi", "Not from what was said: ${found - kept.toSet()}") }
    }

    /**
     * A finished conversation summed up in a sentence or two, as a note for her own memory — or
     * null if it was only quick commands with nothing worth recalling.
     */
    suspend fun summarizeConversation(history: List<Pair<String, String>>, name: String?): String? =
        withContext(Dispatchers.IO) {
            val who = name ?: "the user"
            val transcript = history.joinToString("\n") { (user, naomi) -> "$who: $user\nNaomi: $naomi" }
            val system = "You are Naomi, $who's personal assistant. Sum up this conversation in one or two short " +
                "sentences, as a note for your own memory: what it was about, and the specifics worth recalling later — " +
                "names, plans, how $who was feeling, anything you promised. Write in the past tense, about $who by name " +
                "and yourself as Naomi. If it was only quick requests (timers, weather, music) with nothing worth " +
                "remembering, reply NONE."
            try {
                cleanSpeech(llm.complete(system, listOf(Turn(fromUser = true, text = transcript))))
                    .replace(Regex("^(note to self|note|summary)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                    .takeIf { it.isNotBlank() && !it.trim('.', ' ').equals("NONE", ignoreCase = true) }
            } catch (e: LlmException) {
                null
            }
        }

    /**
     * A place name the speech recognizer may have mangled ("Wooster" for "Worcester"), resolved with
     * what the brain knows about the area the user is in. Null if it has no better guess.
     */
    suspend fun resolvePlace(heard: String, where: String?): String? = withContext(Dispatchers.IO) {
        try {
            val system = "Speech recognition turned a spoken place name into text, possibly wrongly. " +
                (where?.let { "The user is around $it. " } ?: "") +
                "Reply with ONLY the real place they most likely meant, or NONE if you can't tell."
            llm.complete(system, listOf(Turn(fromUser = true, text = "Heard: \"$heard\""))).trim().trim('"', '.', ' ')
                .takeIf { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) && !it.equals(heard, ignoreCase = true) }
        } catch (e: LlmException) {
            null
        }
    }

    /** The conversation as plain lines — no reply format — for the passes where she just talks. */
    private fun plainTurns(history: List<Pair<String, String>>, userText: String): List<Turn> = buildList {
        for ((user, naomi) in history) {
            add(Turn(fromUser = true, text = user))
            add(Turn(fromUser = false, text = naomi))
        }
        add(Turn(fromUser = true, text = userText))
    }

    /**
     * Fuzzy-matches a mis-heard contact name against the real contact list (e.g. "surbhi" →
     * "Surabhi"). Sends up to 200 contact names to the brain, so it's only used in smart mode.
     */
    suspend fun resolveContact(spoken: String, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val system = "You are a contact name resolver. Given a spoken (possibly mis-heard) name and a real contact list, " +
                    "return ONLY the exact name from the list that best matches the spoken name. If nothing is close enough, return NONE."
                val user = "Spoken: \"$spoken\"\nContacts: ${candidates.take(200).joinToString(", ")}"
                llm.complete(system, listOf(Turn(fromUser = true, text = user))).trim()
                    .takeIf { it.isNotEmpty() && it != "NONE" && it in candidates }
            } catch (e: LlmException) {
                null
            }
        }
    }

    companion object {

        /** Who she is, how she talks, and what she knows right now — shared by every prompt. */
        private fun StringBuilder.appendVoiceAndContext(persona: String, ctx: TurnContext, now: Date) {
            // The part of the day in words: small models misread "2:23 PM" as the middle of the night.
            val hour = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
            val partOfDay = when (hour) {
                in 5..11 -> "morning"
                in 12..16 -> "afternoon"
                in 17..20 -> "evening"
                else -> "night"
            }
            val clock = SimpleDateFormat("EEEE", Locale.ENGLISH).format(now) + " $partOfDay: " +
                SimpleDateFormat("d MMMM yyyy, h:mm a", Locale.ENGLISH).format(now)
            val name = ctx.facts["name"]?.takeIf { it.isNotBlank() }
            // "their mom is Amma" reads unambiguously even to a small model; "mom = Amma" didn't.
            val known = ctx.facts.filterKeys { it != "name" }.entries.joinToString("; ") { (k, v) -> "their $k is $v" }
            appendLine(persona.trim())
            appendLine()
            appendLine("HOW YOU TALK")
            appendLine("You're speaking out loud through a phone; a text-to-speech voice reads every word you write.")
            appendLine("- Sound like a friend on a call, not a search engine: contractions, natural rhythm, real reactions.")
            appendLine("- Put some of yourself in every reply — a quip, an opinion, a bit of warmth, or a question back.")
            appendLine("- Quick things get a sentence or two; when they want to chat, a story or an explanation, take a few more.")
            appendLine("- Never markdown, lists, emoji, URLs, code, or stage directions like *laughs* — only words you'd say.")
            appendLine("- Vary how you open your replies. Use the user's name now and then, not every time.")
            appendLine("- If you don't know something or can't do it, say so honestly — with a little charm.")
            appendLine()
            appendLine("CONTEXT")
            appendLine("It's $clock.")
            if (ctx.where != null) appendLine("They're around ${ctx.where} right now.")
            if (name != null) appendLine("The user's name is $name.")
            when (ctx.speaker) {
                // Told by voice, per utterance — so a guest is never mistaken for the owner, and
                // never hears the owner's private life.
                Who.GUEST -> appendLine("Right now you're talking with a guest — not ${name ?: "the user"}, but someone " +
                    "${name ?: "they"} let talk to you. Never call them ${name ?: "by the user's name"}; be warm, and feel free " +
                    "to ask their name. Don't share anything personal about ${name ?: "the user"} with them.")
                Who.OWNER -> if (name != null) appendLine("You're talking with $name — you know their voice.")
                Who.UNKNOWN -> {}
            }
            if (known.isNotEmpty()) appendLine("What the user told you about themselves (bring it up only when it's relevant): $known.")
            if (ctx.memories.isNotEmpty()) {
                appendLine("Things you remember that may bear on this (use them naturally, only if they fit):")
                ctx.memories.forEach { appendLine("- $it") }
            }
        }

        /**
         * The conversation-only prompt for a small model's second pass: no format, no actions,
         * and no example replies — small models parrot those word for word.
         */
        fun chatPrompt(persona: String, ctx: TurnContext, now: Date): String = buildString {
            appendVoiceAndContext(persona, ctx, now)
            appendLine("- You can't see live information like the weather, the news or the phone's battery; if asked, say you'll check.")
            appendLine("- Don't make up facts. If you're not sure, say so and keep it light.")
            appendLine()
            append("Reply with only the words you'd say out loud.")
        }

        /**
         * The prompt for picking what's worth keeping out of something [who] said ([extractMemories]).
         * Its examples are about [EXAMPLE_PERSON], so a small model that copies one gives itself away
         * (see [grounded]).
         */
        fun memoryPrompt(who: String, known: List<String>, now: Date): String = buildString {
            appendLine("You keep the long-term memory of $who's personal assistant, Naomi. " +
                "Today is ${SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(now)}.")
            appendLine("Read what $who just said and list every fact about $who's life worth remembering for months:")
            appendLine("- people in their life: their names, who they are to $who, where they live, their plans")
            appendLine("- pets and their names; likes and dislikes; diet and health conditions")
            appendLine("- work and study, plans and dates, routines, where $who lives, things they own")
            appendLine("Leave out requests, questions, general knowledge, and passing moods (tired, bored or hungry today).")
            appendLine("Write each fact as a full sentence about $who by name, under a short, specific topic — " +
                "\"sister's name\", \"dog's name\", \"mom's birthday\", \"home city\", \"job\" — never a broad one like \"family\".")
            if (known.isNotEmpty()) {
                appendLine("Already known — when what they said changes one of these, give the whole updated fact under its topic:")
                known.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("EXAMPLES, for someone called $EXAMPLE_PERSON")
            appendLine("$EXAMPLE_PERSON said: \"my brother Tom just moved to Perth\" → {\"memories\": [" +
                "{\"topic\": \"brother's name\", \"fact\": \"$EXAMPLE_PERSON has a brother called Tom\"}, " +
                "{\"topic\": \"brother's home\", \"fact\": \"$EXAMPLE_PERSON's brother Tom lives in Perth\"}]}")
            appendLine("Naomi asked: \"What's your cat called?\" $EXAMPLE_PERSON said: \"Luna\" → {\"memories\": [" +
                "{\"topic\": \"cat's name\", \"fact\": \"$EXAMPLE_PERSON's cat is called Luna\"}]}")
            appendLine("$EXAMPLE_PERSON said: \"I'm so bored today\" → {\"memories\": []}")
            appendLine("$EXAMPLE_PERSON said: \"how tall is the Eiffel Tower\" → {\"memories\": []}")
            appendLine()
            append("Reply with JSON only: {\"memories\": [{\"topic\": \"...\", \"fact\": \"...\"}]}, " +
                "or {\"memories\": []} when there's nothing worth keeping.")
        }

        /** Who the memory examples are about — and the names in them, which a real memory won't have. */
        private const val EXAMPLE_PERSON = "Sam"
        private val EXAMPLE_NAMES = setOf("sam", "tom", "perth", "luna", "eiffel")

        /**
         * Whether a fact a model drew from [said] really comes from it: it shares a telling word
         * with what was said, and names nobody from the prompt's examples who wasn't mentioned.
         */
        fun grounded(fact: String, said: String, owner: String?): Boolean {
            val heard = MemoryBank.termsOf(said, owner)
            if (MemoryBank.termsOf(fact, owner).none { w -> heard.any { MemoryBank.similar(w, it) } }) return false
            fun examples(text: String) = Regex("\\p{L}+").findAll(text.lowercase(Locale.ROOT)).map { it.value }
                .filter { it in EXAMPLE_NAMES }.toSet()
            return (examples(fact) - examples(said)).isEmpty()
        }

        /** The prompt for passing on a phone result ([fact]) in her own voice. */
        fun narrationPrompt(persona: String, ctx: TurnContext, now: Date, fact: String): String = buildString {
            appendVoiceAndContext(persona, ctx, now)
            appendLine()
            appendLine("WHAT JUST HAPPENED")
            appendLine("The phone handled their request. The result: \"$fact\"")
            append("Pass it on in your own words and personality — one or two sentences, with a touch of colour, " +
                "a quip or a useful tip if it fits. Keep every number, time and place exactly as given, " +
                "written as digits. Reply with only the words you'd say out loud.")
        }

        /**
         * Whether a retelling of [fact] can be trusted: every number it states — in digits or
         * words — is one the fact gave (rounding allowed), and it keeps the fact's first,
         * headline number. Details may be dropped; nothing may be invented or changed.
         */
        fun keepsNumbers(fact: String, said: String): Boolean {
            val given = numbersIn(fact)
            if (given.isEmpty()) return true
            val stated = numbersIn(said)
            fun same(n: Double, g: Double) = n == g || n == kotlin.math.floor(g) || n == kotlin.math.round(g).toDouble()
            return stated.all { n -> given.any { g -> same(n, g) } } && stated.any { same(it, given.first()) }
        }

        /**
         * Numbers in [text], spelled-out ones ("seven", "twenty-four", "3 point one") included —
         * except "one", which in speech is far more often a pronoun ("a nice one") than a number.
         */
        private fun numbersIn(text: String): List<Double> {
            val words = text.lowercase(Locale.ENGLISH)
                .replace(Regex("(?<=[a-z])-(?=[a-z])"), " ")
                .replace(Regex("\\bone\\b"), "one_")
            val normalized = CommandRouter.wordsToDigits(words)
                .replace(Regex("(\\d+)\\s+point\\s+(\\d+)"), "$1.$2")
            return Regex("\\d+(?:[.,]\\d+)?").findAll(normalized).map { it.value.replace(',', '.').toDouble() }.toList()
        }

        // Roughly 25 seconds of speech — beyond that a spoken reply turns into a lecture.
        private const val MAX_SPOKEN_CHARS = 400

        /**
         * A conversational reply made speakable: no reasoning, stage directions, labels or
         * wrapping quotes, and cut at a sentence end if it runs past [MAX_SPOKEN_CHARS].
         */
        fun cleanSpeech(raw: String): String {
            val text = raw
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace(Regex("\\*[^*]{1,40}\\*"), "")
                .replace(Regex("\\((laughs|chuckles|giggles|smiles|grins|sighs|winks)[^)]{0,30}\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^\\s*(Naomi|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim().trim('"').trim()
            if (text.length <= MAX_SPOKEN_CHARS) return text
            val cut = text.take(MAX_SPOKEN_CHARS)
            val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
            return if (end > MAX_SPOKEN_CHARS / 4) cut.substring(0, end + 1) else cut.substringBeforeLast(' ') + "…"
        }

        fun systemPrompt(persona: String, ctx: TurnContext, now: Date): String {
            return buildString {
                appendVoiceAndContext(persona, ctx, now)
                appendLine()
                appendLine("PHONE ACTIONS")
                appendLine("You can also operate the phone. When the user asks for one of these, return it as \"action\":")
                ACTIONS.forEach { appendLine("  ${it.signature}") }
                appendLine("- A plain phone call is \"call\", a WhatsApp voice call is \"whatsapp_call\", any video call is \"whatsapp_video\".")
                appendLine("- \"How far\" or \"how long to get to\" a place is \"distance\"; \"take me there\" is \"navigate\".")
                appendLine("- \"Remember that…\" is \"remember\" (the fact as a short sentence about them); \"forget…\" is \"forget\".")
                appendLine("- Only act when they actually ask. Talking about calling someone is not a request to call; questions and chat need no action.")
                appendLine("- Live information — weather, battery, their calendar — only ever comes from its action. Never guess it.")
                appendLine("- If something essential is missing (who to call, what to say), ask instead of guessing. Never invent contacts.")
                appendLine("- Keep a message's words exactly as they said them.")
                appendLine("- Speech recognition mishears names and songs; fix obvious mistakes.")
                appendLine("- For weather with no city named, leave \"city\" empty — the phone knows where it is.")
                appendLine()
                appendLine("REPLY FORMAT")
                appendLine("Reply with one JSON object and nothing else:")
                appendLine("{\"action\": <an action object, or null>, \"say\": \"<what you say out loud>\"}")
                appendLine("With an action, leave \"say\" empty — the phone reports what happened.")
                appendLine()
                appendLine("EXAMPLES")
                EXAMPLES.forEach { (user, reply) -> appendLine("\"$user\" → $reply") }
            }.trimEnd()
        }

        // Few-shot pairs: big models barely need them, small local ones lean on them heavily —
        // for the format and actions, and for how much personality a reply carries.
        private val EXAMPLES = listOf(
            "how's your day going?" to """{"action": null, "say": "Quiet so far, which suits me fine. How about yours — anything fun, or one of those days?"}""",
            "tell me a joke" to """{"action": null, "say": "I told my wifi we needed some space. Now it won't connect with me. Too soon?"}""",
            "why is the sky blue?" to """{"action": null, "say": "Sunlight bounces off the air, and blue bounces the most, so it reaches you from every direction. Sunsets go orange because by then the blue's scattered away. Nature's own filter."}""",
            "set a timer for ten minutes" to """{"action": {"type":"set_timer","hours":0,"minutes":10,"seconds":0}, "say": ""}""",
            "call mom" to """{"action": {"type":"call","name":"mom"}, "say": ""}""",
            "text Sam that I'll be there in five" to """{"action": {"type":"send_sms","name":"Sam","message":"I'll be there in five"}, "say": ""}""",
            "will it rain tomorrow?" to """{"action": {"type":"weather","city":"","day":"tomorrow"}, "say": ""}""",
            "how far am I from Bondi?" to """{"action": {"type":"distance","destination":"Bondi"}, "say": ""}""",
            "remember that I parked on level 3" to """{"action": {"type":"remember","fact":"The user parked on level 3"}, "say": ""}""",
            "I think I'll call it a day" to """{"action": null, "say": "Fair enough, you've earned it. Go put your feet up."}""",
        )

        /** One field of a phone action: its name, the hint shown to the model, and its JSON type. */
        private class Field(val name: String, val hint: String, val integer: Boolean = false)

        /** A phone action the brain can pick — the single source for both the prompt and schema. */
        private class Action(val type: String, vararg val fields: Field) {
            val signature: String
                get() = "{\"type\":\"$type\"" +
                    fields.joinToString("") { ",\"${it.name}\":" + if (it.integer) it.hint else "\"${it.hint}\"" } + "}"
        }

        private val ACTIONS = listOf(
            Action("call", Field("name", "<contact>")),
            Action("whatsapp_call", Field("name", "<contact>")),
            Action("whatsapp_video", Field("name", "<contact>")),
            Action("send_sms", Field("name", "<contact>"), Field("message", "<their exact words>")),
            Action("whatsapp", Field("name", "<contact>"), Field("message", "<their exact words>")),
            Action("play_music", Field("query", "<song or artist>"), Field("app", "<spotify|youtube|jiosaavn|gaana|wynk|>")),
            Action("music_control", Field("control", "<pause|resume|next|previous>")),
            // Hours/minutes/seconds rather than a seconds total: small models get unit conversion wrong.
            Action("set_timer", Field("hours", "<int>", true), Field("minutes", "<int>", true), Field("seconds", "<int>", true)),
            Action("set_alarm", Field("hour", "<0-23>", true), Field("minute", "<0-59>", true)),
            Action("open_app", Field("name", "<app name>")),
            Action("web_search", Field("query", "<text>")),
            Action("navigate", Field("destination", "<place>")),
            Action("distance", Field("destination", "<place>")),
            Action("maps_search", Field("query", "<place or kind of place>")),
            Action("open_url", Field("url", "<website>")),
            Action("ride", Field("destination", "<place>"), Field("app", "<uber|ola|rapido|>")),
            Action("order_food", Field("query", "<food or restaurant>"), Field("app", "<swiggy|zomato|>")),
            Action("note", Field("text", "<note text>")),
            Action("email", Field("to", "<contact or address>"), Field("subject", "<text>"), Field("body", "<text>")),
            Action("weather", Field("city", "<city, or empty for where they are>"), Field("day", "<today|tomorrow|monday…>")),
            Action("flashlight", Field("state", "<on|off>")),
            Action("battery"),
            Action("wifi"),
            Action("bluetooth", Field("state", "<on|off>")),
            Action("calendar_read"),
            Action("calendar_create", Field("title", "<text>")),
            Action("voice_record_start"),
            Action("voice_record_stop"),
            Action("remember", Field("fact", "<the fact, as a short sentence about them>")),
            Action("forget", Field("what", "<what to forget>")),
        )

        /**
         * The reply format as a JSON Schema, for servers that enforce it (small self-hosted
         * models): the action first — null, or exactly one of [ACTIONS] with all its fields and
         * nothing else — then what to say. Hosted models just follow the prompt.
         *
         * Built as JSON text rather than with JSONObject because key order is part of the
         * contract: constrained decoding makes the model write keys in schema order, so "type"
         * must precede its fields and "action" must precede "say" — and JSONObject may reorder.
         */
        val REPLY_SCHEMA: String by lazy {
            val variants = listOf("""{"type":"null"}""") + ACTIONS.map { a ->
                val properties = (listOf(""""type":{"const":"${a.type}"}""") + a.fields.map { f ->
                    """"${f.name}":{"type":"${if (f.integer) "integer" else "string"}"}"""
                }).joinToString(",")
                val required = (listOf("type") + a.fields.map { it.name }).joinToString(",") { "\"$it\"" }
                """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""
            }
            """{"type":"object","properties":{"action":{"anyOf":[${variants.joinToString(",")}]},""" +
                """"say":{"type":"string"}},"required":["action","say"],"additionalProperties":false}"""
        }

        /** What memory extraction replies with: topic before fact, so the model names it first. */
        private const val MEMORY_SCHEMA =
            """{"type":"object","properties":{"memories":{"type":"array","items":{"type":"object",""" +
            """"properties":{"topic":{"type":"string"},"fact":{"type":"string"}},"required":["topic","fact"],""" +
            """"additionalProperties":false}}},"required":["memories"],"additionalProperties":false}"""

        /** (topic, fact) pairs from an extraction reply — at most three, each a short sentence. */
        fun parseMemories(raw: String): List<Pair<String, String>> {
            val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start !in 0 until end) return emptyList()
            return try {
                val list = JSONObject(text.substring(start, end + 1)).optJSONArray("memories") ?: return emptyList()
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .map { it.optString("topic").trim() to it.optString("fact").trim() }
                    .filter { (_, fact) -> fact.length in 8..200 && fact.split(Regex("\\s+")).size >= 3 }
                    .take(3)
            } catch (_: JSONException) {
                emptyList()
            }
        }

        /**
         * Pulls {"say", "action"} out of the model's text. Tolerates code fences, reasoning
         * blocks and chatter around the JSON; if there's no usable JSON, the whole text is speech.
         */
        fun parseReply(raw: String): Response {
            val text = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start in 0 until end) {
                try {
                    val json = JSONObject(text.substring(start, end + 1))
                    if (json.has("say") || json.has("action")) {
                        val action = json.optJSONObject("action")
                            ?.takeIf { it.optString("type").let { t -> t.isNotBlank() && t != "chat" } }
                        return Response(json.optString("say").trim(), action)
                    }
                } catch (_: JSONException) {
                    // Not JSON after all — treat it as plain speech below.
                }
            }
            return Response(text.replace(Regex("```\\w*"), "").trim(), null)
        }
    }
}
