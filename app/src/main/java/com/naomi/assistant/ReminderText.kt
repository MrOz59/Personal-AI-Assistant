package com.naomi.assistant

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Naomi says about reminders, in English or Portuguese — whichever the request was in
 * ([portuguese] picks it). Spoken lines, so times read naturally: "tomorrow at 9:00 AM", "amanhã
 * às 9:00", "daqui a 20 minutos".
 */
object ReminderText {

    /** Whether to answer [said] in Portuguese. The one place that decides the language of her reminder lines. */
    fun portuguese(said: String): Boolean = ReminderParser.isPortuguese(said)

    /** "Got it. I'll remind you tomorrow at 9:00 AM: call the dentist." */
    fun set(text: String, at: LocalDateTime, repeat: Repeat, now: LocalDateTime, pt: Boolean): String =
        if (pt) "${OK_PT.random()} Eu te lembro ${whenSaid(at, repeat, now, true)}: $text."
        else "${OK_EN.random()} I'll remind you ${whenSaid(at, repeat, now, false)}: $text."

    /** What's set, soonest first, as one spoken answer. */
    fun list(reminders: List<Reminder>, local: (Long) -> LocalDateTime, now: LocalDateTime, pt: Boolean): String {
        if (reminders.isEmpty()) return if (pt) "Você não tem nenhum lembrete." else "You don't have any reminders."
        val said = reminders.take(MAX_LISTED).joinToString("; ") { "${it.text}, ${whenSaid(local(it.at), it.repeat, now, pt)}" }
        val more = reminders.size - MAX_LISTED
        return if (pt) {
            val head = if (reminders.size == 1) "Você tem um lembrete" else "Você tem ${reminders.size} lembretes"
            "$head: $said" + if (more > 0) "; e mais $more." else "."
        } else {
            val head = if (reminders.size == 1) "You have one reminder" else "You have ${reminders.size} reminders"
            "$head: $said" + if (more > 0) "; and $more more." else "."
        }
    }

    fun cancelled(gone: List<Reminder>, pt: Boolean): String = when {
        gone.size == 1 && pt -> "Pronto, cancelei o lembrete: ${gone[0].text}."
        gone.size == 1 -> "Done, I've cancelled that reminder: ${gone[0].text}."
        pt -> "Pronto, cancelei ${gone.size} lembretes."
        else -> "Done, I've cancelled ${gone.size} reminders."
    }

    fun none(pt: Boolean) = if (pt) "Você não tem nenhum lembrete." else "You don't have any reminders."
    fun notFound(pt: Boolean) = if (pt) "Não achei nenhum lembrete assim." else "I couldn't find a reminder like that."
    fun askWhat(pt: Boolean) = if (pt) "Te lembro do quê?" else "What should I remind you about?"
    fun askWhen(pt: Boolean) = if (pt) "Quando eu te lembro?" else "When should I remind you?"
    fun askWhenAgain(pt: Boolean) =
        if (pt) "Não entendi o horário. Fala tipo \"amanhã às 9\" ou \"daqui a 20 minutos\"."
        else "I didn't catch the time. Say something like \"tomorrow at 9\" or \"in 20 minutes\"."
    fun dropped(pt: Boolean) = if (pt) "Tá bom, deixa pra lá." else "Okay, never mind."
    fun confirmAll(count: Int, pt: Boolean) = if (pt) "Cancelo todos os $count lembretes?" else "Cancel all $count reminders?"
    fun clearedAll(count: Int, pt: Boolean) = if (pt) "Pronto, apaguei os $count lembretes." else "Done, all $count reminders are gone."
    fun keptAll(pt: Boolean) = if (pt) "Tá bom, deixei todos." else "Okay, I've kept them."

    /** What she says when one goes off. */
    fun alert(text: String, pt: Boolean): String = (if (pt) ALERT_PT else ALERT_EN).random().format(text.trimEnd('.', '!'))

    /** The notification channel's name, and its buttons. */
    fun channel(pt: Boolean) = if (pt) "Lembretes" else "Reminders"
    fun snooze(pt: Boolean) = if (pt) "Adiar 10 min" else "Snooze 10 min"
    fun done(pt: Boolean) = if (pt) "Feito" else "Done"

    /** A calendar event added outright: "Added "Dentist" to your calendar for tomorrow at 3:00 PM." */
    fun eventAdded(title: String, at: LocalDateTime, now: LocalDateTime, pt: Boolean): String =
        if (pt) "Coloquei \"$title\" na sua agenda, ${whenSaid(at, Repeat.NONE, now, true)}."
        else "Added \"$title\" to your calendar for ${whenSaid(at, Repeat.NONE, now, false)}."

