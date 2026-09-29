package com.naomi.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ln

/**
 * Naomi's long-term memory, beyond the named facts in [MemoryStore]: what she's learned about the
 * owner ("Ozzy's sister Ana lives in Lisbon") and short notes on past conversations. Kept in
 * naomi_memories.json in internal storage.
 *
 * Recall is on-device and lexical: memories are scored by the words they share with what was just
 * said, rare words counting most (IDF), with word endings folded ("parked" ~ "parking"). Only the
 * best few go to the brain each turn, so a small model's context stays small and the memory never
 * leaves the phone all at once.
 */
class MemoryBank(private val file: File) {

    enum class Kind { FACT, EPISODE }

    data class Memory(
        val id: Long,
        val text: String,
        val kind: Kind,
        /** What a fact is about ("sister's name"); a newer fact on the same topic replaces it. */
        val topic: String = "",
        val created: Long,
        /** When it was last recalled into a conversation; the least used facts go first when full. */
        val used: Long = created,
    )

    /** The owner's name, left out of matching: it's in nearly every memory, so it tells none apart. */
    @Volatile var ownerName: String? = null

    /** Called, on whichever thread made the change, whenever memories are added, edited or removed. */
    @Volatile var onChange: (() -> Unit)? = null

    private val memories = mutableListOf<Memory>()
    private var nextId = 1L

    init {
        load()
    }

    /** Everything, newest first. */
    @Synchronized
    fun all(): List<Memory> = memories.sortedByDescending { it.created }

    /**
     * Keeps a fact. An older one on the same specific [topic], or saying nearly the same thing, is
     * replaced — so a changed fact ("moved to Melbourne") doesn't sit next to the one it changes.
     */
    fun remember(text: String, topic: String = "", now: Long = System.currentTimeMillis()): Memory {
        val key = normalizeTopic(topic)
        val stored = synchronized(this) {
            val words = terms(text)
            val old = memories.firstOrNull { it.kind == Kind.FACT && key.isNotEmpty() && key !in BROAD_TOPICS && it.topic == key }
                ?: memories.firstOrNull { it.kind == Kind.FACT && jaccard(words, terms(it.text)) >= SAME_FACT }
            val memory = Memory(old?.id ?: nextId++, text.trim(), Kind.FACT, key.ifEmpty { old?.topic.orEmpty() }, now)
            if (old != null) memories[memories.indexOf(old)] = memory else memories += memory
            trim()
            save()
            memory
        }
        onChange?.invoke()
        return stored
    }

    /** Keeps the note of a finished conversation. */
    fun addEpisode(text: String, now: Long = System.currentTimeMillis()): Memory {
        val stored = synchronized(this) {
            Memory(nextId++, text.trim(), Kind.EPISODE, created = now).also {
                memories += it
                trim()
                save()
            }
        }
        onChange?.invoke()
        return stored
    }

    /** Rewords a memory (from the memory screen). */
    fun update(id: Long, text: String) {
        synchronized(this) {
            val i = memories.indexOfFirst { it.id == id }
            if (i < 0 || text.isBlank()) return
            memories[i] = memories[i].copy(text = text.trim())
            save()
        }
        onChange?.invoke()
    }

    /** Removes the memories with these [ids], returning what was removed. */
    fun delete(ids: Collection<Long>): List<Memory> {
        val removed = synchronized(this) {
            memories.filter { it.id in ids }.also {
                if (it.isEmpty()) return it
                memories.removeAll(it)
                save()
            }
        }
        onChange?.invoke()
        return removed
    }

    fun clear() {
        synchronized(this) {
            memories.clear()
            save()
        }
        onChange?.invoke()
    }

    /**
     * The memories of [kind] that best match [query], best first: at most [limit], and only ones
     * sharing a telling word with it. Unless [markUsed] is false, they count as recalled now.
     */
    fun relevant(
        query: String,
        kind: Kind,
        limit: Int,
        markUsed: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): List<Memory> = synchronized(this) {
        val ranked = rank(terms(query), memories.filter { it.kind == kind })
        val top = ranked.firstOrNull()?.second ?: return emptyList()
        val found = ranked.filter { it.second >= top * RELATIVE_CUTOFF }.take(limit).map { it.first }
        if (markUsed) markUsed(found.map { it.id }.toSet(), now)
        found
    }

