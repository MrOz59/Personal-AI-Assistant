package com.naomi.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Reminders as they're said, in English or Portuguese: what to be reminded of, when, and how
 * often — "remind me to call mom tomorrow at 9", "me lembra daqui a 20 minutos de tirar o bolo
 * do forno", "remind me every weekday at 7:30 to take my pills". It also tells a new reminder
 * apart from asking what's set ("what are my reminders?") and from cancelling one. The clock is
 * passed in ([now]), and nothing here touches Android, so it's all unit-tested.
 */
object ReminderParser {

    /** When a reminder goes off: its first time, and how often it comes back. */
    data class When(val at: LocalDateTime, val repeat: Repeat = Repeat.NONE)

    /** A request for a new reminder: what to remind them of (blank if they didn't say), and when (null if they didn't say). */
    data class Request(val text: String, val time: When?, val portuguese: Boolean)

    /** Whether [text] asks for a new reminder. */
    fun isRequest(text: String): Boolean = CREATE.containsMatchIn(text) && !isCancel(text) && !isList(text)

    /** Whether [text] asks what reminders are set. */
    fun isList(text: String): Boolean = LIST.containsMatchIn(text) && !CREATE_VERB.containsMatchIn(text) && !isCancel(text)

    /** Whether [text] asks to cancel a reminder. */
    fun isCancel(text: String): Boolean = CANCEL.containsMatchIn(text)

    /**
     * Which reminder a cancel request means, in its own words ("cancel the dentist one" →
     * "dentist"): blank for "that one" / the last one set, "all" for all of them.
     */
    fun cancelTarget(text: String): String {
        val words = clean(text).replace(CANCEL_VERB, " ").replace(REMINDER_WORD, " ").replace(CANCEL_FILLER, " ")
        val target = trimConnectors(words.replace(SPACES, " ").trim().trim('.', '!', '?', ':', ' '))
        return if (ALL.matches(target)) "all" else target
    }

    /** The reminder [text] asks for, or null if it doesn't ask for one. */
    fun parse(text: String, now: LocalDateTime): Request? {
        if (!isRequest(text)) return null
        val scan = scan(text)
        // Everything up to and including the request itself ("hey Naomi, can you remind me") goes.
        val rest = CREATE.find(scan.rest)?.let { scan.rest.substring(it.range.last + 1) } ?: scan.rest
        return Request(tidy(rest), scan.resolve(now), isPortuguese(text))
    }

    /** When [text] says a reminder should go off — "tomorrow at 9", "daqui a meia hora" — or null if it names no time. */
    fun parseWhen(text: String, now: LocalDateTime): When? = scan(text).resolve(now)

    /** [text] with its times, days and repeats taken out: "dentist tomorrow at 3pm" → "dentist". */
    fun withoutWhen(text: String): String = tidy(scan(text).rest)

