package com.naomi.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

/** What Naomi should say back, and whether she should immediately listen again. */
data class Reply(val text: String, val listenAgain: Boolean = false)

/** Who said a turn, told by voice: the owner, someone else, or unknown (no audio / no voiceprint). */
enum class Who { OWNER, GUEST, UNKNOWN }

/**
 * Orchestrates understanding + action, plus multi-turn follow-ups:
 *
 *   1. If Naomi just asked something (e.g. "WhatsApp or text?"), feed this turn to that follow-up.
 *   2. SMART MODE (on, with a brain set up): the cloud model hears every turn, answers in Naomi's
 *      personality and picks a phone action when one is asked for — real conversation instead of
 *      keyword matching. If the cloud can't be reached, the offline path below takes over.
 *   3. OFFLINE: the FAST + FREE keyword router handles common commands on-device, and the
 *      on-device Gemma (if installed) takes the rest. Nothing leaves the phone.
 *
 * Both paths execute actions through the same CommandRouter.execute* methods.
 *
 * It also keeps Naomi's long-term memory ([MemoryBank]): "remember that…" / "forget…" on
 * request, facts learned from what the owner tells her, and a note of each conversation when it
 * ends. Each turn, the memories related to it go to the cloud brain with it — never a guest's.
 */
class AssistantBrain(context: Context) {

    private val appContext = context.applicationContext
    private val router = CommandRouter(context.applicationContext)
    private val localBrain = LocalBrain(context.applicationContext)
    private val weather = WeatherClient()
    private val distance = DistanceClient(context.applicationContext)
    val memory = MemoryStore(context.applicationContext)
    /** What she's learned about the owner, and notes on past conversations. */
    val memories = MemoryBank.get(context.applicationContext)
    val settings = BrainSettings(context.applicationContext)

    // Work that mustn't hold up a reply: learning from what was said, noting down a finished
    // conversation. Not tied to the screen, so a note started as it closes still gets written.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Smart mode (UI toggle): the cloud brain may be used. When false, nothing leaves the phone. */
    @Volatile var smartMode = false

    // A follow-up awaiting the user's next reply (e.g. choosing the messaging app).
    private var pending: ((String) -> CommandRouter.Result)? = null
    private var pendingTries = 0

    // Rolling conversation memory (user, naomi) so follow-ups and chat stay in context.
    private val history = ArrayDeque<Pair<String, String>>()
    private val maxHistory = 10

    // The conversation so far as the owner had it — for its note when it ends — and whether any
    // of it was real talk rather than quick commands. [chatTurn] marks the turn in progress as talk.
    private val conversation = ArrayDeque<Pair<String, String>>()
    private var talked = false
    private var chatTurn = false
    private var lastTurnAt = 0L

    // What was learned last, and when: "forget that" right after means these.
    @Volatile private var lastLearned: List<Long> = emptyList()
    @Volatile private var lastLearnedAt = 0L

    // True when Naomi's last reply invited an answer (a question, or a reply in conversation
    // mode) and we're waiting for the user to speak again — no wake word needed.
    private var expectingFollowUp = false
    private var followUpStreak = 0
    private val maxFollowUpStreak = 8

    /** True while Naomi is waiting for the user's next answer — structured OR conversational.
     *  The UI uses this to decide whether to reopen the mic after speaking. */
    val hasPending: Boolean get() = pending != null || expectingFollowUp

    /** True while a structured choice is pending ("WhatsApp or text?"), answerable in one word. */
    val awaitingChoice: Boolean get() = pending != null