    /**
     * Removes the memories [what] describes, returning them: clear matches only (sharing most of
     * its telling words, and close to the best match), at most [MAX_FORGET] at a time.
     */
    fun forget(what: String): List<Memory> {
        val gone = synchronized(this) {
            val query = terms(what)
            val ranked = rank(query, memories.toList(), minShare = 0.5)
            val top = ranked.firstOrNull()?.second ?: return emptyList()
            ranked.filter { it.second >= top * FORGET_CUTOFF }.take(MAX_FORGET).map { it.first }.also {
                memories.removeAll(it.toSet())
                save()
            }
        }
        onChange?.invoke()
        return gone
    }

    /** The most recent conversation note, if it's from within [withinMs]. */
    @Synchronized
    fun lastEpisode(withinMs: Long, now: Long = System.currentTimeMillis()): Memory? =
        memories.filter { it.kind == Kind.EPISODE && now - it.created <= withinMs }.maxByOrNull { it.created }

    /** The facts learned most recently — for "what do you know about me?". */
    @Synchronized
    fun overview(limit: Int): List<Memory> =
        memories.filter { it.kind == Kind.FACT }.sortedByDescending { it.created }.take(limit)

    private fun markUsed(ids: Set<Long>, now: Long) {
        if (ids.isEmpty()) return
        for (i in memories.indices) if (memories[i].id in ids) memories[i] = memories[i].copy(used = now)
        save()
    }

    /** Past capacity, the least recently used facts and the oldest notes go first. */
    private fun trim() {
        val facts = memories.filter { it.kind == Kind.FACT }
        if (facts.size > MAX_FACTS) memories.removeAll(facts.sortedBy { it.used }.take(facts.size - MAX_FACTS).toSet())
        val episodes = memories.filter { it.kind == Kind.EPISODE }
        if (episodes.size > MAX_EPISODES) {
            memories.removeAll(episodes.sortedBy { it.created }.take(episodes.size - MAX_EPISODES).toSet())
        }
    }

    /**
     * [pool] scored against the [query] terms, best first, leaving out memories that share none of
     * its telling words — or, with [minShare], less than that fraction of them. Each shared word
     * counts by how rare it is; a word in most memories tells them apart from nothing and doesn't
     * count at all. Long memories are discounted a little, so they don't win by sheer size.
     */
    private fun rank(query: Set<String>, pool: List<Memory>, minShare: Double = 0.0): List<Pair<Memory, Double>> {
        if (query.isEmpty() || pool.isEmpty()) return emptyList()
        val indexed = pool.map { it to terms(it.text + " " + it.topic) }
        val n = indexed.size
        val averageLength = (indexed.sumOf { it.second.size }.toDouble() / n).coerceAtLeast(1.0)
        val weights = query.mapNotNull { q ->
            val df = indexed.count { (_, words) -> words.any { similar(q, it) } }
            if (df == 0 || (n >= 6 && df * 2 > n)) null else q to ln((n + 1.0) / (df + 0.5))
        }
        return indexed.mapNotNull { (memory, words) ->
            val shared = weights.filter { (q, _) -> words.any { similar(q, it) } }
            if (shared.isEmpty() || shared.size < minShare * query.size) return@mapNotNull null
            memory to shared.sumOf { it.second } / (0.5 + 0.5 * words.size / averageLength)
        }.sortedByDescending { it.second }
    }

    /** The telling words of [text], stemmed — no filler, and not the owner's name. */
    fun terms(text: String): Set<String> = termsOf(text, ownerName)