    /**
     * A time as a model wrote it — "2026-09-30 09:00", ISO "2026-09-30T09:00:00", or just "09:00" —
     * moved on a day if it's today's and already gone. Null if it isn't one, or it's in the past.
     */
    fun modelTime(text: String?, now: LocalDateTime): LocalDateTime? {
        val t = text?.trim().orEmpty()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})[T ](\\d{1,2}):(\\d{2})").find(t)?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            val at = runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) }.getOrNull() ?: return null
            if (at.isAfter(now)) return at
            // "At 9" read as today's 9 when it's gone 9: the next one.
            return at.plusDays(1).takeIf { it.isAfter(now) && at.toLocalDate() == now.toLocalDate() }
        }
        Regex("^(\\d{1,2}):(\\d{2})$").find(t)?.let { m ->
            val time = runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() ?: return null
            val today = now.toLocalDate().atTime(time)
            return if (today.isAfter(now)) today else today.plusDays(1)
        }
        return null
    }

    /** Whether [text] reads as Portuguese rather than English, by the everyday words in it. */
    fun isPortuguese(text: String): Boolean {
        val words = Regex("[\\p{L}']+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        return words.count { it in PT_WORDS } > words.count { it in EN_WORDS }
    }

    // ── Reading the time ───────────────────────────────────────────────────────

    private enum class Part(val default: LocalTime) {
        MORNING(LocalTime.of(9, 0)), AFTERNOON(LocalTime.of(15, 0)), EVENING(LocalTime.of(19, 0)),
        NIGHT(LocalTime.of(20, 0)), SMALL_HOURS(LocalTime.of(3, 0))
    }

    /** A clock time as said: [explicit] when am/pm, a 24-hour time or the part of the day settles it. */
    private data class Clock(val hour: Int, val minute: Int, val explicit: Boolean)

    /** What a sentence says about time, and what's left of it once those words are cut out ([rest]). */
    private class Scan(var rest: String) {
        var inMinutes: Long? = null
        var inDays: Long? = null
        var dayOffset: Long? = null
        var weekday: DayOfWeek? = null
        var monthDay: Int? = null
        var month: Int? = null
        var part: Part? = null
        var clock: Clock? = null
        var repeat = Repeat.NONE

        /** Cuts [pattern]'s first match out of [rest], if there is one, after handing it to [onMatch]. */
        fun take(pattern: Regex, onMatch: (MatchResult) -> Unit) {
            val m = pattern.find(rest) ?: return
            onMatch(m)
            rest = rest.replaceRange(m.range, " ".repeat(m.value.length))
        }

        fun resolve(now: LocalDateTime): When? {
            val start = now.withSecond(0).withNano(0)
            inMinutes?.let { return When(start.plusMinutes(it), repeat) }
            val today = now.toLocalDate()
            val date: LocalDate? = when {
                inDays != null -> today.plusDays(inDays!!)
                dayOffset != null -> today.plusDays(dayOffset!!)
                monthDay != null -> dayOfMonth(monthDay!!, month, today)
                weekday != null -> {
                    // A repeat on that day may start today; "on Friday" said on a Friday means the next one.
                    if (repeat == Repeat.WEEKLY && today.dayOfWeek == weekday) today else today.with(TemporalAdjusters.next(weekday!!))
                }
                else -> null
            }
            val c = clock
            val time: LocalTime = when {
                c != null && c.explicit -> LocalTime.of(c.hour % 24, c.minute)
                c != null -> ambiguous(c, date, now)
                part != null -> part!!.default
                inDays != null -> start.toLocalTime()
                // "Tomorrow", "on Friday", "every Monday": in the morning, unless they say otherwise.
                date != null && (date != today || repeat != Repeat.NONE) -> DEFAULT_TIME
                else -> return null
            }
            var at = (date ?: today).atTime(time)
            if (!at.isAfter(now)) {
                // A time of day that's gone today is tomorrow's; a one-off on a day that's gone is no time at all.
                at = when {
                    date == null -> at.plusDays(1)
                    repeat == Repeat.NONE -> return null
                    else -> repeat.after(at)
                }
            }
            if (repeat == Repeat.WEEKDAYS) while (at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) at = at.plusDays(1)
            return When(at, repeat)
        }

        /**
         * "At 5" with no am/pm: the part of the day said, else the 5 o'clock that makes sense — 1 to
         * 6 is taken for the afternoon (nobody sets a 5am reminder by accident), 7 to 11 for the
         * morning — unless, for a one-off today, that's gone and the other one isn't.
         */
        private fun ambiguous(c: Clock, date: LocalDate?, now: LocalDateTime): LocalTime {
            part?.let { return LocalTime.of(inPart(c.hour, it), c.minute) }
            if (c.hour == 12) return LocalTime.of(12, c.minute)
            val am = LocalTime.of(c.hour % 12, c.minute)
            val pm = LocalTime.of(c.hour % 12 + 12, c.minute)
            val preferred = if (c.hour <= 6) pm else am
            // A routine's hour doesn't flip to the evening because this morning's is gone.
            if (repeat != Repeat.NONE || (date != null && date != now.toLocalDate())) return preferred
            return listOf(preferred, if (preferred == am) pm else am).firstOrNull { now.toLocalDate().atTime(it).isAfter(now) } ?: preferred
        }

        private fun dayOfMonth(day: Int, month: Int?, today: LocalDate): LocalDate? {
            fun on(date: LocalDate) = date.withDayOfMonth(minOf(day, date.lengthOfMonth()))
            if (month != null) {
                val thisYear = on(LocalDate.of(today.year, month, 1))
                return if (thisYear.isBefore(today)) on(LocalDate.of(today.year + 1, month, 1)) else thisYear
            }
            val thisMonth = on(today)
            return if (thisMonth.isBefore(today)) on(today.plusMonths(1).withDayOfMonth(1)) else thisMonth
        }
    }

    /** Reads the times, days and repeats out of [text], leaving the rest. */
    private fun scan(text: String): Scan = Scan(clean(text)).apply {
        // Relative: "in 20 minutes", "daqui a uma hora e meia", "in 2 days".
        take(IN_HALF_HOUR) { inMinutes = 30 }
        if (inMinutes == null) take(IN_AMOUNT) { m ->
            val n = number(m.groupValues[1])?.toLong() ?: return@take
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val extra = if (m.groupValues[3].isNotEmpty()) 30L else number(m.groupValues[4])?.toLong() ?: 0L
            when {
                unit.startsWith("min") -> inMinutes = n
                unit.startsWith("h") -> inMinutes = n * 60 + extra
                unit.startsWith("d") -> inDays = n
                else -> inDays = n * 7 // weeks, semanas
            }
        }
        // Repeats, before the days and times inside them: "every Monday", "todo dia 10", "de segunda a sexta".
        take(EVERY_MONTH) { m ->
            repeat = Repeat.MONTHLY
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let { monthDay = it.toInt() }
        }
        if (repeat == Repeat.NONE) take(EVERY_WEEKDAY_OFF) { repeat = Repeat.WEEKDAYS }
        if (repeat == Repeat.NONE) take(EVERY_DAY_OF_WEEK) { m ->
            repeat = Repeat.WEEKLY
            weekday = dayOfWeek(first(m))
        }
        if (repeat == Repeat.NONE) take(EVERY_PART) { m -> repeat = Repeat.DAILY; part = partOf(first(m)) }
        if (repeat == Repeat.NONE) take(EVERY_DAY) { repeat = Repeat.DAILY }
        if (repeat == Repeat.NONE) take(EVERY_WEEK) { repeat = Repeat.WEEKLY }

        // Clock times, before the days: "às 5 da tarde" holds a part of the day.
        take(NOON_MIDNIGHT) { m ->
            val midnight = m.groupValues[1].lowercase(Locale.ROOT).let { it.startsWith("midn") || it.startsWith("meia") }
            clock = Clock(if (midnight) 0 else 12, if (m.groupValues[2].isNotEmpty()) 30 else 0, true)
        }
        if (clock == null) take(CLOCK_EN) { m -> clock = clock(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].ifEmpty { m.groupValues[5] }) }
        if (clock == null) take(CLOCK_PT) { m ->
            val g = m.groupValues
            val minute = when {
                g[4].isNotEmpty() -> g[4]
                g[5].equals("meia", ignoreCase = true) -> "30"
                else -> g[5].filter { it.isDigit() }
            }
            clock = clock(g[1].ifEmpty { g[2] }.ifEmpty { g[3] }, minute, "", g[6])
        }
        if (clock == null) take(CLOCK_H) { m -> clock = clock(m.groupValues[1], m.groupValues[2], "", m.groupValues[3]) }
        if (clock == null) take(CLOCK_MERIDIEM) { m -> clock = clock(m.groupValues[1], m.groupValues[2], m.groupValues[3], "") }
        if (clock == null) take(CLOCK_24) { m -> clock = clock(m.groupValues[1], m.groupValues[2], "", "") }

        // Dates: "on the 10th", "October 3", "dia 10", "3 de outubro".
        if (monthDay == null) take(DATE_MONTH_FIRST) { m -> monthDay = m.groupValues[2].toInt(); month = monthOf(m.groupValues[1]) }
        if (monthDay == null) take(DATE_DAY_FIRST) { m ->
            val g = m.groupValues
            monthDay = listOf(g[1], g[3], g[5]).first { it.isNotEmpty() }.toInt()
            month = listOf(g[2], g[4], g[6]).firstOrNull { it.isNotEmpty() }?.let(::monthOf)
        }
        monthDay?.let { if (it !in 1..31) { monthDay = null; month = null } }

        // Days: "tomorrow morning", "depois de amanhã", "na sexta", "tonight".
        take(DAY_AFTER_TOMORROW) { dayOffset = 2 }
        if (dayOffset == null) take(TOMORROW) { m -> dayOffset = 1; firstOrNull(m)?.let { part = part ?: partOf(it) } }
        if (dayOffset == null) take(TONIGHT) { dayOffset = 0; part = part ?: Part.NIGHT }
        if (dayOffset == null) take(TODAY) { m -> dayOffset = 0; firstOrNull(m)?.let { part = part ?: partOf(it) } }
        if (weekday == null && monthDay == null) take(ON_WEEKDAY) { m ->
            weekday = dayOfWeek(m.groupValues[1].ifEmpty { m.groupValues[3] })
            m.groupValues[2].ifEmpty { m.groupValues[4] }.takeIf { it.isNotEmpty() }?.let { part = part ?: partOf(it) }
        }
        if (part == null) take(PART_ALONE) { m -> part = partOf(first(m)) }
    }

    /** A clock time from its pieces: the hour and minutes said, am/pm, and the part of the day said with it ("da tarde"). */
    private fun clock(hourSaid: String, minuteSaid: String, meridiem: String, partSaid: String): Clock? {
        val hour = number(hourSaid) ?: return null
        val minute = minuteSaid.toIntOrNull() ?: 0
        if (hour > 23 || minute > 59) return null
        val m = meridiem.lowercase(Locale.ROOT).filter { it == 'a' || it == 'p' }
        return when {
            m == "p" -> Clock(if (hour < 12) hour + 12 else hour, minute, true)
            m == "a" -> Clock(if (hour == 12) 0 else hour, minute, true)
            hour == 0 || hour > 12 -> Clock(hour, minute, true)
            partSaid.isNotBlank() -> Clock(inPart(hour, partOf(partSaid)), minute, true)
            // A 24-hour time written with its leading zero ("07:30", "09h15") is as said.
            minuteSaid.isNotEmpty() && hourSaid.startsWith("0") -> Clock(hour, minute, true)
            else -> Clock(hour, minute, false)
        }
    }

    /** An hour on the 12-hour clock in a part of the day, on the 24-hour clock: 3 in the afternoon is 15; 12 at night is 0. */
    private fun inPart(hour: Int, part: Part): Int = when (part) {
        Part.MORNING, Part.SMALL_HOURS -> hour % 12
        Part.NIGHT -> if (hour == 12) 0 else hour % 12 + 12
        else -> hour % 12 + 12
    }

    private fun first(m: MatchResult): String = firstOrNull(m).orEmpty()
    private fun firstOrNull(m: MatchResult): String? = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }

    private fun partOf(word: String): Part = when (word.lowercase(Locale.ROOT).trim()) {
        "morning", "manhã", "manha", "manhãs", "manhas", "cedo" -> Part.MORNING
        "afternoon", "tarde", "tardes" -> Part.AFTERNOON
        "evening" -> Part.EVENING
        "madrugada" -> Part.SMALL_HOURS
        else -> Part.NIGHT // night, noite
    }

    private fun dayOfWeek(word: String): DayOfWeek {
        val w = word.lowercase(Locale.ROOT)
        return when {
            w.startsWith("mon") || w.startsWith("seg") -> DayOfWeek.MONDAY
            w.startsWith("tue") || w.startsWith("ter") -> DayOfWeek.TUESDAY
            w.startsWith("wed") || w.startsWith("qua") -> DayOfWeek.WEDNESDAY
            w.startsWith("thu") || w.startsWith("qui") -> DayOfWeek.THURSDAY
            w.startsWith("fri") || w.startsWith("sex") -> DayOfWeek.FRIDAY
            w.startsWith("sat") || w.startsWith("sáb") || w.startsWith("sab") -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }

    private fun monthOf(word: String): Int {
        val w = word.lowercase(Locale.ROOT).take(3)
        return MONTHS.indexOfFirst { names -> names.any { it.startsWith(w) } } + 1
    }

    /** A number as digits or as a word ("twenty five", "vinte e cinco", "uma"), or null. */
    private fun number(said: String): Int? {
        val t = said.lowercase(Locale.ROOT).trim()
        t.toIntOrNull()?.let { return it }
        NUMBER_WORDS[t]?.let { return it }
        val parts = t.split(Regex("[\\s-]+")).filter { it.isNotBlank() && it != "e" }
        if (parts.size == 2) {
            val tens = NUMBER_WORDS[parts[0]] ?: return null
            val units = NUMBER_WORDS[parts[1]] ?: return null
            if (tens % 10 == 0 && units in 1..9) return tens + units
        }
        return null
    }

    /** Text as the patterns read it: the same length (so cuts line up), with commas and curly quotes out of the way. */
    private fun clean(text: String): String = text.replace('’', '\'').replace(',', ' ').replace(';', ' ')

    /** What's left of a sentence as the thing to remember: no joining words at its ends, no doubled spaces. */
    private fun tidy(text: String): String = trimConnectors(text.replace(SPACES, " ").trim().trim('.', '!', '?', ':', ' '))

    /** Words that joined the reminder to the rest of the sentence, taken off its ends. */
    private fun trimConnectors(text: String): String {
        var t = text
        while (true) {
            val next = t.replace(LEADING_CONNECTOR, "").replace(TRAILING_CONNECTOR, "").trim()
            if (next == t) return t
            t = next
        }
    }

    // "às 5" has to read the same in unit tests as on the phone: see [wordRegex].
    private fun rx(pattern: String) = wordRegex(pattern)

    private val SPACES = Regex("\\s{2,}")
    private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60,
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "três" to 3, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "quinze" to 15,
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
    )
    private val MONTHS = listOf(
        listOf("january", "janeiro"), listOf("february", "fevereiro"), listOf("march", "março", "marco"), listOf("april", "abril"),
        listOf("may", "maio"), listOf("june", "junho"), listOf("july", "julho"), listOf("august", "agosto"),
        listOf("september", "setembro"), listOf("october", "outubro"), listOf("november", "novembro"), listOf("december", "dezembro"),
    )
    private const val UNIT_WORDS = "one|two|three|four|five|six|seven|eight|nine|um|uma|dois|duas|três|tres|quatro|cinco|seis|sete|oito|nove"
    private const val TENS_WORDS = "twenty|thirty|forty|fifty|vinte|trinta|quarenta|cinquenta"
    private const val HOUR = "\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
        "uma|duas|dois|três|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze"
    private const val AMOUNT = "\\d{1,3}|(?:$TENS_WORDS)(?:[ -]|\\s+e\\s+)(?:$UNIT_WORDS)|an?|um|uma|" +
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|fifty|sixty|" +
        "dois|duas|três|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|quinze|vinte|trinta|quarenta|cinquenta|sessenta"
    private const val WEEKDAY_EN = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val WEEKDAY_PT_F = "segunda|terça|terca|quarta|quinta|sexta"
    private const val WEEKDAY_PT_M = "sábado|sabado|domingo"
    private const val PART_PT = "manhã|manha|tarde|noite"
    private const val MONTH_EN = "january|february|march|april|may|june|july|august|september|october|november|december"
    private const val MONTH_PT = "janeiro|fevereiro|março|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro"
    private const val ORDINAL = "(?:st|nd|rd|th|º)?"

    private val IN_HALF_HOUR = rx("\\b(?:in|within)\\s+half\\s+an\\s+hour\\b|\\b(?:daqui\\s+a|daqui|em|dentro\\s+de)\\s+meia\\s+hora\\b")
    private val IN_AMOUNT = rx("\\b(?:in|within|daqui\\s+a|daqui|em|dentro\\s+de)\\s+($AMOUNT)\\s+" +
        "(minutes?|mins?|hours?|hrs?|days?|weeks?|minutos?|horas?|dias?|semanas?)" +
        "(?:\\s+(?:and|e)\\s+(?:(a\\s+half|meia)|($AMOUNT)\\s+(?:minutes?|mins?|minutos?)))?\\b")

    private val EVERY_MONTH = rx("\\btodo\\s+(?:m[êe]s\\s+(?:no\\s+)?)?dia\\s+(\\d{1,2})\\b|\\bevery\\s+month\\s+on\\s+the\\s+(\\d{1,2})$ORDINAL\\b|" +
        "\\b(?:on\\s+)?the\\s+(\\d{1,2})$ORDINAL\\s+of\\s+every\\s+month\\b|\\bevery\\s+month\\b|\\bmonthly\\b|\\btodo\\s+m[êe]s\\b|\\bmensalmente\\b")
    private val EVERY_WEEKDAY_OFF = rx("\\b(?:every\\s+weekday|on\\s+weekdays|weekdays|every\\s+work\\s*day|" +
        "(?:from\\s+)?monday\\s+(?:to|through)\\s+friday|(?:todos\\s+os\\s+|nos\\s+|em\\s+)?dias\\s+[úu]teis|" +
        "de\\s+segunda\\s+a\\s+sexta(?:-feira)?)\\b")
    private val EVERY_DAY_OF_WEEK = rx("\\bevery\\s+($WEEKDAY_EN)s?\\b|\\bon\\s+($WEEKDAY_EN)s\\b|" +
        "\\b(?:toda|todas\\s+as)\\s+($WEEKDAY_PT_F)(?:s|-feiras?|\\s+feiras?)?\\b|\\b(?:às|as|nas)\\s+($WEEKDAY_PT_F)(?:s|-feiras)\\b|" +
        "\\b(?:todo|todos\\s+os)\\s+($WEEKDAY_PT_M)s?\\b|\\b(?:aos|nos)\\s+($WEEKDAY_PT_M)s\\b")
    private val EVERY_PART = rx("\\bevery\\s+(morning|afternoon|evening|night)\\b|\\b(?:toda|todas\\s+as)\\s+(manh[ãa]s?|tardes?|noites?)\\b")
    private val EVERY_DAY = rx("\\b(?:every\\s*day|daily|each\\s+day|todo\\s+(?:santo\\s+)?dia|todos\\s+os\\s+dias|diariamente|cada\\s+dia)\\b")
    private val EVERY_WEEK = rx("\\b(?:every\\s+week|weekly|once\\s+a\\s+week|toda\\s+semana|todas\\s+as\\s+semanas|semanalmente|uma\\s+vez\\s+por\\s+semana)\\b")

    private val NOON_MIDNIGHT = rx("(?:\\b(?:at|ao|à|a)\\s+)?\\b(noon|midday|midnight|meio[- ]dia|meia[- ]noite)(\\s+e\\s+meia)?\\b")
    // "at 5", "at 5:30 pm", "at seven in the evening", "at 11 at night".
    private val CLOCK_EN = rx("\\b(?:at|by|around)\\s+($HOUR)(?:(?::|\\.|\\s)(\\d{2}))?\\s*(a\\.?\\s?m\\b\\.?|p\\.?\\s?m\\b\\.?|o'?clock)?" +
        "(?:\\s+(?:in\\s+the\\s+)?(morning|afternoon|evening)|\\s+at\\s+(night))?\\b")
    // "às 5", "às 17h30", "às 5 e meia da tarde", "à uma", "as 9" — a bare "as" only with digits ("as duas coisas").
    private val CLOCK_PT = rx("(?:\\b(?:às|ás|pelas|lá\\s+pelas|a\\s+partir\\s+das)\\s+($HOUR)|\\bà\\s+(uma|1)|\\bas\\s+(\\d{1,2}))" +
        "(?:\\s*(?:h|:|horas?)\\s*(\\d{2})?)?(?:\\s+e\\s+(meia|\\d{1,2}(?:\\s+minutos?)?))?" +
        "(?:\\s+(?:da|de)\\s+(manhã|manha|tarde|noite|madrugada))?\\b")
    private val CLOCK_H = rx("\\b(\\d{1,2})h(\\d{2})?\\b(?:\\s+(?:da|de)\\s+(manhã|manha|tarde|noite|madrugada)\\b)?")
    private val CLOCK_MERIDIEM = rx("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?\\s?m\\b\\.?|p\\.?\\s?m\\b\\.?)")
    private val CLOCK_24 = rx("\\b(\\d{1,2}):(\\d{2})\\b")

    private val DATE_MONTH_FIRST = rx("\\b(?:on\\s+)?($MONTH_EN)\\s+(\\d{1,2})$ORDINAL\\b")
    private val DATE_DAY_FIRST = rx("\\b(?:on\\s+)?the\\s+(\\d{1,2})$ORDINAL(?:\\s+of\\s+($MONTH_EN))?\\b|" +
        "\\b(?:no\\s+|em\\s+)?(?:dia\\s+(\\d{1,2})(?:\\s+de\\s+($MONTH_PT))?|(\\d{1,2})\\s+de\\s+($MONTH_PT))\\b")

    private val DAY_AFTER_TOMORROW = rx("\\b(?:the\\s+)?day\\s+after\\s+tomorrow\\b|\\bdepois\\s+de\\s+amanh[ãa]\\b")
    private val TOMORROW = rx("\\btomorrow(?:\\s+(morning|afternoon|evening|night))?\\b|" +
        "\\bamanh[ãa](?:\\s+(?:de|à|a|pela)\\s+($PART_PT)|\\s+(cedo))?\\b")
    private val TONIGHT = rx("\\btonight\\b|\\bhoje\\s+(?:à|a|de)\\s+noite\\b")
    private val TODAY = rx("\\b(?:later\\s+)?today(?:\\s+(?:in\\s+the\\s+)?(morning|afternoon|evening))?\\b|" +
        "\\bthis\\s+(morning|afternoon|evening)\\b|\\bhoje(?:\\s+(?:de|à|a|pela)\\s+(manhã|manha|tarde))?\\b")
    // "segunda" alone is as likely "second" as Monday, so it needs a "na" or "-feira"; the other days may stand alone.
    private val ON_WEEKDAY = rx("\\b(?:on\\s+|next\\s+|this\\s+|on\\s+next\\s+)?($WEEKDAY_EN)\\b(?:\\s+(morning|afternoon|evening|night))?|" +
        "(?:\\b(?:na|no|nesta|neste|nessa|nesse|esta|este|essa|esse|pr[óo]xim[ao])\\s+(?=$WEEKDAY_PT_F|$WEEKDAY_PT_M)|\\b(?=segunda(?:-|\\s+)feira|$WEEKDAY_PT_M|terça|terca|quarta|quinta|sexta))" +
        "($WEEKDAY_PT_F|$WEEKDAY_PT_M)(?:-feira|\\s+feira)?(?:\\s+que\\s+vem)?(?:\\s+(?:de|à|a|pela)\\s+($PART_PT))?\\b")
    private val PART_ALONE = rx("\\bin\\s+the\\s+(morning|afternoon|evening)\\b|\\bat\\s+(night)\\b|\\b(?:de|à|pela)\\s+($PART_PT)\\b")

    private const val CREATE_WORDS = "\\bremind\\s+me\\b|\\b(?:set|create|add|make|put)\\s+(?:a|an|me\\s+a)?\\s*reminder\\b|" +
        "\\bme\\s+lembr(?:a|e|ar)\\b|\\blembr(?:a|e)-me\\b|" +
        "\\b(?:cria|crie|criar|coloca|coloque|colocar|p[õo]e|ponha|adiciona|adicione|marca|marque|faz|faça|faca|bota|anota|anote)\\s+(?:um\\s+|uma\\s+)?lembrete\\b"
    private val CREATE_VERB = rx(CREATE_WORDS)
    /** A request for a reminder, from the start of the sentence: everything before the request is greeting and politeness. */
    private val CREATE = rx("^.*?(?:$CREATE_WORDS|\\breminder\\s+(?=to\\b|for\\b|about\\b|that\\b)|" +
        "^\\W*(?:(?:hey|ok|okay)\\s+)?(?:naomi\\W+)?(?:please\\s+)?remember\\s+(?=to\\b)|\\bdon't\\s+let\\s+me\\s+forget\\b|" +
        "\\blembrete\\s+(?=de\\b|para\\b|pra\\b|pro\\b)|\\bn[ãa]o\\s+(?:me\\s+)?deix[ae]\\s+(?:eu\\s+)?esquecer\\b)")
    private val LIST = rx("\\b(?:what|which|any|list|read|tell\\s+me|show\\s+me|do\\s+i\\s+have|have\\s+i\\s+got)\\b.*\\breminders?\\b|\\bmy\\s+reminders\\b|" +
        "\\b(?:quais|qual|que|lista|listar|liste|l[êe]|ler|leia|fala|diz|mostra|mostre|tenho|tem\\s+algum)\\b.*\\blembretes?\\b|\\bmeus\\s+lembretes\\b")
    private val CANCEL_VERB = rx("\\b(?:cancel|delete|remove|clear|drop|forget|turn\\s+off|" +
        "cancela|cancele|cancelar|apaga|apague|apagar|remova|remover|tira|tire|tirar|exclui|exclua|excluir|" +
        "deleta|deletar|esquece|esqueça|esqueca|desmarca|desmarque)\\b")
    private val REMINDER_WORD = rx("\\b(?:reminders?|lembretes?)\\b")
    private val CANCEL = rx("(?:${CANCEL_VERB.pattern}).*(?:${REMINDER_WORD.pattern})")
    private val CANCEL_FILLER = rx("\\b(?:hey|ok|okay|naomi|please|can|could|would|will|you|the|my|a|an|that|this|one|last|just|" +
        "i\\s+(?:just\\s+)?set|of\\s+them|por\\s+favor|pode|podia|poderia|você|voce|o|os|as|um|uma|meu|meus|minha|minhas|" +
        "esse|este|essa|esta|isso|[úu]ltimo|que\\s+eu\\s+(?:pedi|criei|fiz))\\b")
    private val ALL = rx("^(?:all|every(?:thing|one)?|todos|todas|tudo)$")
    private val LEADING_CONNECTOR = rx("^(?:to|that|about|of|for|and|de|da|do|que|para|pra|pro|sobre|e|:)\\s+")
    private val TRAILING_CONNECTOR = rx("\\s+(?:to|at|on|in|by|the|for|and|de|do|da|às|as|no|na|em|para|pra|e|please|por\\s+favor|thanks|obrigad[oa])$")

    private val PT_WORDS = setOf("lembra", "lembre", "lembrar", "lembrete", "lembretes", "amanhã", "amanha", "hoje", "daqui",
        "às", "minutos", "minuto", "horas", "hora", "segunda", "terça", "terca", "quarta", "quinta", "sexta", "sábado",
        "sabado", "domingo", "todo", "toda", "todos", "todas", "pra", "pro", "para", "de", "do", "da", "que", "você",
        "voce", "meu", "minha", "meus", "minhas", "cancela", "cancelar", "apaga", "apagar", "quais", "tenho", "noite",
        "manhã", "manha", "tarde", "dia", "dias", "semana", "meia", "não", "nao", "com", "uma", "um", "o", "os", "e",
        "no", "na", "eu", "te", "comprar", "ligar", "pagar", "tomar", "fazer")
    private val EN_WORDS = setOf("remind", "reminder", "reminders", "tomorrow", "today", "tonight", "minutes", "minute",
        "hours", "hour", "at", "to", "the", "my", "every", "cancel", "what", "are", "in", "on", "of", "and", "an", "call",
        "buy", "is", "for", "that", "about", "day", "week", "i", "you", "take", "pay", "do", "have")
}