    /**
     * Public entry point. Runs the turn, then decides whether Naomi's reply is a question that
     * should reopen the mic, and records the exchange so the next turn has conversational context.
     */
    suspend fun handle(userText: String, speaker: Who = Who.UNKNOWN): Reply {
        val now = System.currentTimeMillis()
        // A long pause ends a conversation: it gets noted down, and the next one starts fresh.
        if (history.isNotEmpty() && now - lastTurnAt > CONVERSATION_GAP_MS) cancel()
        lastTurnAt = now
        memories.ownerName = memory.get("name")
        val herLastLine = history.lastOrNull()?.second
        chatTurn = false

        val reply = handleInternal(userText, speaker)

        // Any reply that reads as a question keeps the conversation going (mic reopens).
        val question = reply.listenAgain || isQuestion(reply.text)

        // A structured Ask manages its own re-listen via `pending`; only arm the conversational
        // path when there's no structured follow-up already queued.
        if (question && pending == null) {
            followUpStreak++
            // Guard against a runaway loop: after a few hands-free turns, wait for "Naomi" again.
            expectingFollowUp = followUpStreak <= maxFollowUpStreak
        } else {
            expectingFollowUp = false
            if (!question) followUpStreak = 0
        }

        // Remember this exchange for the next turn's context — marking whose line it was.
        history.addLast((if (speaker == Who.GUEST) "(a guest) $userText" else userText) to reply.text)
        while (history.size > maxHistory) history.removeFirst()

        // The owner's side of it, to learn from and to note down when it ends. A guest's isn't kept.
        if (speaker != Who.GUEST) {
            conversation.addLast(userText to reply.text)
            while (conversation.size > MAX_NOTED_TURNS) conversation.removeFirst()
            if (chatTurn) {
                talked = true
                learnFrom(userText, herLastLine)
            }
        }

        return reply.copy(listenAgain = question || reply.listenAgain)
    }

    /** A reply is treated as a question (→ reopen mic) when it ends with a question mark. */
    private fun isQuestion(text: String): Boolean = text.trim().endsWith("?")

    /** Actions whose report is worth hearing in her own voice. The rest hand the screen to another
     *  app (a call, the map, music) or ask a follow-up, and are left as the phone says them. */
    private val NARRATED = setOf("weather", "battery", "calendar_read", "distance", "set_timer", "set_alarm", "flashlight")

    /** What a guest may ask for. Anything personal — calls, messages, calendar, notes, apps,
     *  rides, orders — stays the owner's. */
    private val GUEST_ACTIONS = setOf("weather", "battery", "set_timer", "flashlight", "play_music", "music_control",
        "distance", "maps_search", "web_search")

    private fun ownerName(): String = memory.get("name") ?: "my owner"

    /** "Thanks" / "that's all" / "bye" — the user is wrapping up, so don't keep listening. */
    private fun isGoodbye(text: String): Boolean =
        Regex("^(ok(ay)?[,.]?\\s+)?(thanks|thank you|that'?s (all|it)|bye|goodbye|good ?night|never ?mind|stop|nothing|no thanks)\\b")
            .containsMatchIn(text.lowercase().trim())

    /**
     * Loads a small self-hosted brain while the user is still talking, so a first reply after
     * it's gone idle (Ollama unloads models after a few minutes) isn't held up by the load.
     * Blocking — call off the main thread.
     */
    fun warmUp() {
        if (smartMode) settings.client()?.takeIf { it.small }?.warmUp()
    }

    /** The cloud brain for this turn, or null when smart mode is off or no brain is set up. */
    private fun cloud(): CloudBrain? =
        if (smartMode) settings.client()?.let { CloudBrain(it, settings.persona) } else null