    /** A topic as it's compared: lowercase, and without the owner in it ("Ozzy's home city" → "home city"). */
    private fun normalizeTopic(topic: String): String {
        var t = topic.lowercase(Locale.ROOT).replace('’', '\'')
        ownerName?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let {
            t = t.replace(Regex("\\b${Regex.escape(it)}('s)?\\b"), " ")
        }
        return t.replace(Regex("\\b(the )?user('s)?\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val list = JSONObject(file.readText()).optJSONArray("memories") ?: return
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: continue
                val text = o.optString("text").takeIf { it.isNotBlank() } ?: continue
                val created = o.optLong("created")
                memories += Memory(o.optLong("id"), text, kind, o.optString("topic"), created, o.optLong("used", created))
            }
            nextId = (memories.maxOfOrNull { it.id } ?: 0L) + 1
        } catch (e: JSONException) {
            android.util.Log.e("Naomi", "Memories unreadable: ${e.message}")
        }
    }

    private fun save() {
        val list = JSONArray()
        for (m in memories) {
            list.put(JSONObject().put("id", m.id).put("kind", m.kind.name).put("text", m.text)
                .put("topic", m.topic).put("created", m.created).put("used", m.used))
        }
        // Written aside and moved into place, so a crash mid-write can't cost the whole memory.
        val temp = File(file.path + ".tmp")
        temp.writeText(JSONObject().put("memories", list).toString(1))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        // Kept at most; past these, the least used facts and the oldest notes are let go.
        private const val MAX_FACTS = 400
        private const val MAX_EPISODES = 200
        // Facts sharing this much of their words say the same thing.
        private const val SAME_FACT = 0.6
        // Recalled: memories scoring at least this share of the best match.
        private const val RELATIVE_CUTOFF = 0.35
        // Forgotten: only memories this close to the best match, and never many at once.
        private const val FORGET_CUTOFF = 0.6
        private const val MAX_FORGET = 3

        private val WORD = Regex("[\\p{L}\\p{N}']+")

        /** Topics too broad to mean "the same fact" — the model sometimes files everything under them. */
        private val BROAD_TOPICS = setOf("family", "friends", "people", "relationships", "personal", "personal info",
            "info", "information", "life", "general", "other", "misc", "preferences", "preference", "likes", "dislikes",
            "interests", "hobbies", "plans", "fact", "facts", "memory", "memories", "about")

        private val STOPWORDS = setOf(
            "a", "about", "above", "after", "again", "against", "all", "also", "am", "an", "and", "any", "are",
            "aren't", "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but",
            "by", "can", "can't", "cannot", "could", "couldn't", "did", "didn't", "do", "does", "doesn't", "doing",
            "don't", "down", "during", "each", "even", "ever", "every", "few", "for", "from", "further", "get",
            "gets", "getting", "go", "going", "gonna", "got", "had", "hadn't", "has", "hasn't", "have", "haven't",
            "having", "he", "he'd", "he'll", "he's", "her", "here", "hers", "herself", "hey", "hi", "him",
            "himself", "his", "how", "i", "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it",
            "it'd", "it'll", "it's", "its", "itself", "just", "know", "let", "let's", "lot", "many", "may", "me",
            "might", "mine", "more", "most", "much", "must", "my", "myself", "naomi", "no", "nor", "not", "now",
            "of", "off", "oh", "ok", "okay", "on", "once", "one", "only", "or", "other", "our", "ours", "ourselves",
            "out", "over", "own", "please", "really", "remember", "same", "say", "said", "she", "she'd", "she'll",
            "she's", "should", "shouldn't", "so", "some", "something", "still", "such", "tell", "than", "that",
            "that's", "the", "their", "theirs", "them", "themselves", "then", "there", "there's", "these", "they",
            "they'd", "they'll", "they're", "they've", "thing", "things", "think", "this", "those", "though",
            "through", "to", "too", "under", "until", "up", "us", "user", "very", "want", "was", "wasn't", "we",
            "we'd", "we'll", "we're", "we've", "well", "were", "weren't", "what", "what's", "when", "when's",
            "where", "where's", "which", "while", "who", "who's", "whom", "why", "why's", "will", "with", "won't",
            "would", "wouldn't", "yeah", "yes", "yet", "you", "you'd", "you'll", "you're", "you've", "your",
            "yours", "yourself", "yourselves", "forget", "anything", "everything", "nothing", "stuff",
            "today", "tonight", "tomorrow", "yesterday", "day",
        )

        /** The memory for this app, shared by every screen and brain in the process. */
        @Volatile private var instance: MemoryBank? = null

        fun get(context: Context): MemoryBank = instance ?: synchronized(this) {
            instance ?: MemoryBank(File(context.applicationContext.filesDir, "naomi_memories.json")).also { instance = it }
        }

        /** The telling words of [text], stemmed — no filler, and not [owner]'s name. */
        fun termsOf(text: String, owner: String?): Set<String> {
            val name = owner?.lowercase(Locale.ROOT)
            return WORD.findAll(text.lowercase(Locale.ROOT).replace('’', '\''))
                .map { it.value.removeSuffix("'s").trim('\'') }
                .filter { it.length >= 2 && it !in STOPWORDS && it != name }
                .map(::stem)
                .toSet()
        }

        /** Same word, allowing a slightly longer ending ("movi" ~ "movie", "vegetarian" ~ "vegetarians"). */
        internal fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            val (short, long) = if (a.length <= b.length) a to b else b to a
            return short.length >= 4 && long.startsWith(short) && long.length - short.length <= 3
        }

