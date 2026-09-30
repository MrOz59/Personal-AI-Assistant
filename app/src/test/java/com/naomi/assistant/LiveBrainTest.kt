package com.naomi.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Date

/**
 * Opt-in live check of a real brain with Naomi's actual prompt and parser — how well a model
 * handles her format, actions and personality. Skipped unless NAOMI_LLM_URL is set, e.g.:
 *
 *   NAOMI_LLM_URL=http://127.0.0.1:11434/v1 NAOMI_LLM_MODEL=assistente-leve \
 *     ./gradlew :app:testDebugUnitTest --tests '*LiveBrainTest*' -i
 *
 * (NAOMI_LLM_KEY for servers that need one.) It prints each exchange rather than asserting:
 * the point is to judge a model, not to fail the build.
 */
class LiveBrainTest {

    @Test
    fun conversationAndActions() {
        val url = System.getenv("NAOMI_LLM_URL")
        assumeTrue("NAOMI_LLM_URL not set", !url.isNullOrBlank())
        // NAOMI_LLM_HOSTED=1 sends requests as for a hosted model (no schema); default is the
        // self-hosted path the Custom provider uses.
        val client = OpenAiCompatibleClient(
            url!!, System.getenv("NAOMI_LLM_KEY").orEmpty(), System.getenv("NAOMI_LLM_MODEL").orEmpty(),
            selfHosted = System.getenv("NAOMI_LLM_HOSTED") != "1"
        )
        // The app's own brain logic, including the second conversation pass for small models.
        val brain = CloudBrain(client, BrainSettings.DEFAULT_PERSONA)
        val facts = mapOf("name" to "Ozzy", "mom" to "Amma", "city" to "Sydney")
        val cases = listOf(
            "hey Naomi, how's it going?" to null,
            "tell me a joke" to null,
            "I'm bored" to null,
            "set a timer for five minutes" to "set_timer",
            "call my mom" to "call",
            "what's the weather like?" to "weather",
            "how's the weather tomorrow" to "weather",
            "tell me something interesting about octopuses" to null,
            "I think I'm going to call it a day" to null,
            "text John that I'm running late" to "send_sms",
            "what's my battery at?" to "battery",
            "wake me up at 6:30 tomorrow" to "set_alarm",
            "play Blinding Lights" to "play_music",
            "how far away am I from Riverside" to "distance",
            "where's the closest woolworths" to "nearest",
            "how far away am I from the closest bus stop" to "nearest",
        )
        var actionOk = 0
        for ((utterance, expected) in cases) {
            val started = System.nanoTime()
            val reply = runBlocking { brain.respond(utterance, emptyList(), TurnContext(facts)) }
            val ms = (System.nanoTime() - started) / 1_000_000
            val got = reply.action?.optString("type")
            if (got == expected) actionOk++
            println("[${ms}ms] ${if (got == expected) "OK " else "BAD"} \"$utterance\"")
            println("        say: ${reply.say}")
            println("        action: ${reply.action ?: "none"}  (expected ${expected ?: "none"})")
        }
        println("SUMMARY: right action $actionOk/${cases.size} (before the app's safety net)")
    }

    /** Phone results retold in her voice — and whether the numbers survive the retelling. */
    @Test
    fun narration() {
        val url = System.getenv("NAOMI_LLM_URL")
        assumeTrue("NAOMI_LLM_URL not set", !url.isNullOrBlank())
        val client = OpenAiCompatibleClient(
            url!!, System.getenv("NAOMI_LLM_KEY").orEmpty(), System.getenv("NAOMI_LLM_MODEL").orEmpty(),
            selfHosted = System.getenv("NAOMI_LLM_HOSTED") != "1"
        )
        val brain = CloudBrain(client, BrainSettings.DEFAULT_PERSONA)
        val facts = mapOf("name" to "Ozzy")
        for ((asked, fact) in listOf(
            "how's the weather tomorrow" to "Tomorrow in Springfield: mostly clear, with a high of 24 and a low of 15, and a 20 percent chance of rain.",
            "what's the weather like" to "It's 17 degrees and rainy in Springfield, with a high of 19 today and a 80 percent chance of rain.",
            "how far am I from Riverside" to "Riverside is about 3.1 kilometres away by road, around 7 minutes by car.",
            "set a timer for ten minutes" to "Timer set for 10 minute(s).",
            "what's my battery at" to "Battery is at 23 percent.",
        )) {
            val started = System.nanoTime()
            // The same prompt and check narrate() uses, but showing the retelling even when rejected.
            val raw = CloudBrain.cleanSpeech(client.complete(
                CloudBrain.narrationPrompt(BrainSettings.DEFAULT_PERSONA, TurnContext(facts, where = "Springfield"), Date(), fact),
                listOf(Turn(fromUser = true, text = asked)), creative = true
            ))
            val ms = (System.nanoTime() - started) / 1_000_000
            println("[${ms}ms] You: $asked")
            println("        fact:  $fact")
            println("        Naomi: $raw  ${if (CloudBrain.keepsNumbers(fact, raw)) "[kept]" else "[REJECTED → plain fact]"}")
        }
        brain.hashCode() // the brain under test is the one the app builds; narrate() wraps exactly this
    }

