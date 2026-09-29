package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeping reminders: in order, across restarts, moving repeats on, and finding the one meant. */
class ReminderStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun store() = ReminderStore(folder.root.resolve("reminders.json")) { zone }
    private fun ms(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun keptSoonestFirstAndAcrossARestart() {
        val store = store()
        store.add("call the dentist", ms(LocalDateTime.of(2026, 10, 1, 9, 0)), now = 1)
        store.add("take the pills", ms(LocalDateTime.of(2026, 9, 29, 17, 0)), Repeat.DAILY, now = 2)
        store.add("pagar o aluguel", ms(LocalDateTime.of(2026, 10, 10, 9, 0)), Repeat.MONTHLY, portuguese = true, now = 3)
        assertEquals(listOf("take the pills", "call the dentist", "pagar o aluguel"), store.all().map { it.text })
        assertEquals(store.all(), store().all())
        assertEquals("pagar o aluguel", store.latest()?.text)
    }

    @Test
    fun aOneOffIsGoneOnceItGoesOff() {
        val store = store()
        val r = store.add("call the dentist", ms(LocalDateTime.of(2026, 10, 1, 9, 0)))
        assertNull(store.fired(r.id, ms(LocalDateTime.of(2026, 10, 1, 9, 0))))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun repeatsMoveOnToTheirNextTime() {
        val store = store()
        val daily = store.add("take the pills", ms(LocalDateTime.of(2026, 9, 29, 17, 0)), Repeat.DAILY)
        // Went off late (the phone was off for two days): the next one after now, at the same time of day.
        val next = store.fired(daily.id, ms(LocalDateTime.of(2026, 10, 1, 8, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 1, 17, 0), next?.let { store.local(it.at) })
        // A weekday one on Friday comes back on Monday.
        val work = store.add("stand-up", ms(LocalDateTime.of(2026, 10, 2, 9, 30)), Repeat.WEEKDAYS)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 30), store.local(store.fired(work.id, ms(LocalDateTime.of(2026, 10, 2, 9, 30)))!!.at))
        val rent = store.add("pagar o aluguel", ms(LocalDateTime.of(2026, 10, 10, 9, 0)), Repeat.MONTHLY)
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), store.local(store.fired(rent.id, ms(LocalDateTime.of(2026, 10, 10, 9, 0)))!!.at))
        assertEquals(3, store.all().size)
    }

    @Test
    fun findingTheOneMeant() {
        val store = store()
        store.add("call the dentist", 1)
        store.add("call mom", 2)
        store.add("ligar pro encanador", 3)
        store.add("pagar a conta de luz", 4)
        assertEquals(listOf("call the dentist"), store.matching("the dentist").map { it.text })
        assertEquals(listOf("ligar pro encanador"), store.matching("o encanador").map { it.text })
        assertEquals(listOf("pagar a conta de luz"), store.matching("conta de luz").map { it.text })
        // Sharing only "de" or "call" with a longer description isn't enough.
        assertTrue(store.matching("call the plumber about the leak").isEmpty())
        assertTrue(store.matching("de").isEmpty())
    }

    @Test
    fun idsArentReused() {
        val store = store()
        val first = store.add("one", 1)
        store.remove(listOf(first.id))
        assertTrue(store().add("two", 2).id > first.id)
    }

    @Test
    fun repeatsByName() {
        assertEquals(Repeat.WEEKDAYS, Repeat.of("weekdays"))
        assertEquals(Repeat.DAILY, Repeat.of(" Daily "))
        assertEquals(Repeat.NONE, Repeat.of(""))
        assertEquals(Repeat.NONE, Repeat.of(null))
    }
}