        private fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

        /**
         * Folds English word endings so different forms meet: step 1 of Porter's stemmer
         * ("parked", "parking" → "park"; "liked", "likes" → "like"; "parties", "party" → "parti").
         */
        internal fun stem(word: String): String {
            if (word.length <= 3 || !word.all { it in 'a'..'z' }) return word
            // Plurals.
            var w = when {
                word.endsWith("sses") -> word.dropLast(2)
                word.endsWith("ies") -> word.dropLast(2)
                word.endsWith("ss") -> word
                word.endsWith("s") -> word.dropLast(1)
                else -> word
            }
            // -ed and -ing, then mending the stem they leave ("hat" → "hate", "runn" → "run", "lik" → "like").
            if (w.endsWith("eed")) {
                if (measure(w.dropLast(3)) > 0) w = w.dropLast(1)
            } else {
                val base = when {
                    w.endsWith("ed") && hasVowel(w.dropLast(2)) -> w.dropLast(2)
                    w.endsWith("ing") && hasVowel(w.dropLast(3)) -> w.dropLast(3)
                    else -> null
                }
                if (base != null) w = when {
                    base.endsWith("at") || base.endsWith("bl") || base.endsWith("iz") -> base + "e"
                    base.length >= 2 && base.last() == base[base.length - 2] && isConsonant(base, base.length - 1) &&
                        base.last() !in "lsz" -> base.dropLast(1)
                    measure(base) == 1 && endsCvc(base) -> base + "e"
                    else -> base
                }
            }
            // A final y after a vowel-bearing stem: "party" and "parties" both end up "parti".
            if (w.endsWith("y") && hasVowel(w.dropLast(1))) w = w.dropLast(1) + "i"
            return w
        }

        private fun isConsonant(w: String, i: Int): Boolean = when (w[i]) {
            'a', 'e', 'i', 'o', 'u' -> false
            'y' -> i == 0 || !isConsonant(w, i - 1)
            else -> true
        }

        private fun hasVowel(w: String): Boolean = w.indices.any { !isConsonant(w, it) }

        /** Porter's measure: how many vowel-consonant runs the word has. */
        private fun measure(w: String): Int {
            var m = 0
            var i = 0
            while (i < w.length && isConsonant(w, i)) i++
            while (i < w.length) {
                while (i < w.length && !isConsonant(w, i)) i++
                if (i >= w.length) break
                while (i < w.length && isConsonant(w, i)) i++
                m++
            }
            return m
        }