    /** A short conversation with history — how she holds a thread and a personality over turns. */
    @Test
    fun multiTurnChat() {
        val url = System.getenv("NAOMI_LLM_URL")
        assumeTrue("NAOMI_LLM_URL not set", !url.isNullOrBlank())
        val client = OpenAiCompatibleClient(
            url!!, System.getenv("NAOMI_LLM_KEY").orEmpty(), System.getenv("NAOMI_LLM_MODEL").orEmpty(),
            selfHosted = System.getenv("NAOMI_LLM_HOSTED") != "1"
        )
        val brain = CloudBrain(client, BrainSettings.DEFAULT_PERSONA)
        val facts = mapOf("name" to "Ozzy", "mom" to "Amma")
        val history = mutableListOf<Pair<String, String>>()
        for (line in listOf(
            "hey Naomi, I just got home from work",
            "it was rough, my boss was on my case all day",
            "any ideas to cheer me up?",
            "haha okay, what should I have for dinner then?",
        )) {
            val started = System.nanoTime()
            val reply = runBlocking { brain.respond(line, history, TurnContext(facts)) }
            val ms = (System.nanoTime() - started) / 1_000_000
            println("[${ms}ms] You: $line")
            println("        Naomi: ${reply.say}${reply.action?.let { "  [action $it]" } ?: ""}")
            history += line to reply.say
        }
    }

    /** What she'd keep from things the owner says — and, as important, what she'd let go. */
    @Test
    fun memoryExtraction() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        val known = listOf("[home city] Ozzy lives in Sydney", "[job] Ozzy works as a software engineer at Atlassian")
        for ((said, asked) in listOf(
            "my sister Ana is coming to visit from Lisbon next week" to null,
            "ugh I'm so tired today" to null,
            "I've stopped eating meat, I'm vegetarian now" to null,
            "I just got a job offer from Canva, I'm starting there next month" to null,
            "I think I'll call it a day" to null,
            "we moved to Melbourne last weekend" to null,
            "Biscuit" to "What's your dog called, by the way?",
            "what's the capital of Portugal" to null,
            "my mom's birthday is on the 12th of March and she loves orchids" to null,
            "I go to the gym every Monday and Thursday before work" to null,
        )) {
            val started = System.nanoTime()
            val found = runBlocking {
                brain.extractMemories(MemoryBank.pinDates(said, java.time.LocalDate.now()), "Ozzy", known, asked)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            println("[${ms}ms] ${asked?.let { "(Naomi: $it) " } ?: ""}Ozzy: $said")
            if (found.isEmpty()) println("        (nothing kept)")
            found.forEach { (topic, fact) -> println("        [$topic] $fact") }
        }
    }