    private suspend fun handleInternal(userText: String, speaker: Who): Reply {
        val guest = speaker == Who.GUEST
        // A question Naomi asked the owner ("Send it?") is theirs to answer.
        if (guest && pending != null) return Reply("That one's for ${ownerName()} to answer.", listenAgain = true)

        pending?.let { onAnswer ->
            val result = onAnswer(userText)
            if (result is CommandRouter.Result.Ask) {
                pendingTries++
                if (pendingTries >= 5) { reset(); return Reply("Okay, let's leave it for now.") }
            }
            return resultToReply(result) { reset(); Reply("Okay, never mind.") }
        }

        // Explicit memory requests are handled on the phone, the same with or without the cloud.
        if (!guest) memoryCommand(userText)?.let { return it }

        val lower = userText.lowercase()

        // 0. DAILY BRIEFING: greeting + date + weather + today's calendar, in one go (owner only).
        if (!guest && Regex("\\b(good morning|morning briefing|brief me|briefing|start my day|how'?s my day|what'?s my day|the rundown|catch me up)\\b")
                .containsMatchIn(lower)) {
            return Reply(dailyBriefing())
        }

        // 1. SMART MODE: the cloud brain understands the whole turn — chat or action.
        cloud()?.let { cloud ->
            cloudTurn(cloud, userText, speaker)?.let { return it }
            // The cloud couldn't be reached: handle it offline below.
        }

        // 2a. WEATHER: needs a live network lookup (async), so it can't live in the sync router.
        if (Regex("\\b(weather|temperature|forecast)\\b").containsMatchIn(lower)) {
            return Reply(weatherReport(extractCity(userText), WeatherClient.dayOffset(userText)))
        }

        // Offline, guests get only the weather above: the router acts without asking whose voice it was.
        if (guest) return Reply("Sorry, while my smart mode's off I only take requests from ${ownerName()}.")

        // "What do you remember about…?" — answered straight from memory.
        recallOffline(userText)?.let { return it }

        // Feed persisted preferences into the router before each turn.
        router.preferredMusicApp = memory.get("music app") ?: ""

        // 2b. FAST + FREE + PRIVATE: keyword router handles common commands on-device.
        val routed = router.tryHandle(userText)
        if (routed !is CommandRouter.Result.NotHandled) {
            return resultToReply(routed) { Reply("I couldn't do that.") }
        }

        // 2c. ON-DEVICE LLM (Gemma), if installed: picks an action or answers from its own knowledge.
        localBrain.route(userText)?.let { intent ->
            val action = intent.optString("action")
            if (action == "chat") return Reply(intent.optString("reply").ifBlank { "Sorry, could you say that again?" })
            return dispatch(action, intent, userText, say = "")
        }

        return Reply(
            if (smartMode && settings.isConfigured()) "I can't reach my cloud brain right now, and that one's beyond me offline."
            else "Sorry, I didn't catch that — I can do timers, alarms, calls, music, messages, calendar, and answer questions."
        )
    }

    /** One turn through the cloud brain, or null if it couldn't be reached (caller falls back). */
    private suspend fun cloudTurn(cloud: CloudBrain, userText: String, speaker: Who): Reply? {
        val ctx = TurnContext(
            // A guest hears nothing of the owner's life — only their name, so she never calls the guest by it.
            facts = if (speaker == Who.GUEST) memory.all().filterKeys { it == "name" } else memory.all(),
            // The neighbourhood (never coordinates) lets her place local names and questions.
            where = DeviceLocation.current(appContext)?.name,
            speaker = speaker,
            memories = recall(userText, speaker),
        )
        val response = try {
            cloud.respond(userText, history.toList(), ctx)
        } catch (e: LlmException) {
            android.util.Log.e("Naomi", "Cloud brain failed: ${e.message}")
            return null
        }
        val action = response.action ?: return missedAction(userText, speaker)?.let { narrated(cloud, userText, it, ctx) } ?: run {
            chatTurn = true
            Reply(
                response.say.ifBlank { "Sorry, I lost my train of thought — say that again?" },
                // Conversation mode: keep listening after a spoken answer, unless they're wrapping up.
                listenAgain = settings.conversationMode && !isGoodbye(userText)
            )
        }
        // The phone reports what actually happened — a model's line alongside an action can claim
        // results that didn't happen (small models especially). Reports worth hearing in her own
        // voice are then retold by the brain, numbers checked.
        val type = action.optString("type")
        if (speaker == Who.GUEST && type !in GUEST_ACTIONS) {
            return Reply("Sorry, that's something only ${ownerName()} can ask me to do.")
        }
        val reply = dispatch(type, action, userText, say = "")
        return if (type in NARRATED) narrated(cloud, userText, reply, ctx) else reply
    }

    /** [reply]'s report retold in her voice, or [reply] as is if the retelling fails its check.
     *  Questions (follow-ups awaiting an answer) are left alone. */
    private suspend fun narrated(cloud: CloudBrain, userText: String, reply: Reply, ctx: TurnContext): Reply {
        if (reply.listenAgain) return reply
        return cloud.narrate(userText, reply.text, history.toList(), ctx)
            ?.let { reply.copy(text = it) } ?: reply
    }

