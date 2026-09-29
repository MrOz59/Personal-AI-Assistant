package com.naomi.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** How often a reminder comes back. */
enum class Repeat {
    NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY;

    /** When a reminder that went off at [at] comes back next ([at] itself for a one-off). */
    fun after(at: LocalDateTime): LocalDateTime = when (this) {
        NONE -> at
        DAILY -> at.plusDays(1)
        WEEKDAYS -> {
            var next = at.plusDays(1)
            while (next.dayOfWeek == DayOfWeek.SATURDAY || next.dayOfWeek == DayOfWeek.SUNDAY) next = next.plusDays(1)
            next
        }
        WEEKLY -> at.plusWeeks(1)
        MONTHLY -> at.plusMonths(1)
    }

    companion object {
        /** A repeat as a model or the file names it ("daily", "weekdays"); anything else is [NONE]. */
        fun of(name: String?): Repeat = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: NONE
    }
}

/**
 * Something to remind the owner of at [at] (epoch millis), coming back as [repeat] says.
 * [portuguese]: it was asked for in Portuguese, so that's how she says it when it goes off.
 */
data class Reminder(
    val id: Long,
    val text: String,
    val at: Long,
    val repeat: Repeat = Repeat.NONE,
    val portuguese: Boolean = false,
    val created: Long,
)

/**
 * The reminders that are set, kept in naomi_reminders.json in internal storage. This only keeps
 * them; [ReminderAlarms] puts them on the system's alarm clock and [ReminderReceiver] sets them off.
 */
class ReminderStore(private val file: File, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {

    /** Called, on whichever thread made the change, whenever a reminder is added, moved on or removed. */
    @Volatile var onChange: (() -> Unit)? = null

    private val reminders = mutableListOf<Reminder>()
    private var nextId = 1L

    init {
        load()
    }

    /** Everything set, soonest first. */
    @Synchronized
    fun all(): List<Reminder> = reminders.sortedBy { it.at }

    @Synchronized
    fun get(id: Long): Reminder? = reminders.firstOrNull { it.id == id }

    /** The one set most recently — "cancel that" means it. */
    @Synchronized
    fun latest(): Reminder? = reminders.maxByOrNull { it.created }

    fun add(text: String, at: Long, repeat: Repeat = Repeat.NONE, portuguese: Boolean = false, now: Long = System.currentTimeMillis()): Reminder {
        val added = synchronized(this) {
            Reminder(nextId++, text.trim(), at, repeat, portuguese, now).also { reminders += it; save() }
        }
        onChange?.invoke()
        return added
    }

    /** Removes the reminders with [ids], returning those that were there. */
    fun remove(ids: Collection<Long>): List<Reminder> {
        val gone = synchronized(this) {
            reminders.filter { it.id in ids }.also { if (it.isNotEmpty()) { reminders.removeAll(it); save() } }
        }
        if (gone.isNotEmpty()) onChange?.invoke()
        return gone
    }

    /**
     * What going off does to reminder [id]: a repeat moves on to its next time after [now] and is
     * returned, to be put back on the alarm clock; a one-off is done, and gone (null).
     */
    fun fired(id: Long, now: Long = System.currentTimeMillis()): Reminder? {
        val next = synchronized(this) {
            val r = reminders.firstOrNull { it.id == id } ?: return null
            if (r.repeat == Repeat.NONE) {
                reminders.remove(r)
                save()
                null
            } else {
                var at = local(r.at)
                val after = local(now)
                while (!at.isAfter(after)) at = r.repeat.after(at)
                r.copy(at = epochOf(at)).also { reminders[reminders.indexOf(r)] = it; save() }
            }
        }
        onChange?.invoke()
        return next
    }

    /**
     * The reminders [what] describes, by the words they share: "dentist" finds "call the dentist".
     * Only the best matches, and only if they share at least half its words.
     */
    fun matching(what: String): List<Reminder> {
        val words = termsOf(what)
        if (words.isEmpty()) return emptyList()
        val scored = all().map { r ->
            val its = termsOf(r.text)
            r to words.count { w -> its.any { MemoryBank.similar(w, it) } }
        }
        val best = scored.maxOfOrNull { it.second } ?: 0
        if (best == 0 || best * 2 < words.size) return emptyList()
        return scored.filter { it.second == best }.map { it.first }
    }

    /** The local time [epochMillis] falls on. */
    fun local(epochMillis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone())

    /** The epoch millis of local time [at]. */
    fun epochOf(at: LocalDateTime): Long = at.atZone(zone()).toInstant().toEpochMilli()

    private fun termsOf(text: String): Set<String> = MemoryBank.termsOf(text, null) - PT_FILLER

    private fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val list = json.optJSONArray("reminders") ?: return
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val text = o.optString("text").takeIf { it.isNotBlank() } ?: continue
                reminders += Reminder(o.optLong("id"), text, o.optLong("at"), Repeat.of(o.optString("repeat")),
                    o.optBoolean("pt"), o.optLong("created"))
            }
            // Ids aren't reused, so an alarm or notification left over from an old reminder can't be taken for a new one.
            nextId = maxOf(json.optLong("next_id", 1L), (reminders.maxOfOrNull { it.id } ?: 0L) + 1)
        } catch (e: JSONException) {
            android.util.Log.e("Naomi", "Reminders unreadable: ${e.message}")
        }
    }

    private fun save() {
        val list = JSONArray()
        for (r in reminders) {
            list.put(JSONObject().put("id", r.id).put("text", r.text).put("at", r.at).put("repeat", r.repeat.name)
                .put("pt", r.portuguese).put("created", r.created))
        }
        // Written aside and moved into place, so a crash mid-write can't lose them all.
        val temp = File(file.path + ".tmp")
        temp.writeText(JSONObject().put("next_id", nextId).put("reminders", list).toString(1))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        // Portuguese words too common to tell reminders apart (the memory's own list is English).
        private val PT_FILLER = setOf("de", "da", "do", "das", "dos", "o", "os", "as", "um", "uma", "pra", "pro", "para",
            "que", "em", "no", "na", "com", "me", "te", "se", "meu", "minha")

        /** The reminders for this app, shared by the brain and the alarm receiver in the process. */
        @Volatile private var instance: ReminderStore? = null

        fun get(context: Context): ReminderStore = instance ?: synchronized(this) {
            instance ?: ReminderStore(File(context.applicationContext.filesDir, "naomi_reminders.json")).also { instance = it }
        }
    }
}
