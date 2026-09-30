package com.naomi.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

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
    private val transit = TransitClient()
    private val search = SearchClient()
    private val pageReader = PageReader()
    val memory = MemoryStore(context.applicationContext)
    /** What she's learned about the owner, and notes on past conversations. */
    val memories = MemoryBank.get(context.applicationContext)
    val settings = BrainSettings(context.applicationContext)

    // Work that mustn't hold up a reply: learning from what was said, noting down a finished
    // conversation. Not tied to the screen, so a note started as it closes still gets written.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Alarms don't outlive an app update or a force stop: put the reminders back on the clock.
        background.launch { ReminderAlarms.rescheduleAll(appContext) }
    }

    /** Smart mode (UI toggle): the cloud brain may be used. When false, nothing leaves the phone. */
    @Volatile var smartMode = false

    /** Says a line while she works on a slow answer ("Let me check."), ahead of the reply. Set by the screen. */
    var onAside: ((String) -> Unit)? = null

    // A follow-up awaiting the user's next reply (e.g. choosing the messaging app).
    private var pending: ((String) -> CommandRouter.Result)? = null
    private var pendingTries = 0

    // Rolling conversation memory (user, naomi) so follow-ups and chat stay in context.
    private val history = ArrayDeque<Pair<String, String>>()
    // With the decision prompt's ~2,400 tokens, 20 exchanges come to ~4,400: sized for a brain
    // with an 8K context (Ollama's default is 4K — see the README).
    private val maxHistory = 20

    // The conversation so far as the owner had it — for its note when it ends — and whether any
    // of it was real talk rather than quick commands. [chatTurn] marks the turn in progress as talk.
    private val conversation = ArrayDeque<Pair<String, String>>()
    private var talked = false
    // The language of the last turn: a turn in another one starts a new conversation.
    private var lastLanguage: Language? = null
    private var chatTurn = false
    private var lastTurnAt = 0L

    // The place the last distance answer was about — "and on foot?" asks about it again.
    private var lastPlace: DistanceClient.Found? = null
    // An offer she just made ("Want me to open the route in Maps?"): a yes takes it, a no drops
    // it, and anything else drops it and is handled as a new request.
    private var offer: (() -> CommandRouter.Result)? = null
    // What the cloud brain was told this turn, for retelling a result in her voice from inside an
    // action (directions); null offline.
    private var turnCtx: TurnContext? = null
    // The recognizer's other guesses at this turn's words.
    private var heardAs: List<String> = emptyList()
    // Contact names, looked up now and then for the recognizer's vocabulary.
    private var contactNames: List<String> = emptyList()
    private var contactNamesAt = 0L
    // Set by a turn that was an explicit "remember that…"/"forget…": nothing more to learn from it.
    private var memoryTurn = false
    // This turn's "Let me check." has been said already (see [cloudTurn]); and it was answered by
    // looking it up, in her own voice already, so there's nothing to retell.
    private var lookingSaid = false
    private var lookedUp = false

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
    suspend fun handle(heard: String, speaker: Who = Who.UNKNOWN, alternatives: List<String> = emptyList()): Reply {
        // The recognizer's runner-up is sometimes the right one: the names it has that she knows
        // settle it ("closest work" / "closest Woolworths", with Woolworths just talked about).
        val userText = alternatives.firstOrNull { knownNamesIn(it) > knownNamesIn(heard) } ?: heard
        val heardAs = if (userText == heard) alternatives else listOf(heard) + (alternatives - userText)
        if (userText != heard) android.util.Log.i("Naomi", "Took the recognizer's other guess: \"$userText\" over \"$heard\"")
        val now = System.currentTimeMillis()
        // A long pause ends a conversation: it gets noted down, and the next one starts fresh. So
        // does a change of language: the talk so far would pull her replies back into the old one.
        val language = Language.current(appContext)
        if (history.isNotEmpty() && (now - lastTurnAt > CONVERSATION_GAP_MS || language != lastLanguage)) cancel()
        lastTurnAt = now
        lastLanguage = language
        memories.ownerName = memory.get("name")
        val herLastLine = history.lastOrNull()?.second
        chatTurn = false
        memoryTurn = false
        lookingSaid = false
        lookedUp = false
        turnCtx = null
        this.heardAs = heardAs

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
            if (chatTurn) talked = true
            // What they say about themselves counts even when it comes with a request ("I don't
            // have a car, so how far is it on foot?") — only not twice, after "remember that…".
            if (!memoryTurn) learnFrom(userText, herLastLine)
        }

        return reply.copy(listenAgain = question || reply.listenAgain)
    }

    /** A reply is treated as a question (→ reopen mic) when it ends with a question mark. */
    private fun isQuestion(text: String): Boolean = text.trim().endsWith("?")

    /** Actions whose report is worth hearing in her own voice. The rest hand the screen to another
     *  app (a call, the map, music) or ask a follow-up, and are left as the phone says them. */
    private val NARRATED = setOf("weather", "battery", "calendar_read", "distance", "nearest", "set_timer", "set_alarm", "flashlight")

    /** What a guest may ask for. Anything personal — calls, messages, calendar, notes, apps,
     *  rides, orders — stays the owner's. */
    private val GUEST_ACTIONS = setOf("weather", "battery", "set_timer", "flashlight", "play_music", "music_control",
        "distance", "nearest", "directions", "maps_search", "web_search", "look_up")

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

        val lower = userText.lowercase()

        offer?.let { take ->
            offer = null
            if (NO_TO_OFFER.containsMatchIn(lower)) return Reply("No problem.")
            if (YES_TO_OFFER.containsMatchIn(lower)) return resultToReply(take()) { Reply("I couldn't open that.") }
        }

        // 0. DAILY BRIEFING: greeting + date + weather + today's calendar, in one go (owner only).
        if (!guest && Regex("\\b(good morning|morning briefing|brief me|briefing|start my day|how'?s my day|what'?s my day|the rundown|catch me up)\\b")
                .containsMatchIn(lower)) {
            return Reply(dailyBriefing())
        }

        // 1. SMART MODE: the cloud brain understands the whole turn — chat or action. "Remember
        // that…" and "forget…" go to it too: out of context, "remember the Woolworths we talked
        // about? how do I get there" reads like a thing to keep.
        cloud()?.let { cloud ->
            cloudTurn(cloud, userText, speaker)?.let { return it }
            // The cloud couldn't be reached: handle it offline below.
        }

        // Offline, memory requests are recognized by their wording.
        if (!guest) memoryCommand(userText)?.let { return it }

        // 2a. WEATHER: needs a live network lookup (async), so it can't live in the sync router.
        if (Regex("\\b(weather|temperature|forecast)\\b").containsMatchIn(lower) || WEATHER_PT.containsMatchIn(lower)) {
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

        // She said she'd look it up, but the brain that reads the results is out of reach: the search itself still works.
        if (lookingSaid) {
            val query = searchQuery(userText)
            offer = { router.executeWebSearch(query) }
            return Reply(if (Language.current(appContext) == Language.PORTUGUESE)
                "Não consegui falar com o meu cérebro agora. Quer que eu abra a pesquisa no celular?"
                else "I can't reach my brain right now. Want me to open the search on your phone?", listenAgain = true)
        }
        return Reply(
            if (smartMode && settings.isConfigured()) "I can't reach my cloud brain right now, and that one's beyond me offline."
            else "Sorry, I didn't catch that — I can do timers, alarms, calls, music, messages, calendar, and answer questions."
        )
    }

    /** One turn through the cloud brain, or null if it couldn't be reached (caller falls back). */
    private suspend fun cloudTurn(cloud: CloudBrain, userText: String, speaker: Who): Reply? {
        // A question only a search can answer ("who won last night?"): she says she's checking now,
        // rather than after the brain has decided to, and a small brain skips its chat pass —
        // what she'll say is the answer from the search.
        val live = looksLive(userText)
        if (live) {
            onAside?.invoke(lookingLine(Language.current(appContext)))
            lookingSaid = true
        }
        val ctx = TurnContext(
            // A guest hears nothing of the owner's life — only their name, so she never calls the guest by it.
            facts = if (speaker == Who.GUEST) memory.all().filterKeys { it == "name" } else memory.all(),
            // The neighbourhood (never coordinates) lets her place local names and questions.
            where = DeviceLocation.current(appContext)?.name,
            speaker = speaker,
            memories = recall(userText, speaker),
            heardAs = heardAs,
            language = Language.current(appContext),
        )
        turnCtx = ctx
        val response = try {
            cloud.respond(userText, history.toList(), ctx, chat = !live)
        } catch (e: LlmException) {
            android.util.Log.e("Naomi", "Cloud brain failed: ${e.message}")
            return null
        }
        val action = response.action ?: return missedReminder(userText, speaker)
            ?: missedAction(userText, speaker)?.let { narrated(cloud, userText, it, ctx) } ?: run {
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
        // A looked-up answer is in her voice already: retelling it would be one more slow call to the brain.
        if (reply.listenAgain || lookedUp) return reply
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
            if (said.split(Regex("\\s+")).size < 2) return null
            memoryTurn = true
            return Reply(rememberFact(said))
        }
        FORGET.find(text)?.let { m -> return forgetReply(m.groupValues[1], fromModel = false)?.also { memoryTurn = true } }
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
     * A reminder asked for, looked at or cancelled that the brain answered in words ("Sure, I'll
     * remind you!") — which sets nothing. Done for real, and said as the phone words it: a
     * retelling could turn tomorrow into today.
     */
    private fun missedReminder(userText: String, speaker: Who): Reply? {
        if (speaker == Who.GUEST) return null
        if (!ReminderParser.isRequest(userText) && !ReminderParser.isList(userText) && !ReminderParser.isCancel(userText)) return null
        val routed = router.tryHandle(userText)
        if (routed is CommandRouter.Result.NotHandled) return null
        return resultToReply(routed) { Reply("I couldn't do that.") }
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
        // "Will it rain tomorrow?" — and "vai chover amanhã", which in Portuguese asks it with no
        // question word, and often no question mark from the recognizer either.
        if ((asking && Regex("\\b(weather|forecast|temperature|rain|raining|rainy|umbrella|sunny|snow)\\b").containsMatchIn(lower)) ||
            WEATHER_PT.containsMatchIn(lower)) {
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
                return Reply(distanceReport(m.groupValues[1].replace(Regex("[?.!]+$"), "").trim(), travelFor("", userText)))
            }
        }
        // "How do I get there by bus?" answered with words: work out the way.
        if (Regex("\\bhow (do|can|could|would|should) (i|we) get (to|there)\\b|\\b(which|what) (bus|train|ferry)\\b").containsMatchIn(lower)) {
            val to = Regex("\\bget to\\s+(.+?)(?:\\s+(?:by|on|via)\\s+.+)?$").find(lower)?.groupValues?.get(1)?.trim('?', '.', '!', ' ')
            return directionsReply(to.orEmpty(), saidTravel("", userText), userText)
        }
        // "Where's the closest Woolworths?" answered with words: look it up.
        if (asksNearest(lower)) {
            Regex("\\b(?:closest|nearest)\\s+(.+)$").find(lower)?.let { m ->
                return Reply(nearestReport(m.groupValues[1].replace(Regex("[?.!]+$"), "").trim(), travelFor("", userText)))
            }
        }
        if (!guest && Regex("\\b(text|message|whatsapp|sms|dm)\\b.+\\b(that|saying|to say)\\b").containsMatchIn(lower)) {
            val routed = router.tryHandle(userText)
            if (routed !is CommandRouter.Result.NotHandled) return resultToReply(routed) { Reply("I couldn't do that.") }
        }
        // "Who won last night?", "google the cricket score": looked up, never answered from a model's memory.
        if (looksLive(userText)) return lookUpReply(searchQuery(userText), userText)
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

    private fun asksNearest(text: String): Boolean {
        val lower = text.lowercase()
        return Regex("\\b(closest|nearest)\\b").containsMatchIn(lower) &&
            !Regex("\\b(map|maps|show|navigate|directions|take me)\\b").containsMatchIn(lower)
    }

    private fun asksDistance(lower: String): Boolean =
        Regex("\\b(how far|distance (to|from)|how long (does it take |would it take |will it take )?to (get|drive))\\b").containsMatchIn(lower)

    /**
     * How far the phone is from [destination], by road when possible. A name the speech
     * recognizer mangled ("Wooster") gets one retry through the brain, which knows the area.
     */
    private suspend fun distanceReport(destination: String, travel: DistanceClient.Travel? = null): String {
        // "How far is it on foot?" — the place just talked about, not a new search.
        lastPlace?.takeIf { refersTo(destination, it) }?.let { place ->
            val here = DeviceLocation.current(appContext)
                ?: return "I can't tell where you are right now. Is location turned on?"
            return distance.describe(here, place, travel)
        }
        if (destination.isBlank()) return "Where to?"
        // "The closest bus stop" is a kind of place to look for, not a place's name.
        nearestKind(destination)?.let { return nearestReport(it, travel) }
        val here = DeviceLocation.current(appContext)
            ?: return "I can't tell where you are right now. Is location turned on?"
        val target = cleanDestination(destination, here.name)
        val found = distance.locate(target, here)
            ?: cloud()?.resolvePlace(target, here.name)?.let { distance.locate(it, here) }
            ?: return "I couldn't find anywhere called $target."
        lastPlace = found
        return distance.describe(here, found, travel)
    }

    /**
     * "How do I get to X (by bus)?" — the way there in words: the bus or train to catch and when,
     * by public transport; how far and how long on foot or by car. Then she offers to open the
     * route in Maps, and waits for the answer.
     */
    private suspend fun directionsReply(destination: String, said: DistanceClient.Travel?, asked: String): Reply {
        val here = DeviceLocation.current(appContext)
            ?: return Reply("I can't tell where you are right now. Is location turned on?")
        val place = lastPlace?.takeIf { refersTo(destination, it) }
            ?: nearestKind(destination)?.let { distance.nearest(DistanceClient.kindOf(it), here) }
            ?: destination.takeIf { it.isNotBlank() }?.let { distance.locate(cleanDestination(it, here.name), here) }
            ?: return Reply(if (destination.isBlank()) "Where to?" else "I couldn't find anywhere called $destination.")
        lastPlace = place
        val km = FloatArray(1).also { android.location.Location.distanceBetween(here.lat, here.lon, place.lat, place.lon, it) }[0] / 1000.0
        // Unsaid: walk if it's close; if not, the bus for someone without a car, else the car.
        val travel = said ?: when {
            km < 1.5 -> DistanceClient.Travel.WALK
            noCar() -> DistanceClient.Travel.TRANSIT
            else -> DistanceClient.Travel.DRIVE
        }
        val trip = if (travel == DistanceClient.Travel.TRANSIT) transit.plan(here, place.lat, place.lon) else null
        val way = when {
            trip != null -> TransitClient.spoken(trip, place.name)
            travel == DistanceClient.Travel.TRANSIT ->
                "I couldn't find a bus or train there right now. " + distance.describe(here, place, DistanceClient.Travel.WALK)
            else -> distance.describe(here, place, travel)
        }
        // Her voice around the way there, which is read out as worked out: a retelling could turn
        // the bus's departure into the arrival time. On foot or by car, a plain retelling will do.
        val cloud = turnCtx?.let { ctx -> cloud()?.let { it to ctx } }
        val told = if (trip != null) {
            val (before, after) = cloud?.let { (brain, ctx) -> brain.frame(asked, way, history.toList(), ctx) } ?: (null to null)
            listOfNotNull(before ?: OPENERS.random(), way, after).joinToString(" ")
        } else {
            cloud?.let { (brain, ctx) -> brain.narrate(asked, way, history.toList(), ctx) } ?: way
        }
        offer = { router.executeDirections(place.lat, place.lon, mapsMode(travel)) }
        return Reply("$told Want me to open the route in Maps?", listenAgain = true)
    }

    private fun mapsMode(travel: DistanceClient.Travel) = when (travel) {
        DistanceClient.Travel.TRANSIT -> "transit"
        DistanceClient.Travel.WALK -> "walking"
        DistanceClient.Travel.DRIVE -> "driving"
    }

    /**
     * [query] looked up on the web and [asked] answered from what came back, in her voice.
     * Results that don't answer it, or no results at all, end with the offer to open the search
     * on the phone.
     */
    private suspend fun lookUpReply(query: String, asked: String): Reply {
        val language = turnCtx?.language ?: Language.current(appContext)
        val pt = language == Language.PORTUGUESE
        if (!lookingSaid) onAside?.invoke(lookingLine(language))
        lookingSaid = true
        lookedUp = true
        val speech = Language.speechTag(appContext)
        val ctx = turnCtx
        val cloud = cloud()
        val lookUp = LookUp(
            search = { q ->
                search.search(q, settings.searchServer, speech)
                    ?.also { android.util.Log.i("Naomi", "Looked up \"$q\" on ${it.source}: ${it.results.size} results") }
            },
            read = { results, question -> pageReader.read(results, question, speech) },
            answer = { results, pages, creative ->
                if (ctx == null || cloud == null) CloudBrain.Attempt(null, CloudBrain.Verdict.UNREACHABLE)
                else cloud.tryAnswer(asked, query, results, history.toList(), ctx, pages, creative)
            },
        )
        val openSearch = { router.executeWebSearch(query) }
        return when (val outcome = lookUp.run(query, asked)) {
            is LookUp.Outcome.Answered -> Reply(outcome.say, listenAgain = settings.conversationMode && !isGoodbye(asked))
            LookUp.Outcome.NoResults -> {
                offer = openSearch
                Reply(if (pt) "Não consegui resultados da busca agora. Quer que eu abra a pesquisa no celular?"
                    else "I couldn't get any search results just now. Want me to open the search on your phone?", listenAgain = true)
            }
            is LookUp.Outcome.NotAnswered -> {
                // No answer in the results or the pages, or none she could word without making things
                // up: the top snippet is as likely a page's footnotes as the answer, so it isn't read
                // out instead. Her own line on it stays, unless it asks something itself.
                offer = openSearch
                val said = outcome.say?.takeUnless { it.trimEnd().endsWith("?") }
                    ?: if (pt) "Achei algumas páginas, mas nada que respondesse com certeza."
                    else "I found a few pages, but couldn't pin down a clear answer."
                Reply("$said ${if (pt) "Quer que eu abra a pesquisa?" else "Want me to open the search?"}", listenAgain = true)
            }
        }
    }

    /**
     * Words the recognizer should listen out for: her name, the owner's, people and places in her
     * memory and facts, the place just talked about, contacts. Names are what it gets wrong most.
     */
    fun vocabulary(): List<String> {
        val words = linkedSetOf("Naomi")
        words += knownNames()
        val now = System.currentTimeMillis()
        if (now - contactNamesAt > CONTACTS_TTL_MS) {
            contactNames = runCatching { router.getAllContactNames() }.getOrDefault(emptyList()).take(MAX_CONTACT_WORDS)
            contactNamesAt = now
        }
        words += contactNames
        return words.filter { it.isNotBlank() }.take(MAX_VOCABULARY)
    }

    /**
     * Names she knows right now: the owner's, people and places in her facts and memories, the
     * place just talked about, and names in the last few lines of the conversation.
     */
    private fun knownNames(): Set<String> {
        val names = linkedSetOf<String>()
        fun capitalised(text: String) = Regex("\\b[A-Z][a-zA-Z']{2,}").findAll(text).map { it.value.removeSuffix("'s") }
            .filterNot { it.lowercase() in NOT_NAMES }
        memory.all().forEach { (key, value) -> if (key == "name" || value.firstOrNull()?.isUpperCase() == true) names += value }
        lastPlace?.let { names += capitalised(it.name) }
        memories.all().forEach { names += capitalised(it.text) }
        history.takeLast(3).forEach { (user, naomi) -> names += capitalised(user); names += capitalised(naomi) }
        return names
    }

    /** How many of the names she knows [text] mentions. */
    private fun knownNamesIn(text: String): Int {
        val lower = text.lowercase()
        return knownNames().count { Regex("\\b${Regex.escape(it.lowercase())}\\b").containsMatchIn(lower) }
    }

    /** Whether [said] means [place]: "it", "there" — or mostly the words of its name. */
    private fun refersTo(said: String, place: DistanceClient.Found): Boolean {
        val t = said.lowercase().trim().trimEnd('?', '.', '!')
        if (t.isEmpty() || t in PLACE_PRONOUNS) return true
        val name = memories.terms(place.name)
        val words = memories.terms(t)
        return name.isNotEmpty() && name.count { n -> words.any { MemoryBank.similar(n, it) } } * 2 >= name.size
    }

    /** A place name without the trip around it: "Woolworths walking distance from Frenchs Forest" → "Woolworths". */
    private fun cleanDestination(said: String, here: String?): String {
        var t = said.replace(Regex("\\b(walking|driving) (distance|time)\\b|\\b(on|by) (foot|walking|car)\\b|" +
            "\\bfrom (here|my (place|home|house))\\b", RegexOption.IGNORE_CASE), " ")
        if (!here.isNullOrBlank()) t = t.replace(Regex("\\bfrom\\s+${Regex.escape(here)}\\b", RegexOption.IGNORE_CASE), " ")
        return t.replace(Regex("\\s+"), " ").trim().ifBlank { said.trim() }
    }

    /**
     * How they mean to get there: as the model took it, else as they said it ("on foot", "I
     * don't have a car", "by car"), else on foot if they've told her they don't drive — else
     * null, left to the distance.
     */
    private fun travelFor(mode: String, said: String): DistanceClient.Travel? =
        saidTravel(mode, said) ?: if (noCar()) DistanceClient.Travel.WALK else null

    /** How they asked to get there — by the model's reading, else their words — or null if they didn't say. */
    private fun saidTravel(mode: String, said: String): DistanceClient.Travel? {
        val m = mode.lowercase().trim()
        val s = said.lowercase()
        return when {
            m.startsWith("walk") || m == "foot" -> DistanceClient.Travel.WALK
            m.startsWith("driv") || m == "car" -> DistanceClient.Travel.DRIVE
            m in setOf("transit", "bus", "train", "public", "public transport") -> DistanceClient.Travel.TRANSIT
            TRANSIT_WORDS.containsMatchIn(s) -> DistanceClient.Travel.TRANSIT
            Regex("\\b(walk|walking|on foot|by foot)\\b").containsMatchIn(s) || NO_CAR.containsMatchIn(s) -> DistanceClient.Travel.WALK
            Regex("\\b(drive|driving|by car)\\b").containsMatchIn(s) -> DistanceClient.Travel.DRIVE
            else -> null
        }
    }

    /** Whether they've told her they don't drive — kept as a memory. */
    private fun noCar(): Boolean = memories.all().any { it.kind == MemoryBank.Kind.FACT && NO_CAR.containsMatchIn(it.text) }

    /**
     * Where the nearest [what] is and how far — a kind of place ("bus stop") or a chain
     * ("Woolworths") — found around the phone in OpenStreetMap.
     */
    private suspend fun nearestReport(what: String, travel: DistanceClient.Travel? = null): String {
        if (DistanceClient.kindOf(what).isBlank()) return "The nearest what?"
        val here = DeviceLocation.current(appContext)
            ?: return "I can't tell where you are right now. Is location turned on?"
        // The search is already around the phone: "Woolworths Frenchs Forest", "a cafe near Manly"
        // would only narrow it to places named that way.
        val kind = DistanceClient.kindOf(what)
            .replace(Regex("\\s+(?:in|near|around|at|close to)\\s+.+$", RegexOption.IGNORE_CASE), "")
            .let { k -> here.name?.takeIf { it.isNotBlank() }?.let { k.replace(it, "", ignoreCase = true) } ?: k }
            .trim().ifBlank { DistanceClient.kindOf(what) }
        val found = distance.nearest(kind, here) ?: return "I couldn't find a $kind anywhere near you."
        lastPlace = found
        return distance.describeNearest(here, found, kind, travel)
    }

    /** The kind of place in "the closest bus stop" / "a pharmacy near me", or null for a named place. */
    private fun nearestKind(text: String): String? {
        val t = text.trim().trimEnd('?', '.', '!')
        return Regex("^(?:the\\s+|a\\s+|an\\s+)?(?:closest|nearest)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
            ?: Regex("^(?:the\\s+|a\\s+|an\\s+)?(.+?)\\s+(?:near me|nearby|around here|close by)$", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
    }

    /**
     * Weather for [city], [day] days ahead. With no city named: where the phone is (GPS), else
     * the saved home city, else she asks which city.
     */
    private suspend fun weatherReport(city: String, day: Int): String {
        val pt = Language.current(appContext) == Language.PORTUGUESE
        if (city.isNotBlank()) return weather.forecast(city, day, pt = pt)
        val here = DeviceLocation.current(appContext)
        return weather.forecast(if (here == null) homeCity().orEmpty() else "", day, here, pt)
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
        val travel = travelFor(args.optString("mode"), original)
        // A bus stop or station is where transit starts, not how to reach it.
        val saidTravel = saidTravel(args.optString("mode"), original).takeUnless {
            it == DistanceClient.Travel.TRANSIT && type == "nearest" &&
                Regex("\\b(stop|station|wharf|terminal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(args.optString("what"))
        }
        // The way there — explained, then offered on the map. "How far by bus" is the way there too.
        if (type == "directions" || (type in setOf("distance", "nearest") && saidTravel == DistanceClient.Travel.TRANSIT)) {
            val where = args.optString("destination").ifBlank { args.optString("what") }
                .let { if (type == "nearest" && nearestKind(it) == null) "the nearest $it" else it }
            return directionsReply(where, saidTravel, original)
        }
        // "Take me there": the place just talked about, if that's what "there" is.
        if (type == "navigate") {
            lastPlace?.takeIf { refersTo(args.optString("destination"), it) }?.let { place ->
                return resultToReply(router.executeDirections(place.lat, place.lon, mapsMode(travel ?: DistanceClient.Travel.DRIVE))) {
                    Reply("I couldn't open Maps.")
                }
            }
        }
        if (type == "nearest") return Reply(join(say, nearestReport(args.optString("what").ifBlank { args.optString("query") }, travel)))
        // "How far is X" is a question, not a trip — even when a model reaches for the map.
        if (type == "distance" || (type in setOf("navigate", "maps_search") && asksDistance(original.lowercase()))) {
            return Reply(join(say, distanceReport(args.optString("destination").ifBlank { args.optString("query") }, travel)))
        }
        // "Where's the closest Woolworths?" wants an answer, not the map — unless the map was asked for.
        if (type == "maps_search" && asksNearest(original)) {
            return Reply(join(say, nearestReport(nearestKind(args.optString("query")) ?: args.optString("query"), travel)))
        }
        // Looked up and answered, not just opened — unless they asked to see the search page.
        // Offline, with nothing to word an answer, the page it is.
        if (type == "look_up" || (type == "web_search" && !ASKS_TO_OPEN.containsMatchIn(original))) {
            val query = args.optString("query").ifBlank { searchQuery(original) }
            if (cloud() != null) return lookUpReply(query, original)
            return resultToReply(router.executeWebSearch(query)) { Reply("I couldn't open the search.") }
        }
        // "Remind me to…" filed as something to remember, or as a calendar entry, is still a reminder.
        if ((type == "remember" || type == "calendar_create") && ReminderParser.isRequest(original)) {
            return resultToReply(router.reminders.set(original, whenSaid = args.optString("when"))) {
                Reply("I couldn't set that reminder.")
            }
        }
        if (type == "remember") {
            memoryTurn = true
            return Reply(rememberFact(args.optString("fact").ifBlank { REMEMBER.find(original)?.groupValues?.get(1) ?: original }))
        }
        if (type == "forget") {
            memoryTurn = true
            return forgetReply(args.optString("what"), fromModel = true) ?: Reply("Okay.")
        }
        if (type == "weather") {
            // A day the user actually said wins over the model's reading of it. A day the model
            // garbled ("amãhem") in a follow-up ("but that was a question") is the one they asked about.
            val modelDay = args.optString("day").trim()
            val day = WeatherClient.dayNamed(original)
                ?: WeatherClient.dayNamed(modelDay)
                ?: modelDay.takeIf { it.isNotEmpty() }?.let { history.lastOrNull()?.first?.let(WeatherClient::dayNamed) }
                ?: 0
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
            "ride" -> router.executeRide(args.optString("destination"), args.optString("app"),
                Language.current(appContext) == Language.PORTUGUESE)
            "order_food" -> router.executeFood(args.optString("query"), args.optString("app"),
                Language.current(appContext) == Language.PORTUGUESE)
            "note" -> router.executeNote(args.optString("text"))
            "email" -> router.executeEmail(memory.resolveContact(args.optString("to")), args.optString("subject"), args.optString("body"))
            "flashlight" -> router.executeTorch(args.optString("state", "on") != "off")
            "battery" -> router.executeBatteryLevel()
            "wifi" -> router.executeWifiSettings()
            "bluetooth" -> router.executeBluetooth(args.optString("state", "on") != "off")
            "calendar_read" -> router.executeCalendarRead()
            // A time the user said wins over the model's working out of it, as with timers.
            "calendar_create" -> router.executeCalendarCreate(args.optString("title"),
                ReminderParser.parseWhen(original, LocalDateTime.now())?.at ?: ReminderParser.modelTime(args.optString("when"), LocalDateTime.now()),
                ReminderParser.isPortuguese(original))
            "reminder" -> router.reminders.set(original, args.optString("text"), args.optString("when"), args.optString("repeat"))
            "reminders_list" -> router.reminders.list(original)
            "reminder_cancel" -> router.reminders.cancel(original, args.optString("what"))
            "voice_record_start" -> router.executeStartRecording()
            "voice_record_stop" -> router.executeStopRecording()
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
    fun cancel() { reset(); followUpStreak = 0; history.clear(); lastPlace = null; offer = null; noteConversation() }

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

        private val LEADING_NAME = Regex("^(hey |ok |okay |oi |ei |olá |ola )?naomi[,.!]?\\s+", RegexOption.IGNORE_CASE)
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
        // Going by public transport: "by bus", "take the train", "which bus" — not "the bus stop".
        private val TRANSIT_WORDS = Regex("\\b(by|on|via|take|taking|catch|catching|get|getting)\\s+(a\\s+|the\\s+)?" +
            "(bus|buses|train|trains|ferry|tram|light rail|metro)\\b|\\bpublic transport\\b|\\b(which|what)\\s+(bus|train|ferry)\\b",
            RegexOption.IGNORE_CASE)
        // "I don't have a car", "Ozzy doesn't drive", "no car" — getting around on foot.
        private val NO_CAR = Regex("\\b(don'?t|doesn'?t|do not|does not|no longer) (have|own) a car\\b|\\bno car\\b|" +
            "\\bwithout a car\\b|\\b(can'?t|cannot|don'?t|doesn'?t|do not|does not) drive\\b", RegexOption.IGNORE_CASE)
        // The recognizer's word list: contact names looked up at most this often, and how many words in all.
        private const val CONTACTS_TTL_MS = 10 * 60_000L
        private const val MAX_CONTACT_WORDS = 60
        private const val MAX_VOCABULARY = 100
        private val NOT_NAMES = setOf("the", "this", "that", "they", "their", "she", "her", "his", "him", "it", "on", "in",
            "at", "and", "but", "when", "yesterday", "today", "earlier", "tomorrow", "note", "naomi", "monday", "tuesday",
            "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december")
        // What she says as she starts looking something up, so the wait isn't silent.
        private val LOOKING = listOf("Let me check.", "One sec, I'll look it up.", "Hang on, let me look.", "Checking now.")
        // Weather asked in Portuguese, question or not: "vai chover amanhã", "tá frio hoje?", "como tá o
        // tempo", "previsão do tempo". Rain or cold with nothing about now or later ("adoro dias de
        // chuva") isn't a question about the weather.
        internal val WEATHER_PT = wordRegex("\\b(previs[ãa]o do tempo|como (est[áa]|t[áa]|vai estar|vai ficar) o tempo|" +
            "(vai|vão|será|sera|tá|ta|está|esta|deve) (chover|chovendo|garoar|nevar|fazer (frio|calor|sol)|esfriar|esquentar|" +
            "estar (frio|quente|chovendo|nublado))|temperatura (hoje|amanh[ãa]|agora|l[áa] fora)|" +
            "(chove|chuva|frio|calor) (hoje|amanh[ãa]|agora|essa semana|nesse fim de semana))\\b")
        private val LOOKING_PT = listOf("Deixa eu ver.", "Um segundo, vou pesquisar.", "Peraí, vou dar uma olhada.", "Vou pesquisar.")

        private fun lookingLine(language: Language) = (if (language == Language.PORTUGUESE) LOOKING_PT else LOOKING).random()

        /**
         * Whether [said] is a question only something current can answer ("who won last night?",
         * "quanto tá o dólar?") or an outright request to search ("google the cricket score",
         * "pesquisa o horário do jogo") — looked up, never answered from a model's memory.
         */
        internal fun looksLive(said: String): Boolean {
            val bare = said.trim().replace(LEADING_NAME, "").trim()
            if (SEARCH_REQUEST.containsMatchIn(bare) || SEARCH_REQUEST_PT.containsMatchIn(bare)) return true
            // An exclamation isn't a question: "que notícia boa!".
            if (bare.endsWith("!")) return false
            if (QUESTION_START.containsMatchIn(bare) && LIVE_QUESTION.containsMatchIn(bare)) return true
            // A reminder, an alarm or a message that mentions the game is still a reminder, alarm or message.
            if (COMMAND_PT.containsMatchIn(bare)) return false
            // In Portuguese a question often starts like a statement ("o mercado abre hoje?"): then only the
            // question mark, when the recognizer adds one, says it's a question.
            val asked = bare.endsWith("?") || QUESTION_START_PT.containsMatchIn(bare)
            return (asked && LIVE_QUESTION_PT.containsMatchIn(bare)) || LIVE_ALONE_PT.containsMatchIn(bare)
        }

        /**
         * What to search for when all there is to go on is what they said: "google the cricket
         * score" → "the cricket score", "pesquisa o preço do dólar" → "o preço do dólar"; the
         * question itself otherwise.
         */
        internal fun searchQuery(said: String): String {
            val bare = said.trim().replace(LEADING_NAME, "").trim()
            return ((SEARCH_REQUEST.find(bare) ?: SEARCH_REQUEST_PT.find(bare))?.groupValues?.get(1) ?: bare).trim('?', '.', '!', ' ')
        }
        // "Search for…", "google…", "look up…": the words after are the search.
        private val SEARCH_REQUEST = Regex("^(?:(?:can|could|would|will) you |please )*(?:search(?: the web| online| google)?" +
            "(?: for)?|google|look up|find out)\\s+(.+)$", RegexOption.IGNORE_CASE)
        // Asking to see the search, not to be told: "open google and search…", "show me results for…".
        private val ASKS_TO_OPEN = Regex("\\b(open|show me|pull up|bring up|browser|chrome)\\b", RegexOption.IGNORE_CASE)
        // A question, by how it starts — the recognizer doesn't always add the question mark.
        private val QUESTION_START = Regex("^(who|what|what's|whats|when|where|which|how|is|are|was|were|did|does|do|will|can you tell|tell me)\\b",
            RegexOption.IGNORE_CASE)
        // Questions only something current can answer.
        private val LIVE_QUESTION = Regex("\\b(who won|who'?s winning|score|scores|latest|news|headlines|price of|stock price|" +
            "exchange rate|opening hours|open (now|today|tonight|tomorrow|until)|release date|(come|comes|coming) out)\\b",
            RegexOption.IGNORE_CASE)
        // The same in Brazilian Portuguese: a question, by how it starts — not by "que", "tem" or "tá" alone,
        // which as often start a remark ("que notícia boa", "tá bom")…
        private val QUESTION_START_PT = wordRegex("^(quem|qual|quais|quando|onde|quanto|quanta|quantos|quantas|o que|oque|" +
            "cadê|cade|será que|sera que|me (diz|diga|fala|fale|conta|conte)|(você|voce|vc) sabe|sabe me dizer|" +
            "sabe (quem|quanto|qual|quais|quando|onde|se|que horas|o que)|(at[ée] )?que (horas|dia)|como (tá|ta|está|esta|foi|ficou)|" +
            "j[áa] (saiu|come[çc]ou|abriu|acabou|terminou)|(t[áa]|est[áa]) (aberto|aberta|abertos|abertas|fechado|fechada|quanto)|" +
            "e (o|a|os|as) \\S+( \\S+)? hoje)\\b")
        // …about something current: results, prices, opening hours, release dates.
        private val LIVE_QUESTION_PT = wordRegex("\\b(quem (ganhou|venceu|marcou)|quem (tá|ta|está|esta) (ganhando|vencendo)|placar|" +
            "resultado d[oa]s? (jogo|partida|cl[áa]ssico|elei[çc][ãa]o|elei[çc][õo]es|mega|loteria|lotof[áa]cil|quina|sorteio|final|" +
            "corrida|luta|(?-i:\\p{Lu})\\p{L}*)|(ganhou|venceu|perdeu|empatou)( \\S+){0,3} (ontem|hoje|anteontem|domingo|s[áa]bado)|" +
            "(como|quanto) (que )?(foi|ficou|terminou|tá|ta|está|esta) o (jogo|partida|cl[áa]ssico)|" +
            "(que horas|quando|que dia) ((é|e|come[çc]a|vai ser) o (jogo|partida|cl[áa]ssico)|joga)|pr[óo]ximo jogo|joga (hoje|amanhã|amanha)|" +
            "classifica[çc][ãa]o|tabela d[oa] (brasileir\\p{L}*|campeonato|s[ée]rie [a-d]|copa|libertadores|paulist[ãa]o|carioca|" +
            "premier|liga|champions|(?-i:\\p{Lu})\\p{L}*)|pre[çc]o d[aeo]s?|quanto (custa|custam|vale|valem)|" +
            // "Quanto tá o dólar", not "quanto tá sua bateria" or "quanto tá faltando pro timer".
            "quanto (que )?(tá|ta|está|esta) (?!(\\S+ ){0,2}(bateria|volume|brilho|armazenamento|mem[óo]ria|sinal|internet|temperatura|" +
            "tempo|clima|timer|cron[ôo]metro|alarme|faltando|n[íi]vel))(?!(o |a |os |as )?(meu|minha|seu|sua)\\b)\\p{L}+|" +
            "cota[çc][ãa]o|c[âa]mbio (d[oa] (d[óo]lar|euro|peso|libra|iene|bitcoin)|hoje|agora)|d[óo]lar hoje|" +
            "hor[áa]rio de funcionamento|que horas( \\S+){0,4} (abre|fecha|funciona)|" +
            "(abre|fecha|aberto|aberta|funciona)( \\S+){0,4} (hoje|agora|amanhã|amanha|domingo|sábado|sabado|no feriado)|" +
            "data de (lançamento|lancamento|estreia)|" +
            // "Quando sai o filme", not "quando sai meu salário" or "quando começa a reunião".
            "quando (sai|lan[çc]a|estreia|come[çc]a)(?! (a |o |as |os )?(minha|meu|minhas|meus|nossa|nosso|seu|sua|reuni[ãa]o|consulta|" +
            "aula|curso|plant[ãa]o|f[ée]rias|sal[áa]rio|pagamento|voo|[ôo]nibus|compromisso|prova|trabalho|expediente|turno)\\b))\\b")
        // Live questions however they start: the news asked for (not just mentioned, as in "posso te contar
        // uma notícia?"), a game today, a price or an hour asked back to front.
        private val LIVE_ALONE_PT = wordRegex("\\b((quais|qual) (são |foram )?(as )?([úu]ltimas )?not[íi]cias|" +
            "(tem|teve|há|ha|saiu) (alguma |algo de |muita |mais )?not[íi]cias?|" +
            "me (fala|conta|diz|d[áa]|passa) (as |alguma |umas )?([úu]ltimas )?not[íi]cias|" +
            "o que (tem|teve|saiu|aconteceu)( \\S+){0,3} (not[íi]cias?|hoje|no mundo)|[úu]ltimas not[íi]cias|manchetes|" +
            "not[íi]cias (de|do|da|dos|das|sobre) |(voc[êe]|vc) (sabe|viu) (as |alguma |das )?([úu]ltimas )?not[íi]cias|" +
            "(tem|ter) jogo( \\S+){0,3} (hoje|amanhã|amanha|agora)|hoje tem jogo|" +
            "(d[óo]lar|euro|gasolina|bitcoin|a[çc][ãa]o|pre[çc]o)( \\S+){0,2} (tá|ta|está|esta) quanto|" +
            "(abre|fecha|funciona) (at[ée] )?que horas)\\b")
        // Things to do, which may mention the game or the news: a reminder, an alarm, a message, a call, a ride.
        private val COMMAND_PT = wordRegex("\\b(me lembr\\p{L}*|lembrete|alarme|despertador|timer|cron[ôo]metro|me acord\\p{L}*|" +
            "manda|mande|envia|envie|fala pr[oa]|diz pr[oa]|pergunta pr[oa]|(liga|ligar|ligue) (pr[oa]|para)|cria|crie|coloca|coloque|" +
            "anota|anote|toca|toque|(chama|chame|pede|peça|peca) (um|uma))\\b")
        // "Pesquisa…", "busca na internet…", "dá um google no…": the words after are the search. A bare
        // "busca" is as often "pick up" ("buscar minha mãe no aeroporto"), so it needs somewhere to search.
        private val SEARCH_REQUEST_PT = wordRegex("^(?:(?:(?:você|voce|vc) )?(?:pode|consegue|poderia|podia) |por favor )*" +
            "(?:pesquis(?:a|e|ar)|(?:busc|procur)(?:a|e|ar) (?:na internet|no google|na web|online|sobre)|googl(?:a|e|ar)|" +
            "d[áa] um google(?: n[oa]| sobre)?|joga no google)(?: (?:pra mim|para mim|na internet|no google|na web|online|sobre|por))*" +
            "\\s+(.+)$")
        // A little life for directions told as they come, when her own retelling isn't to be had.
        private val OPENERS = listOf("Right, bus it is.", "Easy one.", "Here's the plan.", "Got you covered.")
        // Taking or declining an offer she just made, in English or Brazilian Portuguese.
        private val YES_TO_OFFER = Regex("^(oh |well |ok |okay )?(yes|yeah|yep|yup|sure|please|ok|okay|go ahead|do it|open it|why not|" +
            "sim|claro|pode|quero|abre|por favor|beleza)\\b", RegexOption.IGNORE_CASE)
        private val NO_TO_OFFER = Regex("^(oh |well |ok |okay )?(no|nope|nah|no thanks|not now|don'?t|não|nao|agora não|deixa pra lá)\\b",
            RegexOption.IGNORE_CASE)
        // Words for "the place we were just talking about".
        private val PLACE_PRONOUNS = setOf("it", "there", "that", "that place", "this place", "the place", "the store",
            "the shop", "that one", "the same place")
        private val CONFIRMATIONS = listOf(
            "Got it — I'll remember that.",
            "Noted. It's safe with me.",
            "Consider it remembered.",
            "Locked in. I won't forget.",
        )
    }
}