        private fun endsCvc(w: String): Boolean = w.length >= 3 && isConsonant(w, w.length - 3) &&
            !isConsonant(w, w.length - 2) && isConsonant(w, w.length - 1) && w.last() !in "wxy"

        private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
        private val WEEKDAY_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
        private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        private val WEEKDAYS = DayOfWeek.entries.joinToString("|") { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }
        // Not already followed by a pinned date — so pinning twice changes nothing.
        private const val UNPINNED = "(?!\\s*\\()"

        /**
         * [text] with its relative dates pinned down — "on Friday" → "on Friday (2 October 2026)" —
         * so a memory still means the right day when it's recalled weeks later. Recurring days
         * ("every Friday", "on Fridays") are left alone.
         */
        fun pinDates(text: String, today: LocalDate): String {
            fun pin(t: String, pattern: String, date: (MatchResult) -> String?): String =
                Regex("\\b(?:$pattern)\\b$UNPINNED", RegexOption.IGNORE_CASE).replace(t) { m ->
                    date(m)?.let { "${m.value} ($it)" } ?: m.value
                }
            var t = text
            t = pin(t, "day after tomorrow") { today.plusDays(2).format(WEEKDAY_DAY) }
            t = pin(t, "(?<!after )tomorrow") { today.plusDays(1).format(WEEKDAY_DAY) }
            t = pin(t, "yesterday") { today.minusDays(1).format(WEEKDAY_DAY) }
            t = pin(t, "today|tonight|this morning|this afternoon|this evening") { today.format(WEEKDAY_DAY) }
            // Not after "every", nor a weekday that's part of a date already pinned: "(Wednesday 30 …)".
            t = pin(t, "(?<!every |each |\\()(last |next |this |coming )?($WEEKDAYS)(?!s)") { m ->
                val day = DayOfWeek.valueOf(m.groupValues[2].uppercase(Locale.ROOT))
                when (m.groupValues[1].trim().lowercase(Locale.ROOT)) {
                    "last" -> today.with(TemporalAdjusters.previous(day))
                    "this" -> today.with(TemporalAdjusters.nextOrSame(day))
                    else -> today.with(TemporalAdjusters.next(day))
                }.format(DAY)
            }
            t = pin(t, "this weekend") {
                (if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1)
                 else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))).let { "the weekend of ${it.format(DAY)}" }
            }
            t = pin(t, "next week") {
                "the week of " + today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).format(DAY)
            }
            t = pin(t, "next month") { today.plusMonths(1).format(MONTH) }
            t = pin(t, "next year") { (today.year + 1).toString() }
            t = pin(t, "last year") { (today.year - 1).toString() }
            return t
        }

        /** When a note was made, as it's said: "Earlier today", "Yesterday", "3 days ago", "On 12 September". */
        fun whenSaid(created: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val then = Instant.ofEpochMilli(created).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(then, today)
            return when {
                days <= 0 -> "Earlier today"
                days == 1L -> "Yesterday"
                days < 7 -> "$days days ago"
                then.year == today.year -> "On " + then.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
                else -> "On " + then.format(DAY)
            }
        }

        /** A memory as the brain reads it: facts as they are, notes with when they're from. */
        fun describe(m: Memory, now: Long = System.currentTimeMillis()): String =
            if (m.kind == Kind.FACT) m.text else "${whenSaid(m.created, now)}: ${m.text}"

        // Words for which "I …" becomes "<name> …" unchanged: past tenses and modals, plus verbs
        // whose past looks like their present — "remember that I put the key under the mat".
        private val SAME_AFTER_NAME = setOf(
            "can", "could", "will", "would", "shall", "should", "may", "might", "must", "did", "had", "was",
            "can't", "couldn't", "won't", "wouldn't", "shouldn't", "didn't", "hadn't", "wasn't", "mustn't", "used",
            "put", "set", "cut", "hit", "let", "shut", "read", "quit", "cost", "hurt", "bet", "left", "went",
            "got", "saw", "met", "bought", "made", "told", "took", "gave", "ate", "ran", "found", "lost", "felt",
            "kept", "began", "came", "broke", "drove", "flew", "wrote", "spent", "sent", "paid", "sold", "won",
            "forgot", "heard", "held", "hid", "knew", "led", "lent", "meant", "rode", "rang", "sang", "sat", "slept",
            "spoke", "stood", "stole", "swam", "taught", "thought", "threw", "understood", "woke", "wore", "brought",
            "caught", "chose", "drank", "fell", "fed", "fought", "froze", "grew", "hung", "built", "became", "moved",
        )
        private val ADVERBS = setOf("always", "never", "usually", "often", "sometimes", "also", "just", "really",
            "still", "only", "actually", "even", "rarely", "normally", "generally", "definitely", "probably", "already")
        private val THIRD_PERSON = mapOf("am" to "is", "have" to "has", "do" to "does", "don't" to "doesn't",
            "haven't" to "hasn't", "go" to "goes")

        /**
         * Something the owner said about themselves, retold about them — "I parked on level 3" →
         * "Ozzy parked on level 3", "I like my coffee black" → "Ozzy likes their coffee black" —
         * the way every memory is written. Only first-person words change; the rest is kept as said.
         */
        fun inThirdPerson(text: String, name: String): String {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            var named = false
            var i = 0
            fun core(token: String) = token.lowercase(Locale.ROOT).replace('’', '\'').trim { !it.isLetterOrDigit() && it != '\'' }
            fun tail(token: String) = token.takeLastWhile { !it.isLetterOrDigit() && it != '\'' && it != '’' }
            // The verb after "<name>", made to agree with it ("like" → "likes"); adverbs in between kept.
            fun agree() {
                while (i < tokens.size && core(tokens[i]) in ADVERBS) out += tokens[i++]
                if (i >= tokens.size) return
                val token = tokens[i]
                val w = core(token)
                out += when {
                    w in THIRD_PERSON -> THIRD_PERSON.getValue(w) + tail(token)
                    w in SAME_AFTER_NAME || w.endsWith("ed") || w.endsWith("n't") || !w.all { it.isLetter() } -> token
                    w.endsWith("y") && w.length > 2 && w[w.length - 2] !in "aeiou" -> w.dropLast(1) + "ies" + tail(token)
                    w.endsWith("s") || w.endsWith("sh") || w.endsWith("ch") || w.endsWith("x") || w.endsWith("z") ||
                        w.endsWith("o") -> w + "es" + tail(token)
                    else -> w + "s" + tail(token)
                }
                i++
            }
            while (i < tokens.size) {
                val token = tokens[i]
                val tail = tail(token)
                val w = core(token)
                // The first mention is by name; after that, they/their.
                val subject = if (named) null else name
                when (w) {
                    "i" -> {
                        i++
                        if (subject != null) { out += subject + tail; named = true; if (tail.isEmpty()) agree() }
                        else {
                            out += "they$tail"
                            if (tail.isEmpty() && i < tokens.size) {
                                when (core(tokens[i])) {
                                    "am" -> { out += "are" + tail(tokens[i]); i++ }
                                    "was" -> { out += "were" + tail(tokens[i]); i++ }
                                }
                            }
                        }
                        continue
                    }
                    "i'm" -> out += if (subject != null) "$subject is$tail" else "they're$tail"
                    "i've" -> out += if (subject != null) "$subject has$tail" else "they've$tail"
                    "i'd" -> out += if (subject != null) "$subject would$tail" else "they'd$tail"
                    "i'll" -> out += if (subject != null) "$subject will$tail" else "they'll$tail"
                    "my" -> out += if (subject != null) "$subject's$tail" else "their$tail"
                    "me" -> out += (subject ?: "them") + tail
                    "mine" -> out += if (subject != null) "$subject's$tail" else "theirs$tail"
                    "myself" -> out += (subject ?: "themselves") + tail
                    else -> { out += token; i++; continue }
                }
                named = true
                i++
            }
            return out.joinToString(" ").replaceFirstChar { it.uppercase() }
        }
    }
}
