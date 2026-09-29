package com.naomi.assistant

import android.content.Context
import java.time.LocalDateTime

/**
 * Reminders by voice, for the keyword router and the cloud brain alike: setting one ("remind me
 * to call mom at 5"), hearing what's set, and cancelling. What's missing — what, or when — she
 * asks for. Replies come in the language the request was in.
 */
class ReminderCommands(context: Context) {

    private val appContext = context.applicationContext
    private val store = ReminderStore.get(appContext)

    /**
     * A new reminder from [said], the user's own words. [text], [whenSaid] and [repeatSaid] are a
     * model's reading of them, for what the words alone don't settle: a time the user said wins
     * over the model's arithmetic, as a timer's does; the model's wording of what to remember
     * wins over what's left of the sentence.
     */
    fun set(said: String, text: String = "", whenSaid: String = "", repeatSaid: String = ""): CommandRouter.Result {
        val now = LocalDateTime.now()
        val heard = ReminderParser.parse(said, now)
        val what = text.trim().ifBlank { heard?.text.orEmpty() }
        val time = heard?.time ?: ReminderParser.parseWhen(said, now)
            ?: ReminderParser.modelTime(whenSaid, now)?.let { ReminderParser.When(it, Repeat.of(repeatSaid)) }
        return complete(what, time, ReminderText.portuguese(said))
    }

    /** "What are my reminders?" */
    fun list(said: String): CommandRouter.Result =
        CommandRouter.Result.Handled(ReminderText.list(store.all(), store::local, LocalDateTime.now(), ReminderText.portuguese(said)))

    /**
     * Cancels the reminder [said] means — or [what], a model's reading of which one: by its words
     * ("the dentist one"), by its day ("tomorrow's"), the last one set ("cancel that"), or all of
     * them, once they've said yes.
     */
    fun cancel(said: String, what: String = ""): CommandRouter.Result {
        val pt = ReminderText.portuguese(said)
        val set = store.all()
        if (set.isEmpty()) return CommandRouter.Result.Handled(ReminderText.none(pt))
        val target = ReminderParser.cancelTarget(what.ifBlank { said })
        if (target == "all") {
            return CommandRouter.Result.Ask(ReminderText.confirmAll(set.size, pt)) { answer ->
                if (!YES.containsMatchIn(answer.trim())) CommandRouter.Result.Handled(ReminderText.keptAll(pt))
                else CommandRouter.Result.Handled(ReminderText.clearedAll(drop(set).size, pt))
            }
        }
        val found = when {
            target.isBlank() -> listOfNotNull(store.latest())
            else -> store.matching(target).ifEmpty { onTheDay(target) }
        }
        if (found.isEmpty()) return CommandRouter.Result.Handled(ReminderText.notFound(pt))
        return CommandRouter.Result.Handled(ReminderText.cancelled(drop(found), pt))
    }

    /** Sets [what] for [time], asking for whichever of the two is missing first. */
    private fun complete(what: String, time: ReminderParser.When?, pt: Boolean): CommandRouter.Result {
        if (what.isBlank()) {
            return CommandRouter.Result.Ask(ReminderText.askWhat(pt)) { answer ->
                if (NEVER_MIND.containsMatchIn(answer)) CommandRouter.Result.Handled(ReminderText.dropped(pt))
                // "To call mom at 5" answers the time too.
                else complete(ReminderParser.withoutWhen(answer), time ?: ReminderParser.parseWhen(answer, LocalDateTime.now()), pt)
            }
        }
        if (time == null) return askWhen(what, pt, ReminderText.askWhen(pt))
        val reminder = store.add(what, store.epochOf(time.at), time.repeat, pt)
        ReminderAlarms.schedule(appContext, reminder)
        android.util.Log.i("Naomi", "Reminder ${reminder.id} set for ${time.at} (${time.repeat}): $what")
        return CommandRouter.Result.Handled(ReminderText.set(what, time.at, time.repeat, LocalDateTime.now(), pt))
    }

    private fun askWhen(what: String, pt: Boolean, question: String): CommandRouter.Result =
        CommandRouter.Result.Ask(question) { answer ->
            val time = ReminderParser.parseWhen(answer, LocalDateTime.now())
            when {
                time != null -> complete(what, time, pt)
                NEVER_MIND.containsMatchIn(answer) -> CommandRouter.Result.Handled(ReminderText.dropped(pt))
                else -> askWhen(what, pt, ReminderText.askWhenAgain(pt))
            }
        }

    /** The reminders on the day [said] names ("tomorrow's", "o de sexta"), if it names one. */
    private fun onTheDay(said: String): List<Reminder> {
        val day = ReminderParser.parseWhen(said, LocalDateTime.now())?.at?.toLocalDate() ?: return emptyList()
        return store.all().filter { store.local(it.at).toLocalDate() == day }
    }

    /** Takes [reminders] off the alarm clock and out of the store. */
    private fun drop(reminders: List<Reminder>): List<Reminder> =
        store.remove(reminders.map { it.id }).onEach { ReminderAlarms.cancel(appContext, it.id) }

    companion object {
        private val YES = Regex("^(?:oh |well |ok |okay )?(?:yes|yeah|yep|yup|sure|ok|okay|go ahead|do it|sim|pode|claro|isso|manda|beleza)\\b",
            RegexOption.IGNORE_CASE)
        private val NEVER_MIND = Regex("^(?:never ?mind|forget it|cancel|no|nope|nothing|deixa|esquece|cancela|nada|não|nao)\\b",
            RegexOption.IGNORE_CASE)
    }
}
