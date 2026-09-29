package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Reading reminders out of what was said, in English and Portuguese — on a Tuesday, at 2 in the afternoon. */
class ReminderParserTest {

    private val now = LocalDateTime.of(2026, 9, 29, 14, 0) // a Tuesday

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute)

    private fun check(said: String, text: String, at: LocalDateTime?, repeat: Repeat = Repeat.NONE, portuguese: Boolean = false) {
        val r = ReminderParser.parse(said, now)
        requireNotNull(r) { "not read as a reminder: \"$said\"" }
        assertEquals("what, in \"$said\"", text, r.text)
        assertEquals("when, in \"$said\"", at, r.time?.at)
        if (at != null) assertEquals("repeat, in \"$said\"", repeat, r.time?.repeat)
        assertEquals("language of \"$said\"", portuguese, r.portuguese)
    }

    @Test
    fun englishReminders() {
        check("remind me to call mom tomorrow at 9", "call mom", at(9, 30, 9))
        check("Remind me at 5 to take the pills", "take the pills", at(9, 29, 17))
        check("remind me in 20 minutes to check the oven", "check the oven", at(9, 29, 14, 20))
        check("hey Naomi, can you remind me to water the plants in an hour and a half", "water the plants", at(9, 29, 15, 30))
        check("remind me on Friday to pay rent", "pay rent", at(10, 2, 9))
        check("remind me tonight to lock the car", "lock the car", at(9, 29, 20))
        check("remind me tomorrow afternoon to call the bank", "call the bank", at(9, 30, 15))
        check("remind me at 10:30 pm to set out my clothes", "set out my clothes", at(9, 29, 22, 30))
        check("remind me on the 10th to pay the internet bill", "pay the internet bill", at(10, 10, 9))
        check("remember to buy bread tomorrow morning", "buy bread", at(9, 30, 9))
        check("set a reminder for 7pm to feed the cat", "feed the cat", at(9, 29, 19))
    }

    @Test
    fun anHourWithoutAmOrPmIsTheNextOneThatMakesSense() {
        // 8 in the morning is gone at 2 pm: 8 tonight.
        check("remind me at 8 to call Ana", "call Ana", at(9, 29, 20))
        // Tomorrow, 8 means the morning; 3 means the afternoon.
        check("remind me tomorrow at 8 to go running", "go running", at(9, 30, 8))
        check("remind me tomorrow at 3 to call Ana", "call Ana", at(9, 30, 15))
        // Noon's gone today: tomorrow's.
        check("remind me at noon to have lunch", "have lunch", at(9, 30, 12))
    }

    @Test
    fun repeats() {
        check("remind me every weekday at 7:30 am to take my pills", "take my pills", at(9, 30, 7, 30), Repeat.WEEKDAYS)
        check("remind me every Monday at 9 to take out the trash", "take out the trash", at(10, 5, 9), Repeat.WEEKLY)
        check("remind me every day at 10pm to plug in my phone", "plug in my phone", at(9, 29, 22), Repeat.DAILY)
        check("remind me every morning to drink water", "drink water", at(9, 30, 9), Repeat.DAILY)
    }

    @Test
    fun portugueseReminders() {
        check("me lembra amanhã às 9 de ligar pro dentista", "ligar pro dentista", at(9, 30, 9), portuguese = true)
        check("me lembra daqui a 20 minutos de tirar o bolo do forno", "tirar o bolo do forno", at(9, 29, 14, 20), portuguese = true)
        check("me lembra daqui a meia hora de desligar o forno", "desligar o forno", at(9, 29, 14, 30), portuguese = true)
        check("me lembra de pagar a conta de luz na sexta às 3 da tarde", "pagar a conta de luz", at(10, 2, 15), portuguese = true)
        check("me lembra às 17h30 de buscar as crianças", "buscar as crianças", at(9, 29, 17, 30), portuguese = true)
        check("cria um lembrete pra amanhã de manhã: reunião com o João", "reunião com o João", at(9, 30, 9), portuguese = true)
        check("me lembra depois de amanhã à noite de ver o jogo", "ver o jogo", at(10, 1, 20), portuguese = true)
        check("me lembra dia 5 de outubro de renovar a CNH", "renovar a CNH", at(10, 5, 9), portuguese = true)
        check("me lembra às 3 de ligar pra Ana", "ligar pra Ana", at(9, 29, 15), portuguese = true)
        check("me lembra que amanhã tenho dentista", "tenho dentista", at(9, 30, 9), portuguese = true)
        check("Naomi, me lembra hoje à noite de trancar o carro", "trancar o carro", at(9, 29, 20), portuguese = true)
    }

    @Test
    fun portugueseRepeats() {
        check("Naomi, me lembra toda segunda às 8 de levar o lixo", "levar o lixo", at(10, 5, 8), Repeat.WEEKLY, portuguese = true)
        check("me lembra todos os dias às 22h de tomar o remédio", "tomar o remédio", at(9, 29, 22), Repeat.DAILY, portuguese = true)
        check("me lembra todo dia 10 de pagar o aluguel", "pagar o aluguel", at(10, 10, 9), Repeat.MONTHLY, portuguese = true)
        check("me lembra de segunda a sexta às 7 de tomar o café", "tomar o café", at(9, 30, 7), Repeat.WEEKDAYS, portuguese = true)
    }

    @Test
    fun whatOrWhenLeftOutIsLeftBlank() {
        check("remind me to buy milk", "buy milk", null)
        check("me lembra de comprar pão", "comprar pão", null, portuguese = true)
        check("remind me in 10 minutes", "", at(9, 29, 14, 10))
        // "Today" alone isn't a time: she asks.
        check("remind me today to call the bank", "call the bank", null)
    }

    @Test
    fun aDayThatsGoneIsNoTime() {
        // 9 this morning, one-off, is over — so she asks rather than guessing.
        assertNull(ReminderParser.parse("remind me today at 9 am to stretch", now)?.time)
    }

    @Test
    fun answersToWhen() {
        assertEquals(at(9, 30, 9), ReminderParser.parseWhen("tomorrow at 9", now)?.at)
        assertEquals(at(9, 29, 14, 20), ReminderParser.parseWhen("in 20 minutes", now)?.at)
        assertEquals(at(9, 30, 9), ReminderParser.parseWhen("amanhã às 9", now)?.at)
        assertEquals(at(9, 29, 18), ReminderParser.parseWhen("às 6 da tarde", now)?.at)
        assertEquals(at(9, 29, 16, 30), ReminderParser.parseWhen("16:30", now)?.at)
        assertNull(ReminderParser.parseWhen("whenever", now))
        assertNull(ReminderParser.parseWhen("sei lá", now))
    }

    @Test
    fun requestsAreToldFromListsAndCancels() {
        for (said in listOf("what are my reminders?", "do I have any reminders", "quais são meus lembretes?", "tenho algum lembrete?")) {
            assertTrue(said, ReminderParser.isList(said))
            assertFalse(said, ReminderParser.isRequest(said))
            assertFalse(said, ReminderParser.isCancel(said))
        }
        for (said in listOf("cancel the dentist reminder", "cancela o lembrete de ligar pro dentista", "delete all my reminders")) {
            assertTrue(said, ReminderParser.isCancel(said))
            assertFalse(said, ReminderParser.isRequest(said))
            assertFalse(said, ReminderParser.isList(said))
        }
        for (said in listOf("remember that I parked on level 3", "set a timer for 5 minutes", "what time is it", "call mom",
                "what's on my calendar today", "lembra quando fomos pra praia?")) {
            assertFalse(said, ReminderParser.isRequest(said) || ReminderParser.isList(said) || ReminderParser.isCancel(said))
        }
        // Asking for a reminder about cancelling something is still a reminder.
        assertTrue(ReminderParser.isRequest("remind me to cancel the gym tomorrow"))
    }

    @Test
    fun whichReminderToCancel() {
        assertEquals("dentist", ReminderParser.cancelTarget("cancel the dentist reminder"))
        assertEquals("ligar pro dentista", ReminderParser.cancelTarget("cancela o lembrete de ligar pro dentista"))
        assertEquals("all", ReminderParser.cancelTarget("cancel all my reminders"))
        assertEquals("all", ReminderParser.cancelTarget("apaga todos os lembretes"))
        assertEquals("", ReminderParser.cancelTarget("cancel that reminder"))
        assertEquals("", ReminderParser.cancelTarget("cancela esse lembrete"))
    }

    @Test
    fun aModelsTime() {
        assertEquals(at(9, 30, 9), ReminderParser.modelTime("2026-09-30 09:00", now))
        assertEquals(at(9, 30, 9), ReminderParser.modelTime("2026-09-30T09:00:00", now))
        // Today's 9 when it's gone 9: tomorrow's.
        assertEquals(at(9, 30, 9), ReminderParser.modelTime("2026-09-29 09:00", now))
        assertEquals(at(9, 29, 17), ReminderParser.modelTime("17:00", now))
        assertNull(ReminderParser.modelTime("2026-09-20 09:00", now))
        assertNull(ReminderParser.modelTime("", now))
        assertNull(ReminderParser.modelTime("tomorrow", now))
    }

    @Test
    fun anEventsNameWithoutItsTime() {
        assertEquals("add a dentist appointment", ReminderParser.withoutWhen("add a dentist appointment tomorrow at 3pm"))
        assertEquals("marca uma reunião com o João", ReminderParser.withoutWhen("marca uma reunião com o João na sexta às 10"))
    }

    @Test
    fun language() {
        assertTrue(ReminderParser.isPortuguese("me lembra de comprar pão amanhã"))
        assertTrue(ReminderParser.isPortuguese("quais são meus lembretes?"))
        assertFalse(ReminderParser.isPortuguese("remind me to buy bread tomorrow"))
        assertFalse(ReminderParser.isPortuguese("what are my reminders?"))
    }
}