    /** A calendar event left for them to save in the calendar app — with its time filled in if they said one. */
    fun eventOpened(title: String, at: LocalDateTime?, now: LocalDateTime, pt: Boolean): String = when {
        at == null && pt -> "Abrindo sua agenda pra adicionar \"$title\". Escolhe o horário e salva."
        at == null -> "Opening your calendar to add \"$title\" — set the time and save."
        pt -> "Abrindo sua agenda com \"$title\" ${whenSaid(at, Repeat.NONE, now, true)}. É só salvar."
        else -> "Opening your calendar with \"$title\" ${whenSaid(at, Repeat.NONE, now, false)} — just save it."
    }

    /**
     * When something happens, as she'd say it: "in 20 minutes", "today at 5:00 PM", "tomorrow at
     * 9:00 AM", "on Friday at 9:00 AM", "every weekday at 7:30 AM" — or "daqui a 20 minutos",
     * "hoje às 17:00", "amanhã às 9:00", "na sexta às 9:00", "de segunda a sexta às 7:30".
     */
    fun whenSaid(at: LocalDateTime, repeat: Repeat, now: LocalDateTime, pt: Boolean): String {
        val time = timeSaid(at.toLocalTime(), pt)
        return when (repeat) {
            Repeat.DAILY -> if (pt) "todo dia $time" else "every day $time"
            Repeat.WEEKDAYS -> if (pt) "de segunda a sexta $time" else "every weekday $time"
            Repeat.WEEKLY -> if (pt) "${if (weekend(at.dayOfWeek)) "todo" else "toda"} ${dayName(at.dayOfWeek, true)} $time"
                else "every ${dayName(at.dayOfWeek, false)} $time"
            Repeat.MONTHLY -> if (pt) "todo dia ${at.dayOfMonth} $time" else "every month on the ${ordinal(at.dayOfMonth)} $time"
            Repeat.NONE -> {
                val minutes = (Duration.between(now, at).seconds + 59) / 60
                val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
                when {
                    minutes in 1..59 -> when {
                        pt && minutes == 1L -> "daqui a um minuto"
                        pt -> "daqui a $minutes minutos"
                        minutes == 1L -> "in a minute"
                        else -> "in $minutes minutes"
                    }
                    days == 0L -> if (pt) "hoje $time" else "today $time"
                    days == 1L -> if (pt) "amanhã $time" else "tomorrow $time"
                    days in 2..6 -> if (pt) "${if (weekend(at.dayOfWeek)) "no" else "na"} ${dayName(at.dayOfWeek, true)} $time"
                        else "on ${dayName(at.dayOfWeek, false)} $time"
                    else -> {
                        val other = at.year != now.year
                        if (pt) "em ${at.dayOfMonth} de ${MONTHS_PT[at.monthValue - 1]}${if (other) " de ${at.year}" else ""} $time"
                        else "on ${at.month.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }} ${at.dayOfMonth}${if (other) ", ${at.year}" else ""} $time"
                    }
                }
            }
        }
    }

    /** "at 9:00 AM"; "às 9:00", "à 1:30", "ao meio-dia", "à meia-noite". */
    private fun timeSaid(t: LocalTime, pt: Boolean): String {
        if (!pt) return "at " + t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return when {
            t == LocalTime.NOON -> "ao meio-dia"
            t == LocalTime.MIDNIGHT -> "à meia-noite"
            t.hour <= 1 -> "à " + t.format(DateTimeFormatter.ofPattern("H:mm"))
            else -> "às " + t.format(DateTimeFormatter.ofPattern("H:mm"))
        }
    }

    private fun weekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

    private fun dayName(d: DayOfWeek, pt: Boolean): String =
        if (pt) DAYS_PT[d.value - 1] else d.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    // Read out at most this many; the rest are counted.
    private const val MAX_LISTED = 5
    private val OK_EN = listOf("Got it.", "Done.", "You got it.", "Consider it done.")
    private val OK_PT = listOf("Combinado.", "Pode deixar.", "Feito.", "Anotado.")
    private val ALERT_EN = listOf("Hey, a reminder: %s.", "Just reminding you: %s.", "Heads up: %s.")
    private val ALERT_PT = listOf("Ei, lembrete: %s.", "Passando pra te lembrar: %s.", "Lembrete pra você: %s.")
    private val DAYS_PT = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")
    private val MONTHS_PT = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
        "setembro", "outubro", "novembro", "dezembro")
}
