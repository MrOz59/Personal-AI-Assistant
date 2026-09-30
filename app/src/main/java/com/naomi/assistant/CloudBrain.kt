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
    /** The speech recognizer's other guesses at what was just said, when it wasn't sure. */
    val heardAs: List<String> = emptyList(),
    /** The language she hears and speaks (see [Language.current]). */
    val language: Language = Language.ENGLISH,
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

    /** Her answer from web search results, and whether the results held one. */
    data class Answer(val say: String, val found: Boolean)

    /** How one try at answering from search results went (see [tryAnswer]). */
    enum class Verdict {
        /** Answered from the results. */
        ANSWERED,
        /** The results don't say: her line saying so is the answer. */
        NOT_THERE,
        /** No answer that could be used: unreadable, or stating numbers the results don't have. */
        UNSURE,
        /** The brain couldn't be reached. */
        UNREACHABLE,
    }

    /** One try at answering from search results: what she'd say (null unless [verdict] is ANSWERED or NOT_THERE), and how it went. */
    data class Attempt(val answer: Answer?, val verdict: Verdict)

    /**
     * One conversational turn. [history] is the recent (user, Naomi) exchanges.
     * Throws [LlmException] when the brain can't be reached.
     */
    suspend fun respond(
        userText: String,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
        // False when the turn is going to a web search anyway: its answer is what she'll say.
        chat: Boolean = true,
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
        if (decided.action != null || !llm.small || !chat) return@withContext decided

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
     * be reached, or if the wording dropped or changed any number from the fact or said the
     * opposite about rain, twice.
     */
    suspend fun narrate(
        userText: String,
        fact: String,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
    ): String? = withContext(Dispatchers.IO) {
        // Lively first; if that drops or changes a number, once more at a steadier temperature
        // before the plain report — which, for some results, is only in English.
        for (creative in listOf(true, false)) {
            val said = try {
                cleanSpeech(llm.complete(narrationPrompt(persona, ctx, Date(), fact), plainTurns(history, userText), creative = creative))
            } catch (e: LlmException) {
                return@withContext null
            }
            android.util.Log.d("Naomi", "Cloud narration: $said")
            if (said.isNotBlank() && keepsNumbers(fact, said) && agreesOnRain(fact, said)) return@withContext said
        }
        null
    }

    /**
     * A line to say before, and one after, a result the phone reads out word for word — bus
     * directions, where a retelling can swap a departure time for an arrival and cost a bus. Her
     * reaction, a quip or a tip, with no numbers or directions of her own. Either line is null if
     * it was left out or tried to add facts; both, if the brain can't be reached.
     */
    suspend fun frame(
        userText: String,
        fact: String,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val raw = try {
            llm.complete(framePrompt(persona, ctx, Date(), fact), plainTurns(history, userText), FRAME_SCHEMA, creative = true)
        } catch (e: LlmException) {
            return@withContext null to null
        }
        android.util.Log.d("Naomi", "Cloud frame: $raw")
        parseFrame(raw)
    }

    /**
     * What they asked ([userText]) answered from web search [results] for [query], in her voice.
     * Null if the brain can't be reached, or if the answer states a number the results don't
     * have — a small model fills in a score or a price it half remembers. Lively first; if that
     * strays from the results, once more at a steadier temperature.
     */
    suspend fun answerFrom(
        userText: String,
        query: String,
        results: List<SearchClient.Result>,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
        pages: List<PageReader.Excerpt> = emptyList(),
    ): Answer? {
        for (creative in listOf(true, false)) {
            val attempt = tryAnswer(userText, query, results, history, ctx, pages, creative)
            if (attempt.verdict == Verdict.UNREACHABLE) return null
            attempt.answer?.let { return it }
        }
        return null
    }

    /**
     * One try at answering [userText] from web search [results] for [query] — and from [pages],
     * passages read off the top results, when there are any. An answer stating a number neither
     * has is [Verdict.UNSURE], not an answer.
     */
    suspend fun tryAnswer(
        userText: String,
        query: String,
        results: List<SearchClient.Result>,
        history: List<Pair<String, String>>,
        ctx: TurnContext,
        pages: List<PageReader.Excerpt> = emptyList(),
        creative: Boolean = true,
    ): Attempt = withContext(Dispatchers.IO) {
        val now = Date()
        // The results come with the question: that's where a small model looks for what to answer from.
        val turns = plainTurns(history.takeLast(ANSWER_HISTORY), searchTurn(userText, query, results, now, pages))
        val source = (results.flatMap { listOfNotNull(it.title, it.snippet, it.published) } +
            pages.flatMap { listOfNotNull(it.title, it.text, it.published) } +
            listOf(userText, query, searchTurn("", "", emptyList(), now))).joinToString(" ")
        val raw = try {
            llm.complete(answerPrompt(persona, ctx, now), turns, ANSWER_SCHEMA, creative = creative)
        } catch (e: LlmException) {
            return@withContext Attempt(null, Verdict.UNREACHABLE)
        }
        android.util.Log.d("Naomi", "Cloud answer: $raw")
        val answer = parseAnswer(raw, known = listOfNotNull(userText, ctx.facts["name"]).joinToString(" "))
        when {
            answer == null -> Attempt(null, Verdict.UNSURE)
            !statesOnly(source, answer.say) -> {
                android.util.Log.d("Naomi", "That answer isn't all from the results")
                Attempt(null, Verdict.UNSURE)
            }
            else -> Attempt(answer, if (answer.found) Verdict.ANSWERED else Verdict.NOT_THERE)
        }
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
                    .replace(Regex("^(note( to self| for myself| to myself)?|summary)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
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
            // "their mom is Maria" reads unambiguously even to a small model; "mom = Maria" didn't.
            val known = ctx.facts.filterKeys { it != "name" }.entries.joinToString("; ") { (k, v) -> "their $k is $v" }
            appendLine(persona.trim())
            appendLine()
            appendLine("HOW YOU TALK")
            appendLine("You're speaking out loud through a phone; a text-to-speech voice reads every word you write.")
            ctx.language.replyIn?.let {
                appendLine("- Always reply in $it: the user talks to you in it, and a $it voice reads your words.")
            }
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
            if (ctx.heardAs.isNotEmpty()) {
                // English isn't their first language, and the recognizer trips on it: its runners-up
                // often hold the word it got wrong ("closest work" / "closest Woolworths").
                appendLine("Speech recognition wasn't sure of their last words. Its other guesses: " +
                    ctx.heardAs.joinToString("; ") { "\"$it\"" } + ". Take whichever makes most sense in the conversation.")
            }
        }

        /**
         * The conversation-only prompt for a small model's second pass: no format, no actions,
         * and no example replies — small models parrot those word for word.
         */
        fun chatPrompt(persona: String, ctx: TurnContext, now: Date): String = buildString {
            appendVoiceAndContext(persona, ctx, now)
            appendLine("- You can't see live information like the weather, the news or the phone's battery; if asked, offer to look it up.")
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

        /** The prompt for the lines around directions the phone reads out as they are ([frame]). */
        fun framePrompt(persona: String, ctx: TurnContext, now: Date, fact: String): String = buildString {
            appendVoiceAndContext(persona, ctx, now)
            appendLine()
            appendLine("WHAT JUST HAPPENED")
            appendLine("The phone worked out the way there, and reads it out word for word: \"$fact\"")
            append("Add your touch around it, speaking to them: one short line just before it — your reaction to " +
                "what they asked — and one just after it, a send-off: a wish, a joke or a practical reminder, never " +
                "the route again. No directions, stops, times or numbers of your own, and no question after it: the " +
                "phone offers the map next. Reply with JSON only: {\"before\": \"...\", \"after\": \"...\"}")
        }

        // A line describing the request instead of answering it: "Ozzy wants to know how to…".
        private val NARRATING = Regex("\\b(wants to know|is asking|asked (me )?(how|where|about)|the user|they want to)\\b",
            RegexOption.IGNORE_CASE)

        private const val FRAME_SCHEMA = """{"type":"object","properties":{"before":{"type":"string"},""" +
            """"after":{"type":"string"}},"required":["before","after"],"additionalProperties":false}"""

        /**
         * The lines of a [frame] reply: each kept only if it's short, states no number, and — the
         * one after — asks nothing (the map offer comes next).
         */
        fun parseFrame(raw: String): Pair<String?, String?> {
            val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start !in 0 until end) return null to null
            val json = try {
                JSONObject(text.substring(start, end + 1))
            } catch (_: JSONException) {
                return null to null
            }
            fun line(key: String) = cleanSpeech(json.optString(key))
                .takeIf { it.isNotBlank() && it.length <= 160 && numbersIn(it).isEmpty() && !NARRATING.containsMatchIn(it) }
                ?.let { if (it.last() in ".!?…") it else "$it." }
            return line("before") to line("after")?.takeUnless { it.endsWith("?") }
        }

        /** The prompt for answering from web search results ([answerFrom]); the results come in [searchTurn]. */
        fun answerPrompt(persona: String, ctx: TurnContext, now: Date): String = buildString {
            appendVoiceAndContext(persona, ctx, now)
            appendLine()
            appendLine("LOOKING THINGS UP")
            appendLine("You searched the web for what they asked. Their message holds the results — and passages from the pages " +
                "themselves, when you opened them — then their question.")
            appendLine("- \"answer\": what the results say, in a sentence or two, the way you'd tell them. Names, numbers and " +
                "dates exactly as the results give them, and nothing the results don't say, not even what you think you know.")
            appendLine("- Mind today's date: anything dated before today has already happened, and \"next\" means after today.")
            appendLine("- For something that changes, like a price or a score, say when it's from.")
            appendLine("- \"comment\": then one short line of your own — a reaction, a quip or a thought — with no facts or numbers.")
            appendLine("- If the results don't answer it, \"found\" is false and \"answer\" says so in a line.")
            appendLine("- Never read out websites or links.")
            append("Reply with JSON only: {\"found\": true, \"answer\": \"...\", \"comment\": \"...\"}")
        }

        /**
         * Their question with what the search found, as the turn she answers — today's date up
         * front, so "next" and "last year" are read against it — and what she read on the pages
         * themselves ([pages]), if she opened any.
         */
        fun searchTurn(
            userText: String,
            query: String,
            results: List<SearchClient.Result>,
            now: Date,
            pages: List<PageReader.Excerpt> = emptyList(),
        ): String = buildString {
            // The year spelled out too: a small model given 2026 took "last year" for 2024.
            val year = SimpleDateFormat("yyyy", Locale.ENGLISH).format(now).toInt()
            appendLine("Web results for \"$query\" (today is ${SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(now)}; " +
                "last year was ${year - 1}):")
            results.forEachIndexed { i, r ->
                val from = listOfNotNull(r.site.ifBlank { null }, r.published).joinToString(", ")
                appendLine("${i + 1}. ${r.title}${if (from.isEmpty()) "" else " ($from)"}: ${r.snippet}")
            }
            if (pages.isNotEmpty()) {
                appendLine()
                appendLine("From the pages:")
                for (page in pages) {
                    val from = listOfNotNull(page.site.ifBlank { null }, page.published).joinToString(", ")
                    appendLine("${page.title}${if (from.isEmpty()) "" else " ($from)"}:")
                    appendLine(page.text)
                }
            }
            appendLine()
            append("My question: $userText")
        }

        private val PLACEHOLDER = Regex("\\[[^\\]]*\\]")

        /** Capitalised words past the start of a sentence — names, as a model uses them — lowercased. */
        private fun namesIn(text: String): List<String> {
            val tokens = text.split(Regex("\\s+"))
            return tokens.indices.filter { i ->
                i > 0 && !tokens[i - 1].endsWith(".") && !tokens[i - 1].endsWith("!") && !tokens[i - 1].endsWith("?") &&
                    tokens[i].firstOrNull()?.isUpperCase() == true && !tokens[i].startsWith("I'") && tokens[i] != "I"
            }.map { word(tokens[it]) }
        }

        private fun wordsOf(text: String): Set<String> = text.split(Regex("\\s+")).map(::word).toSet()

        private fun word(token: String) = token.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() }.removeSuffix("'s").removeSuffix("’s")

        // The recent exchanges an answer from search results sees: enough for a follow-up, and
        // short enough that a small model's context still has plenty of room for the results.
        private const val ANSWER_HISTORY = 6

        // Whether it answered decides whether she offers to open the search, so it comes first; her
        // own comment is kept apart from the facts, where it can be checked for made-up ones.
        private const val ANSWER_SCHEMA = """{"type":"object","properties":{"found":{"type":"boolean"},""" +
            """"answer":{"type":"string"},"comment":{"type":"string"}},"required":["found","answer","comment"],""" +
            """"additionalProperties":false}"""

        /**
         * The answer in an [answerFrom] reply, with her comment after it when it states no facts of
         * its own — no numbers, no names that neither the answer nor [known] (their question, their
         * name) has — and there was an answer to comment on. Null if there's none to be had.
         */
        fun parseAnswer(raw: String, known: String = ""): Answer? {
            val text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start !in 0 until end) return cleanSpeech(text).takeIf { it.isNotBlank() }?.let { Answer(it, true) }
            val json = try {
                JSONObject(text.substring(start, end + 1))
            } catch (_: JSONException) {
                return null
            }
            fun sentence(s: String) = if (s.last() in ".!?…") s else "$s."
            val answer = cleanSpeech(json.optString("answer").ifBlank { json.optString("say") })
                // "October [insert date]": a gap it didn't fill is no answer.
                .takeIf { it.isNotBlank() && !PLACEHOLDER.containsMatchIn(it) } ?: return null
            val found = json.optBoolean("found", true)
            val comment = cleanSpeech(json.optString("comment"))
                .takeIf { found && it.isNotBlank() && it.length <= 160 && numbersIn(it).isEmpty() && !NARRATING.containsMatchIn(it) }
                ?.takeIf { comment -> namesIn(comment).all { it in wordsOf("$answer $known") } }
            return Answer(listOfNotNull(sentence(answer), comment?.let(::sentence)).joinToString(" "), found)
        }

        /**
         * Whether every number [said] states is one [source] has, as written or rounded ("8.3
         * million" or "84 thousand" for 83,802) — so an answer can't slip in a score, a price or
         * a date the results never gave.
         */
        fun statesOnly(source: String, said: String): Boolean {
            val given = digitGroups(source)
            return digitGroups(said).all { n ->
                given.any { g ->
                    g.startsWith(n) ||
                        // Rounded up: "84" from "83802".
                        (n.length < 18 && g.length > n.length && g[n.length] >= '5' && (g.take(n.length).toLong() + 1).toString() == n)
                }
            }
        }

        /** The numbers in [text] as their digits alone ("$83,802.46" → "8380246"), spelled-out ones included, bar "one". */
        private fun digitGroups(text: String): List<String> {
            val words = text.lowercase(Locale.ENGLISH)
                .replace(Regex("(?<=[a-z])-(?=[a-z])"), " ")
                .replace(Regex("\\bone\\b"), "one_")
            val normalized = CommandRouter.wordsToDigits(words).replace(Regex("(\\d+)\\s+point\\s+(\\d+)"), "$1.$2")
            return Regex("\\d(?:[\\d,.]*\\d)?").findAll(normalized)
                .map { it.value.replace(",", "").replace(".", "").trimStart('0').ifEmpty { "0" } }.toList()
        }

        /**
         * Whether a retelling of a weather [fact] says what it says about rain: no "vai chover" when
         * none is expected, no "no rain" when a lot is. "Will it rain?" is what people ask, and a
         * small model answers it from the question rather than the forecast.
         */
        fun agreesOnRain(fact: String, said: String): Boolean {
            val dry = DRY.containsMatchIn(fact)
            val wet = Regex("(\\d+) (percent|por cento) (chance|de chance) (of|de) (rain|chuva)").find(fact)
                ?.groupValues?.get(1)?.toInt()?.let { it >= 50 } == true
            // Rain said to be coming: a mention with no "no", "não", "sem", "won't" just before it.
            val saysRain = RAIN_SAID.findAll(said).any { m -> !NEGATED.containsMatchIn(said.substring(maxOf(0, m.range.first - 16), m.range.first)) }
            return !(dry && saysRain) && !(wet && DRY_SAID.containsMatchIn(said))
        }

        private val DRY = wordRegex("\\b(no rain expected|sem previsão de chuva)\\b")
        private val RAIN_SAID = wordRegex("\\b(vai chover|vão chover|chover[áa]|vai ter chuva|expectativa de chuva|previsão de chuva|" +
            "chance de chuva|will rain|going to rain|expect(ing)? rain|rain is (coming|expected)|chance of rain)\\b")
        private val NEGATED = wordRegex("\\b(não|nao|sem|nenhuma|no|not|won'?t|without)\\b")
        private val DRY_SAID = wordRegex("\\b(não vai chover|sem chuva|sem previsão de chuva|no rain|won'?t rain|not going to rain|stay dry)\\b")

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
                // Emoji, which the voice would read out by name.
                .replace(Regex("[\\p{So}\\x{1F3FB}-\\x{1F3FF}\\x{FE0F}\\x{200D}]"), "")
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
                appendLine("- \"How far\" or \"how long to get to\" a named place is \"distance\"; \"where's the nearest\" or " +
                    "\"how far is the closest\" kind of place or shop (bus stop, pharmacy, Woolworths) is \"nearest\"; " +
                    "\"take me there\" is \"navigate\"; \"show me on the map\" is \"maps_search\".")
                appendLine("- \"How do I get to\" a place, \"how can I get there by bus\", \"which bus goes to\" it is " +
                    "\"directions\": she explains the way, then offers the map. \"Take me there\" is \"navigate\".")
                appendLine("- For \"distance\", \"nearest\" and \"directions\", \"mode\" is \"walk\" on foot (or with no car), " +
                    "\"drive\" by car, \"transit\" by bus, train or ferry, else empty. A follow-up like \"and walking?\" or " +
                    "\"how do I get there?\" is about the place just discussed — leave \"destination\" empty.")
                appendLine("- \"Remember that…\" is \"remember\" (the fact as a short sentence about them); \"forget…\" is \"forget\".")
                appendLine("- \"Remind me…\" is \"reminder\" — not \"remember\", not the calendar. \"text\" is what to remind them of, " +
                    "in their words. \"when\" is the exact local date and time it goes off, worked out from the date and time above " +
                    "(\"in 20 minutes\", \"tomorrow at 9\", \"on Friday\"), or empty if they didn't say when — she'll ask. \"repeat\" " +
                    "is none unless they said every day, weekdays, every week or every month. \"What are my reminders?\" is " +
                    "\"reminders_list\"; cancelling one is \"reminder_cancel\".")
                appendLine("- An event or appointment for their calendar is \"calendar_create\", its \"when\" worked out the same way.")
                appendLine("- Only act when they actually ask. Talking about calling someone is not a request to call; questions and chat need no action.")
                appendLine("- Live information — weather, battery, their calendar — only ever comes from its action. Never guess it.")
                appendLine("- \"look_up\" searches the web and answers from what it finds. Use it for anything current or that " +
                    "changes — news, scores and results, prices, opening hours, release dates, who holds a job now — for facts " +
                    "you're not sure of, and when they say search, google or look up. A follow-up on something looked up (\"when do " +
                    "they play next?\") is \"look_up\" too. Its \"query\" is a short web search that makes sense on its own, in the " +
                    "language they asked in: fill in who or what from the conversation and what you know of them (their team, " +
                    "their city). Timeless knowledge needs no search.")
                appendLine("- \"web_search\" only opens the search page on the phone, for when they ask to see or open it.")
                appendLine("- If something essential is missing (who to call, what to say), ask instead of guessing. Never invent contacts.")
                appendLine("- Keep a message's words exactly as they said them.")
                appendLine("- Speech recognition mishears names and songs; fix obvious mistakes.")
                appendLine("- For weather with no city named, leave \"city\" empty — the phone knows where it is.")
                if (ctx.language == Language.PORTUGUESE) {
                    // Brazilian Portuguese asks yes/no questions in a statement's words, and the
                    // recognizer seldom adds the question mark: "vai chover amanhã" got a forecast made up.
                    appendLine("- In Portuguese a question often reads like a statement: \"vai chover amanhã\" asks whether " +
                        "it will rain tomorrow. Treat it as the question it is. Action fields stay in English " +
                        "(\"day\": \"tomorrow\", \"mode\": \"walk\").")
                }
                appendLine()
                appendLine("REPLY FORMAT")
                appendLine("Reply with one JSON object and nothing else:")
                appendLine("{\"action\": <an action object, or null>, \"say\": \"<what you say out loud>\"}")
                appendLine("With an action, leave \"say\" empty — the phone reports what happened.")
                appendLine()
                appendLine("EXAMPLES")
                EXAMPLES.forEach { (user, reply) -> appendLine("\"$user\" → $reply") }
                if (ctx.language == Language.PORTUGUESE) EXAMPLES_PT.forEach { (user, reply) -> appendLine("\"$user\" → $reply") }
                // Dated from today, so a model that copies it still has the right day.
                val tomorrow = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(now.time + 86_400_000L))
                appendLine("\"remind me to call the dentist tomorrow at 9\" → {\"action\": {\"type\":\"reminder\",\"text\":\"call the dentist\"," +
                    "\"when\":\"$tomorrow 09:00\",\"repeat\":\"none\"}, \"say\": \"\"}")
            }.trimEnd()
        }

        // Few-shot pairs: big models barely need them, small local ones lean on them heavily —
        // for the format and actions, and for how much personality a reply carries.
        // For Portuguese: its questions that read like statements, with the action's fields in English.
        private val EXAMPLES_PT = listOf(
            "vai chover amanhã" to """{"action": {"type":"weather","city":"","day":"tomorrow"}, "say": ""}""",
            "tá frio lá fora?" to """{"action": {"type":"weather","city":"","day":"today"}, "say": ""}""",
        )

        private val EXAMPLES = listOf(
            "how's your day going?" to """{"action": null, "say": "Quiet so far, which suits me fine. How about yours — anything fun, or one of those days?"}""",
            "tell me a joke" to """{"action": null, "say": "I told my wifi we needed some space. Now it won't connect with me. Too soon?"}""",
            "why is the sky blue?" to """{"action": null, "say": "Sunlight bounces off the air, and blue bounces the most, so it reaches you from every direction. Sunsets go orange because by then the blue's scattered away. Nature's own filter."}""",
            "set a timer for ten minutes" to """{"action": {"type":"set_timer","hours":0,"minutes":10,"seconds":0}, "say": ""}""",
            "call mom" to """{"action": {"type":"call","name":"mom"}, "say": ""}""",
            "text Sam that I'll be there in five" to """{"action": {"type":"send_sms","name":"Sam","message":"I'll be there in five"}, "say": ""}""",
            "will it rain tomorrow?" to """{"action": {"type":"weather","city":"","day":"tomorrow"}, "say": ""}""",
            "how far am I from Bondi?" to """{"action": {"type":"distance","destination":"Bondi","mode":""}, "say": ""}""",
            "where's the closest pharmacy?" to """{"action": {"type":"nearest","what":"pharmacy","mode":""}, "say": ""}""",
            "how long to walk to the nearest bus stop?" to """{"action": {"type":"nearest","what":"bus stop","mode":"walk"}, "say": ""}""",
            "how do I get to Manly by bus?" to """{"action": {"type":"directions","destination":"Manly","mode":"transit"}, "say": ""}""",
            "what's bitcoin worth right now?" to """{"action": {"type":"look_up","query":"bitcoin price"}, "say": ""}""",
            "when does the next Avengers movie come out?" to """{"action": {"type":"look_up","query":"next Avengers movie release date"}, "say": ""}""",
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
            Action("play_music", Field("query", "<song or artist>"), Field("app", "<spotify|youtube|apple music|amazon music|deezer|samsung music|>")),
            Action("music_control", Field("control", "<pause|resume|next|previous>")),
            // Hours/minutes/seconds rather than a seconds total: small models get unit conversion wrong.
            Action("set_timer", Field("hours", "<int>", true), Field("minutes", "<int>", true), Field("seconds", "<int>", true)),
            Action("set_alarm", Field("hour", "<0-23>", true), Field("minute", "<0-59>", true)),
            Action("open_app", Field("name", "<app name>")),
            Action("look_up", Field("query", "<short web search>")),
            Action("web_search", Field("query", "<text>")),
            Action("navigate", Field("destination", "<place>")),
            Action("distance", Field("destination", "<place>"), Field("mode", "<walk|drive|transit|>")),
            Action("nearest", Field("what", "<kind of place or shop: bus stop, pharmacy, Woolworths…>"), Field("mode", "<walk|drive|transit|>")),
            Action("directions", Field("destination", "<place, or empty for the one just discussed>"), Field("mode", "<walk|drive|transit|>")),
            Action("maps_search", Field("query", "<place or kind of place>")),
            Action("open_url", Field("url", "<website>")),
            Action("ride", Field("destination", "<place>"), Field("app", "<uber|didi|>")),
            Action("order_food", Field("query", "<food or restaurant>"), Field("app", "<uber eats|doordash|>")),
            Action("note", Field("text", "<note text>")),
            Action("email", Field("to", "<contact or address>"), Field("subject", "<text>"), Field("body", "<text>")),
            Action("weather", Field("city", "<city, or empty for where they are>"), Field("day", "<today|tomorrow|monday…>")),
            Action("flashlight", Field("state", "<on|off>")),
            Action("battery"),
            Action("wifi"),
            Action("bluetooth", Field("state", "<on|off>")),
            Action("calendar_read"),
            Action("calendar_create", Field("title", "<text>"), Field("when", "<yyyy-MM-dd HH:mm, or empty>")),
            Action("reminder", Field("text", "<what to remind them of, in their words>"),
                Field("when", "<yyyy-MM-dd HH:mm, or empty if they didn't say>"), Field("repeat", "<none|daily|weekdays|weekly|monthly>")),
            Action("reminders_list"),
            Action("reminder_cancel", Field("what", "<which one, in their words, or empty for the last one>")),
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