    // ── Memory ─────────────────────────────────────────────────────────────────

    /**
     * The memories worth having in mind this turn, as lines for the prompt: those related to what
     * was just said (and the line before, so "what about her birthday?" still finds the sister),
     * everything recent when they ask what she knows about them, and — opening a new conversation —
     * how the last one went. Nothing for a guest.
     */
    private suspend fun recall(userText: String, speaker: Who): List<String> {
        if (speaker == Who.GUEST) return emptyList()
        val opening = history.isEmpty()
        val query = listOfNotNull(history.lastOrNull()?.first, userText).joinToString(" ")
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val found = if (asksWhatSheKnows(userText)) memories.overview(OVERVIEW_SIZE) else {
                val related = memories.relevant(query, MemoryBank.Kind.FACT, RECALLED_FACTS, now = now) +
                    memories.relevant(query, MemoryBank.Kind.EPISODE, RECALLED_NOTES, now = now)
                val last = if (opening) memories.lastEpisode(RECENT_NOTE_MS, now) else null
                if (last == null || related.any { it.id == last.id }) related else related + last
            }
            found.map { MemoryBank.describe(it, now) }
        }
    }

    private fun asksWhatSheKnows(text: String): Boolean =
        Regex("\\b(know|remember|learned|learnt) about me\\b|^what do you (know|remember)\\s*[?.!]*$", RegexOption.IGNORE_CASE)
            .containsMatchIn(text.trim())

    /**
     * "Remember that…" and "forget…", done on the phone — instant, and never misread by a model.
     * Null if [userText] isn't one.
     */
    private fun memoryCommand(userText: String): Reply? {
        val text = userText.trim().replace(LEADING_NAME, "")
        REMEMBER.find(text)?.let { m ->
            val said = m.groupValues[1].trim().trimEnd('.', '!', '?')
            return if (said.split(Regex("\\s+")).size < 2) null else Reply(rememberFact(said))
        }
        FORGET.find(text)?.let { m -> return forgetReply(m.groupValues[1], fromModel = false) }
        return null
    }

    /** Keeps [said] — the owner's own words, or a model's retelling — as a memory about them. */
    private fun rememberFact(said: String): String {
        val stored = memories.remember(tidy(MemoryBank.inThirdPerson(said, memory.get("name") ?: "The user")))
        lastLearned = listOf(stored.id)
        lastLearnedAt = System.currentTimeMillis()
        android.util.Log.i("Naomi", "Remembered: ${stored.text}")
        return CONFIRMATIONS.random()
    }

    /**
     * Forgets what [what] describes. "That" or "it" means what she learned a moment ago; if
     * nothing was, a bare "forget it" isn't about memory at all (null, unless a model asked for it).
     */
    private fun forgetReply(what: String, fromModel: Boolean): Reply? {
        // "Forget it, never mind" is as vague as "forget it".
        val target = what.replace(Regex("\\b(never ?mind|please|thanks|thank you|naomi)\\b", RegexOption.IGNORE_CASE), " ")
            .trim().trimEnd('.', '!', '?', ',')
        val vague = memories.terms(target).isEmpty()
        if (vague && Regex("\\b(everything|all)\\b", RegexOption.IGNORE_CASE).containsMatchIn(target)) {
            return Reply("I'd rather not wipe my memory on a voice command. You can clear it on the Memory screen.")
        }
        val gone = when {
            !vague -> memories.forget(target)
            System.currentTimeMillis() - lastLearnedAt <= JUST_LEARNED_MS -> memories.delete(lastLearned).also { lastLearned = emptyList() }
            fromModel -> return Reply("Forget what, exactly?", listenAgain = true)
            else -> return null
        }
        android.util.Log.i("Naomi", "Forgot: ${gone.joinToString(" | ") { it.text }}")
        return Reply(when (gone.size) {
            0 -> "I don't have anything like that in my memory. Everything I remember is on the Memory screen."
            1 -> "Done — that's forgotten."
            else -> "Done — I've forgotten ${gone.size} things about that."
        })
    }

    /** Offline: "what do you know about me?" / "what do you remember about…?", from memory. */
    private fun recallOffline(userText: String): Reply? {
        val found = when {
            asksWhatSheKnows(userText) -> memories.overview(OFFLINE_RECALL)
            RECALL.containsMatchIn(userText) -> memories.relevant(userText, MemoryBank.Kind.FACT, OFFLINE_RECALL)
            else -> return null
        }
        if (found.isEmpty()) return Reply("Nothing on that yet. Tell me, and I'll remember.")
        return Reply("Here's what I remember: " + found.joinToString(" ") { it.text.trimEnd('.') + "." })
    }

    /**
     * Picks out what's worth remembering from something the owner just said — in the background,
     * so the reply isn't held up. Only talk about themselves, or an answer to her question
     * ([herLastLine]), goes to the brain for it.
     */
    private fun learnFrom(userText: String, herLastLine: String?) {
        if (!settings.learnMemories) return
        val answering = herLastLine?.trim()?.endsWith("?") == true
        if (!answering && !aboutThemselves(userText)) return
        val cloud = cloud() ?: return
        val name = memory.get("name")
        background.launch {
            // Related facts already kept, so a change updates them instead of adding a contradiction.
            val known = memories.relevant(userText, MemoryBank.Kind.FACT, KNOWN_FOR_LEARNING, markUsed = false)
                .map { if (it.topic.isBlank()) it.text else "[${it.topic}] ${it.text}" }
            val said = MemoryBank.pinDates(userText, LocalDate.now())
            val learned = cloud.extractMemories(said, name, known, herLastLine?.takeIf { answering }).map { (topic, fact) ->
                // A model now and then writes the owner's words back as they were ("I have a sister…").
                val about = if (FIRST_PERSON_START.containsMatchIn(fact)) MemoryBank.inThirdPerson(fact, name ?: "The user") else fact
                memories.remember(tidy(about), topic)
            }
            if (learned.isEmpty()) return@launch
            lastLearned = learned.map { it.id }
            lastLearnedAt = System.currentTimeMillis()
            android.util.Log.i("Naomi", "Learned: ${learned.joinToString(" | ") { it.text }}")
        }
    }

    /** Whether something said is about the speaker's own life — worth a look for things to learn. */
    private fun aboutThemselves(text: String): Boolean =
        text.trim().split(Regex("\\s+")).size >= 4 &&
            Regex("\\b(i|i'm|im|i've|i'd|i'll|my|me|mine|myself|we|we're|our|us)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * Notes down how the conversation that just ended went — in the background — unless it was
     * only quick commands.
     */
    private fun noteConversation() {
        val said = conversation.toList()
        val worthIt = talked
        conversation.clear()
        talked = false
        if (!worthIt || said.isEmpty() || !settings.learnMemories) return
        val cloud = cloud() ?: return
        val name = memory.get("name")
        background.launch {
            val note = cloud.summarizeConversation(said, name) ?: return@launch
            android.util.Log.i("Naomi", "Noted: ${memories.addEpisode(tidy(note)).text}")
        }
    }

    /** A memory as it's kept: about the owner by name, with its relative dates pinned down. */
    private fun tidy(text: String): String {
        val name = memory.get("name")
        val named = if (name != null) text.replace(Regex("\\b[Tt]he user\\b"), name) else text
        return MemoryBank.pinDates(named.trim(), LocalDate.now())
    }

    /**
     * A clear request the brain answered in words instead of acting on — small models do this,
     * and make up live facts while at it. Catches the ones that matter most: questions about
     * live information (weather, battery, calendar — never let those be guessed) and a message
     * with an explicit body ("text X that Y"), which the offline router parses reliably and
     * confirms before sending.
     */
    private suspend fun missedAction(userText: String, speaker: Who): Reply? {
        val lower = userText.lowercase()
        val guest = speaker == Who.GUEST
        val asking = Regex("\\b(what|what's|how|how's|how much|is it|will it|tell me|check|any|do i)\\b").containsMatchIn(lower)
        if (asking && Regex("\\b(weather|forecast|temperature)\\b").containsMatchIn(lower)) {
            return Reply(weatherReport(extractCity(userText), WeatherClient.dayOffset(userText)))
        }
        if (asking && Regex("\\bbattery\\b").containsMatchIn(lower)) {
            return resultToReply(router.executeBatteryLevel()) { Reply("I couldn't read the battery.") }
        }
        if (!guest && asking && Regex("\\b(calendar|schedule|agenda|meetings?)\\b").containsMatchIn(lower)) {
            return resultToReply(router.executeCalendarRead()) { Reply("I couldn't read your calendar.") }
        }
        if (asksDistance(lower)) {
            Regex("\\b(?:from|to)\\s+(.+)$").findAll(lower).lastOrNull()?.let { m ->
                return Reply(distanceReport(m.groupValues[1].replace(Regex("[?.!]+$"), "").trim()))
            }
        }
        if (!guest && Regex("\\b(text|message|whatsapp|sms|dm)\\b.+\\b(that|saying|to say)\\b").containsMatchIn(lower)) {
            val routed = router.tryHandle(userText)
            if (routed !is CommandRouter.Result.NotHandled) return resultToReply(routed) { Reply("I couldn't do that.") }
        }
        return null
    }

    /**
     * A spoken morning briefing: time-appropriate greeting, today's date, the weather for the
     * user's stored home city (a "city"/"home" fact), and today's calendar events.
     * Reuses the existing weather + calendar plumbing so it stays consistent with those commands.
     */
    private suspend fun dailyBriefing(): String {
        val sb = StringBuilder()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        sb.append(
            when {
                hour < 12 -> "Good morning!"
                hour < 17 -> "Good afternoon!"
                else -> "Good evening!"
            }
        )
        val df = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault())
        sb.append(" It's ${df.format(java.util.Date())}.")

        // Weather where the phone is (or the saved home city) — skipped if neither is known.
        val w = weatherReport("", 0)
        if (!w.startsWith("Which") && !w.startsWith("I couldn't") && !w.startsWith("I'm offline")) sb.append(" $w")

        // Today's calendar.
        val cal = router.executeCalendarRead()
        if (cal is CommandRouter.Result.Handled && cal.reply.isNotBlank()) sb.append(" ${cal.reply}")

        return sb.toString()
    }

    private fun homeCity(): String? = memory.get("city") ?: memory.get("home city") ?: memory.get("home")

    private fun asksDistance(lower: String): Boolean =
        Regex("\\b(how far|distance (to|from)|how long (does it take |would it take |will it take )?to (get|drive))\\b").containsMatchIn(lower)

    /**
     * How far the phone is from [destination], by road when possible. A name the speech
     * recognizer mangled ("Wooster") gets one retry through the brain, which knows the area.
     */
    private suspend fun distanceReport(destination: String): String {
        if (destination.isBlank()) return "Where to?"
        val here = DeviceLocation.current(appContext)
            ?: return "I can't tell where you are right now. Is location turned on?"
        val found = distance.locate(destination, here)
            ?: cloud()?.resolvePlace(destination, here.name)?.let { distance.locate(it, here) }
            ?: return "I couldn't find anywhere called $destination."
        return distance.describe(here, found)
    }

    /**
     * Weather for [city], [day] days ahead. With no city named: where the phone is (GPS), else
     * the saved home city, else she asks which city.
     */
    private suspend fun weatherReport(city: String, day: Int): String {
        if (city.isNotBlank()) return weather.forecast(city, day)
        val here = DeviceLocation.current(appContext)
        return weather.forecast(if (here == null) homeCity().orEmpty() else "", day, here)
    }

    /** Words after "in/at/for", stripped of weather/time filler — the city for a weather query. */
    private fun extractCity(text: String): String {
        val m = Regex("\\b(?:in|at|for)\\s+([a-zA-Z .]+)").find(text.lowercase()) ?: return ""
        return m.groupValues[1]
            .replace(Regex("\\b(weather|temperature|forecast|today|tonight|tomorrow|day after|now|right now|please|" +
                "this week|the weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), "")
            .replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Executes a structured action picked by a model — [type] with its fields in [args] (see
     * CloudBrain.ACTIONS / LocalBrain.ROUTE_PROMPT). [say] is the model's own line, spoken before
     * the phone's report of what actually happened.
     */
    private suspend fun dispatch(type: String, args: JSONObject, original: String, say: String): Reply {
        // "How far is X" is a question, not a trip — even when a model reaches for the map.
        if (type == "distance" || (type in setOf("navigate", "maps_search") && asksDistance(original.lowercase()))) {
            return Reply(join(say, distanceReport(args.optString("destination").ifBlank { args.optString("query") })))
        }
        if (type == "remember") {
            return Reply(rememberFact(args.optString("fact").ifBlank { REMEMBER.find(original)?.groupValues?.get(1) ?: original }))
        }
        if (type == "forget") return forgetReply(args.optString("what"), fromModel = true) ?: Reply("Okay.")
        if (type == "weather") {
            // A day the user actually said wins over the model's reading of it.
            val day = WeatherClient.dayOffset(original).takeIf { it != 0 } ?: WeatherClient.dayOffset(args.optString("day"))
            return Reply(join(say, weatherReport(args.optString("city"), day)))
        }
        val result = when (type) {
            "call" -> withContact(args.optString("name")) { router.executeCall(it) }
            "whatsapp_call" -> withContact(args.optString("name")) { router.executeWhatsAppCall(it, video = false) }
            "whatsapp_video" -> withContact(args.optString("name")) { router.executeWhatsAppCall(it, video = true) }
            "send_sms" -> withContact(args.optString("name")) { router.executeMessage(it, messageOf(args, original), MsgChannel.SMS) }
            "whatsapp" -> withContact(args.optString("name")) { router.executeMessage(it, messageOf(args, original), MsgChannel.WHATSAPP) }
            "play_music" -> router.executePlay(args.optString("query"), args.optString("app"))
            "music_control" -> router.executeMusicControl(args.optString("control"))
            // Numbers the user actually said win over a model's reading of them. The cloud brain
            // splits hours/minutes/seconds; the on-device Gemma sends seconds only.
            "set_timer" -> router.executeTimer(CommandRouter.spokenSeconds(original)
                ?: (args.optInt("hours") * 3600 + args.optInt("minutes") * 60 + args.optInt("seconds")))
            "set_alarm" -> (CommandRouter.spokenClock(original) ?: (args.optInt("hour") to args.optInt("minute")))
                .let { (hour, minute) -> router.executeAlarm(hour, minute) }
            "open_app" -> router.executeOpenApp(args.optString("name"))
            "web_search" -> router.executeWebSearch(args.optString("query"))
            "navigate" -> router.executeNavigate(args.optString("destination"))
            "maps_search" -> router.executeMapsSearch(args.optString("query"))
            "open_url" -> router.executeOpenUrl(args.optString("url"))
            "ride" -> router.executeRide(args.optString("destination"), args.optString("app"))
            "order_food" -> router.executeFood(args.optString("query"), args.optString("app"))
            "note" -> router.executeNote(args.optString("text"))
            "email" -> router.executeEmail(memory.resolveContact(args.optString("to")), args.optString("subject"), args.optString("body"))
            "flashlight" -> router.executeTorch(args.optString("state", "on") != "off")
            "battery" -> router.executeBatteryLevel()
            "wifi" -> router.executeWifiSettings()
            "bluetooth" -> router.executeBluetooth(args.optString("state", "on") != "off")
            "calendar_read" -> router.executeCalendarRead()
            "calendar_create" -> router.executeCalendarCreate(args.optString("title"))
            "voice_record_start" -> router.executeStartRecording()
            "voice_record_stop" -> router.executeStopRecording()
            "course_check" -> router.executeCourseCheck()
            else -> router.tryHandle(original) // unknown action → try the keyword router
        }
        return resultToReply(result, say) { Reply("I'm not sure how to do that yet.") }
    }

    /**
     * Runs a contact action. If the name matched no contact and the cloud brain is available,
     * asks it to fuzzy-match the name against the real contact list and retries once. That sends
     * contact names to the cloud, so it only happens in smart mode.
     */
    private suspend fun withContact(name: String, run: (String) -> CommandRouter.Result): CommandRouter.Result {
        val first = run(memory.resolveContact(name))
        if (first !is CommandRouter.Result.Handled || !first.reply.startsWith("I couldn't find a contact named")) return first
        val corrected = cloud()?.resolveContact(name, router.getAllContactNames()) ?: return first
        android.util.Log.i("Naomi", "Fuzzy contact: \"$name\" → \"$corrected\"")
        return run(corrected)
    }

    /**
     * A message action's text: the user's own words when they spelled it out ("text X that Y"),
     * since models paraphrase and embellish; else the model's, which sometimes files it under
     * "text" instead of "message".
     */
    private fun messageOf(args: JSONObject, original: String): String =
        CommandRouter.spokenMessage(original) ?: args.optString("message").ifBlank { args.optString("text") }

    private fun resultToReply(result: CommandRouter.Result, say: String = "", onNotHandled: () -> Reply): Reply =
        when (result) {
            is CommandRouter.Result.Handled -> { reset(); Reply(join(say, result.reply)) }
            is CommandRouter.Result.Ask -> {
                pending = result.onAnswer
                Reply(join(say, result.prompt), listenAgain = true)
            }
            CommandRouter.Result.NotHandled -> { reset(); onNotHandled() }
        }

    private fun join(first: String, second: String): String =
        listOf(first, second).filter { it.isNotBlank() }.joinToString(" ")

    private fun reset() { pending = null; pendingTries = 0; expectingFollowUp = false }

    /** The user went quiet instead of answering: drop the follow-up but keep the conversation. */
    fun endFollowUp() { reset(); followUpStreak = 0 }

    /** Full conversational reset — drop any pending follow-up AND the conversation history.
     *  Called when the user backs out / leaves, so the next turn starts fresh. The conversation
     *  that ends here is noted down first. */
    fun cancel() { reset(); followUpStreak = 0; history.clear(); noteConversation() }

    companion object {
        // A pause this long ends a conversation: it's noted down, and the next one starts fresh.
        private const val CONVERSATION_GAP_MS = 30 * 60_000L
        // The owner's exchanges kept for a conversation's note.
        private const val MAX_NOTED_TURNS = 30
        // Memories recalled into a turn, and for "what do you know about me?".
        private const val RECALLED_FACTS = 5
        private const val RECALLED_NOTES = 2
        private const val OVERVIEW_SIZE = 10
        private const val OFFLINE_RECALL = 3
        // Related facts shown when learning, so it updates them instead of repeating them.
        private const val KNOWN_FOR_LEARNING = 6
        // The last conversation, if this recent, is brought to mind when the next one opens.
        private const val RECENT_NOTE_MS = 36 * 3_600_000L
        // "Forget that" within this long of learning something means that.
        private const val JUST_LEARNED_MS = 10 * 60_000L

        private val LEADING_NAME = Regex("^(hey |ok |okay )?naomi[,.!]?\\s+", RegexOption.IGNORE_CASE)
        // "remember that…", "please don't forget…", "can you keep in mind…" — but not "remember to…"
        // (a reminder) or "remember when…" (a question).
        private val REMEMBER = Regex(
            "^(?:(?:can|could|will|would) you |please |i want you to |i need you to |make sure (?:you|to) )*" +
                "(?:remember|don'?t forget|keep in mind)(?: that)?\\s+" +
                "(?!(?:to|when|what|where|who|how|why|if|whether|me)\\b)(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val FORGET = Regex(
            "^(?:(?:can|could|will|would) you |please )*forget (?:about |that |what i (?:said|told you) about )?(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val RECALL = Regex("\\b(do you remember|what do you (know|remember)|what did i (tell|say to) you)\\b", RegexOption.IGNORE_CASE)
        private val FIRST_PERSON_START = Regex("^(i|i'm|i've|i'd|i'll|my|me)\\b", RegexOption.IGNORE_CASE)
        private val CONFIRMATIONS = listOf(
            "Got it — I'll remember that.",
            "Noted. It's safe with me.",
            "Consider it remembered.",
            "Locked in. I won't forget.",
        )
    }
}