    /** Notes on finished conversations: worth recalling when there's substance, NONE when not. */
    @Test
    fun conversationNotes() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        for (conversation in listOf(
            listOf(
                "hey Naomi, I just got home from work" to "Welcome back! How did it go?",
                "rough, my boss wants the migration done by Friday" to "Oof, that's tight. Is it doable?",
                "maybe, if I skip the gym this week" to "Don't skip it entirely — even one session will keep you sane.",
            ),
            listOf(
                "set a timer for ten minutes" to "Timer set for 10 minutes.",
                "what's the weather like" to "It's 18 degrees and sunny in Springfield.",
            ),
        )) {
            val started = System.nanoTime()
            val note = runBlocking { brain.summarizeConversation(conversation, "Ozzy") }
            val ms = (System.nanoTime() - started) / 1_000_000
            println("[${ms}ms] ${conversation.joinToString(" / ") { it.first }}")
            println("        note: ${note ?: "(none)"}")
        }
    }

    /** Whether recalled memories come up naturally — and only when they fit. */
    @Test
    fun conversationWithMemories() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        val memories = listOf(
            "Ozzy is vegetarian",
            "Ozzy's sister Ana lives in Lisbon",
            "Yesterday: Ozzy was nervous about his job interview at Canva on Thursday (1 October 2026); Naomi wished him luck.",
        )
        for (line in listOf("hey Naomi, I'm back", "what should I cook tonight?", "tell me a joke")) {
            val started = System.nanoTime()
            val reply = runBlocking { brain.respond(line, emptyList(), TurnContext(mapOf("name" to "Ozzy"), memories = memories)) }
            val ms = (System.nanoTime() - started) / 1_000_000
            println("[${ms}ms] You: $line")
            println("        Naomi: ${reply.say}${reply.action?.let { "  [action $it]" } ?: ""}")
        }
    }

    /** Misheard sentences, with the recognizer's runner-up guesses: does the brain pick the right one? */
    @Test
    fun misheardWithOtherGuesses() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        for ((heard, others) in listOf(
            "how can I get to the closest work by bus" to listOf("how can I get to the closest Woolworths by bus", "how can I get to the closest walk by bus"),
            "do the woolsworth" to listOf("to the Woolworths", "do the Woolworths"),
            "set a timer for fifteen minutes" to listOf("set a timer for fifty minutes"),
            "call my mum" to listOf("call my mom", "cool my mum"),
        )) {
            val reply = runBlocking { brain.respond(heard, emptyList(), TurnContext(mapOf("name" to "Ozzy"), heardAs = others)) }
            println("heard \"$heard\" (or: ${others.joinToString(" | ")})")
            println("        action: ${reply.action ?: "none"}   say: ${reply.say.take(100)}")
        }
    }

    /** Questions that need a search, and ones that don't: does the brain reach for "look_up", with a query that stands alone? */
    @Test
    fun lookUpDecisions() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        val cases = listOf(
            "who won the NRL grand final this year?" to "look_up",
            "what's the price of gold today" to "look_up",
            "is Bunnings open on public holidays?" to "look_up",
            "search for the best pizza in town" to "look_up",
            "google how tall Mount Everest is" to "look_up",
            "when is the next iPhone coming out" to "look_up",
            "who's the prime minister of Japan right now" to "look_up",
            "open google and search for cheap flights" to "web_search",
            "why do cats purr?" to null,
            "tell me a joke" to null,
        )
        var ok = 0
        for ((utterance, expected) in cases) {
            val reply = runBlocking { brain.respond(utterance, emptyList(), TurnContext(mapOf("name" to "Ozzy"))) }
            val got = reply.action?.optString("type")
            if (got == expected) ok++
            println("${if (got == expected) "OK " else "BAD"} \"$utterance\" → ${reply.action ?: "say: ${reply.say.take(90)}"}")
        }
        // A follow-up: the query has to name what "they" are.
        val history = listOf("who won the NRL grand final last year?" to "The Brisbane Broncos, 26 to 22 over the Storm.")
        val followUp = runBlocking { brain.respond("when do they play next?", history, TurnContext(mapOf("name" to "Ozzy"))) }
        println("follow-up \"when do they play next?\" → ${followUp.action ?: "say: ${followUp.say.take(90)}"}")
        println("SUMMARY: $ok/${cases.size}")
    }

    /**
     * Real searches answered from their results: NAOMI_SEARCH_URL is a SearXNG (blank: DuckDuckGo).
     * Prints each answer, how long it took, and whether it held up (null: a number it made up).
     */
    @Test
    fun answersFromSearch() {
        val client = liveClient() ?: return
        val brain = CloudBrain(client, BrainSettings.DEFAULT_PERSONA)
        val search = SearchClient()
        for ((asked, query) in listOf(
            "who won the NRL grand final last year?" to "2025 NRL grand final winner",
            "who won the 2025 NRL grand final?" to "2025 NRL grand final winner",
            "what's bitcoin worth right now?" to "bitcoin price",
            "when's the next full moon?" to "next full moon date",
            "who's the prime minister of Japan right now?" to "current prime minister of Japan",
            "what's the capital of the moon?" to "capital of the moon",
        )) {
            val started = System.nanoTime()
            val found = runBlocking { search.search(query, System.getenv("NAOMI_SEARCH_URL").orEmpty(), "en-AU") }
            val searched = (System.nanoTime() - started) / 1_000_000
            if (found == null) { println("\"$asked\": no results"); continue }
            val answer = runBlocking { brain.answerFrom(asked, query, found.results, emptyList(), TurnContext(mapOf("name" to "Ozzy"))) }
            val total = (System.nanoTime() - started) / 1_000_000
            println("\"$asked\" [${found.source}, search ${searched}ms, total ${total}ms]")
            println("        ${answer ?: "REJECTED (or unreachable)"}")
            if (answer == null) {
                // Why: the same request again, raw.
                val ctx = TurnContext(mapOf("name" to "Ozzy"))
                val raw = client.complete(CloudBrain.answerPrompt(BrainSettings.DEFAULT_PERSONA, ctx, Date()),
                    listOf(Turn(fromUser = true, text = CloudBrain.searchTurn(asked, query, found.results, Date()))), creative = true)
                println("        raw retry: $raw")
                found.results.forEach { println("          - ${it.title}: ${it.snippet.take(140)}") }
            }
        }
    }

    /** Weather asked in Portuguese, where a question reads like a statement: does it reach for "weather"? */
    @Test
    fun portugueseWeather() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        val ctx = TurnContext(mapOf("name" to "Ozzy"), language = Language.PORTUGUESE)
        var ok = 0
        val cases = listOf("Vai chover amanhã", "tá frio hoje", "vai fazer sol no sábado", "como tá o tempo lá fora",
            "me conta uma piada", "estou cansado hoje")
        for (utterance in cases) {
            val reply = runBlocking { brain.respond(utterance, emptyList(), ctx) }
            val weather = reply.action?.optString("type") == "weather"
            if (weather == (cases.indexOf(utterance) < 4)) ok++
            println("\"$utterance\" → ${reply.action ?: "say: ${reply.say.take(100)}"}")
        }
        // The follow-up that went wrong: correcting her after she took the question for a statement.
        val history = listOf("Vai chover amanhã" to "Parece que você está preparado para a chuva de amanhã. Tem algum plano?")
        val followUp = runBlocking { brain.respond("mas foi uma pergunta", history, ctx) }
        println("follow-up \"mas foi uma pergunta\" → ${followUp.action ?: "say: ${followUp.say.take(100)}"}")
        println("SUMMARY: $ok/${cases.size}")
    }

    /** Switched to English with the talk so far in Portuguese: does she answer in English? */
    @Test
    fun englishAfterPortuguese() {
        val brain = CloudBrain(liveClient() ?: return, BrainSettings.DEFAULT_PERSONA)
        val history = listOf(
            "vai chover amanhã" to "Amanhã em Cromer vai ser nublado, com máxima de 28 e mínima de 14, sem chuva.",
            "em nome" to "Ahah, parece que o reconhecimento de voz está confuso hoje! Você está falando comigo?",
        )
        for (said in listOf("I was talking to somebody else, not to you", "tell me a joke", "how's your day going?")) {
            val reply = runBlocking { brain.respond(said, history, TurnContext(mapOf("name" to "Ozzy"), language = Language.ENGLISH)) }
            println("\"$said\" → ${reply.action ?: reply.say.take(140)}")
        }
    }

    /** The brain from NAOMI_LLM_URL / _MODEL / _KEY, or null (test skipped) when it isn't set. */
    private fun liveClient(): LlmClient? {
        val url = System.getenv("NAOMI_LLM_URL")
        assumeTrue("NAOMI_LLM_URL not set", !url.isNullOrBlank())
        return OpenAiCompatibleClient(
            url!!, System.getenv("NAOMI_LLM_KEY").orEmpty(), System.getenv("NAOMI_LLM_MODEL").orEmpty(),
            selfHosted = System.getenv("NAOMI_LLM_HOSTED") != "1"
        )
    }

    /** NAOMI_DUMP_DIR=<dir>: writes the exact system prompt and reply schema, for tuning elsewhere. */
    @Test
    fun dumpPromptAndSchema() {
        val dir = System.getenv("NAOMI_DUMP_DIR")
        assumeTrue("NAOMI_DUMP_DIR not set", !dir.isNullOrBlank())
        val prompt = CloudBrain.systemPrompt(
            BrainSettings.DEFAULT_PERSONA,
            TurnContext(mapOf("name" to "Ozzy", "mom" to "Amma", "city" to "Sydney")),
            Date()
        )
        java.io.File(dir!!, "system_prompt.txt").writeText(prompt)
        java.io.File(dir, "reply_schema.json").writeText(CloudBrain.REPLY_SCHEMA)
    }
}
