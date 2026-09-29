package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** How she says when a reminder goes off, in both languages — on a Tuesday, at 2 in the afternoon. */
class ReminderTextTest {

    private val now = LocalDateTime.of(2026, 9, 29, 14, 0, 30)

    private fun en(at: LocalDateTime, repeat: Repeat = Repeat.NONE) = ReminderText.whenSaid(at, repeat, now, pt = false)
    private fun pt(at: LocalDateTime, repeat: Repeat = Repeat.NONE) = ReminderText.whenSaid(at, repeat, now, pt = true)

    @Test
    fun oneOffs() {
        val soon = LocalDateTime.of(2026, 9, 29, 14, 20)
        assertEquals("in 20 minutes", en(soon))
        assertEquals("daqui a 20 minutos", pt(soon))
        assertEquals("today at 5:00 PM", en(LocalDateTime.of(2026, 9, 29, 17, 0)))
        assertEquals("hoje às 17:00", pt(LocalDateTime.of(2026, 9, 29, 17, 0)))
        assertEquals("tomorrow at 9:00 AM", en(LocalDateTime.of(2026, 9, 30, 9, 0)))
        assertEquals("amanhã às 9:00", pt(LocalDateTime.of(2026, 9, 30, 9, 0)))
        assertEquals("amanhã ao meio-dia", pt(LocalDateTime.of(2026, 9, 30, 12, 0)))
        assertEquals("amanhã à 1:30", pt(LocalDateTime.of(2026, 9, 30, 1, 30)))
        assertEquals("on Friday at 3:00 PM", en(LocalDateTime.of(2026, 10, 2, 15, 0)))
        assertEquals("na sexta às 15:00", pt(LocalDateTime.of(2026, 10, 2, 15, 0)))
        assertEquals("no sábado às 10:00", pt(LocalDateTime.of(2026, 10, 3, 10, 0)))
        assertEquals("on October 10 at 9:00 AM", en(LocalDateTime.of(2026, 10, 10, 9, 0)))
        assertEquals("em 10 de outubro às 9:00", pt(LocalDateTime.of(2026, 10, 10, 9, 0)))
        assertEquals("em 5 de janeiro de 2027 às 9:00", pt(LocalDateTime.of(2027, 1, 5, 9, 0)))
        assertEquals("on January 5, 2027 at 9:00 AM", en(LocalDateTime.of(2027, 1, 5, 9, 0)))
    }

    @Test
    fun repeats() {
        val monday = LocalDateTime.of(2026, 10, 5, 8, 0)
        assertEquals("every day at 8:00 AM", en(monday, Repeat.DAILY))
        assertEquals("todo dia às 8:00", pt(monday, Repeat.DAILY))
        assertEquals("de segunda a sexta às 8:00", pt(monday, Repeat.WEEKDAYS))
        assertEquals("every Monday at 8:00 AM", en(monday, Repeat.WEEKLY))
        assertEquals("toda segunda às 8:00", pt(monday, Repeat.WEEKLY))
        assertEquals("todo domingo às 8:00", pt(LocalDateTime.of(2026, 10, 4, 8, 0), Repeat.WEEKLY))
        assertEquals("every month on the 10th at 9:00 AM", en(LocalDateTime.of(2026, 10, 10, 9, 0), Repeat.MONTHLY))
        assertEquals("todo dia 10 às 9:00", pt(LocalDateTime.of(2026, 10, 10, 9, 0), Repeat.MONTHLY))
    }

    @Test
    fun confirmingAndListing() {
        val set = ReminderText.set("ligar pro dentista", LocalDateTime.of(2026, 9, 30, 9, 0), Repeat.NONE, now, pt = true)
        assertTrue(set, set.endsWith(" Eu te lembro amanhã às 9:00: ligar pro dentista."))
        val setEn = ReminderText.set("call the dentist", LocalDateTime.of(2026, 9, 30, 9, 0), Repeat.NONE, now, pt = false)
        assertTrue(setEn, setEn.endsWith(" I'll remind you tomorrow at 9:00 AM: call the dentist."))

        val local = { ms: Long -> LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC) }
        fun ms(at: LocalDateTime) = at.toEpochSecond(ZoneOffset.UTC) * 1000
        val reminders = listOf(
            Reminder(1, "ligar pra mãe", ms(LocalDateTime.of(2026, 9, 29, 17, 0)), created = 0),
            Reminder(2, "tomar o remédio", ms(LocalDateTime.of(2026, 9, 29, 22, 0)), Repeat.DAILY, created = 0),
        )
        assertEquals("Você tem 2 lembretes: ligar pra mãe, hoje às 17:00; tomar o remédio, todo dia às 22:00.",
            ReminderText.list(reminders, local, now, pt = true))
        assertEquals("You have one reminder: ligar pra mãe, today at 5:00 PM.", ReminderText.list(reminders.take(1), local, now, pt = false))
        assertEquals("Você não tem nenhum lembrete.", ReminderText.list(emptyList(), local, now, pt = true))
    }
}
