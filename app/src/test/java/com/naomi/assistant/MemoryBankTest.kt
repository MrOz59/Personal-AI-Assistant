package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId

/** Naomi's long-term memory: keeping, replacing, recalling and forgetting — and the words it keeps. */
class MemoryBankTest {

    @get:Rule val folder = TemporaryFolder()

    private fun bank() = MemoryBank(folder.root.resolve("memories.json")).apply { ownerName = "Ozzy" }

    @Test
    fun aChangedFactReplacesTheOldOne() {
        val bank = bank()
        bank.remember("Ozzy lives in Sydney", "home city")
        bank.remember("Ozzy lives in Melbourne now", "Ozzy's home city")
        assertEquals(listOf("Ozzy lives in Melbourne now"), bank.all().map { it.text })
        // The same thing said again, without a topic, doesn't pile up either.
        bank.remember("Ozzy's sister Ana lives in Lisbon")
        bank.remember("Ozzy's sister Ana lives in Lisbon, Portugal")
        assertEquals(2, bank.all().size)
        // A broad topic doesn't mean the same fact.
        bank.remember("Ozzy has a brother, Tom", "family")
        bank.remember("Ozzy's cousin Leo lives in Porto", "family")
        assertEquals(4, bank.all().size)
    }

    @Test
    fun recallFindsWhatWasSaidInOtherWords() {
        val bank = bank()
        bank.remember("Ozzy parked on level 3")
        bank.remember("Ozzy is vegetarian")
        bank.remember("Ozzy's sister Ana lives in Lisbon")
        bank.remember("Ozzy likes his coffee black")
        assertEquals(listOf("Ozzy parked on level 3"), bank.relevant("where did I park the car?", MemoryBank.Kind.FACT, 3).map { it.text })
        assertEquals("Ozzy's sister Ana lives in Lisbon", bank.relevant("when is my sister visiting", MemoryBank.Kind.FACT, 3).first().text)
        assertEquals("Ozzy likes his coffee black", bank.relevant("I'd like a coffee", MemoryBank.Kind.FACT, 1).first().text)
        assertTrue("nothing shared, nothing recalled", bank.relevant("tell me a joke", MemoryBank.Kind.FACT, 3).isEmpty())
        assertTrue("the owner's name alone isn't a match", bank.relevant("Ozzy", MemoryBank.Kind.FACT, 3).isEmpty())
    }

    @Test
    fun recallIsMarkedAndSurvivesARestart() {
        val bank = bank()
        val parked = bank.remember("Ozzy parked on level 3", now = 1_000)
        bank.addEpisode("Ozzy talked about the migration deadline.", now = 2_000)
        bank.relevant("where did I park", MemoryBank.Kind.FACT, 1, now = 5_000)
        val again = MemoryBank(folder.root.resolve("memories.json"))
        assertEquals(bank.all(), again.all())
        assertEquals(5_000, again.all().first { it.id == parked.id }.used)
        // New ids don't collide with ones from before the restart.
        assertTrue(again.remember("Ozzy has a dog called Biscuit").id > parked.id)
    }

    @Test
    fun forgettingTakesOnlyClearMatches() {
        val bank = bank()
        bank.remember("Ozzy parked on level 3")
        bank.remember("Ozzy's sister Ana lives in Lisbon")
        bank.remember("Ozzy's sister is called Ana")
        bank.remember("Ozzy is vegetarian")
        assertEquals(listOf("Ozzy parked on level 3"), bank.forget("where I parked").map { it.text })
        assertEquals(listOf("Ozzy's sister Ana lives in Lisbon"), bank.forget("that my sister lives in Lisbon").map { it.text })
        assertTrue(bank.forget("my favourite football team").isEmpty())
        assertEquals(2, bank.all().size)
    }

    @Test
    fun theLastConversationIsKeptInMind() {
        val bank = bank()
        bank.addEpisode("Ozzy was stressed about work.", now = 1_000)
        bank.addEpisode("Ozzy planned a trip to Bali.", now = 2_000)
        assertEquals("Ozzy planned a trip to Bali.", bank.lastEpisode(withinMs = 10_000, now = 5_000)?.text)
        assertNull("too long ago", bank.lastEpisode(withinMs = 1_000, now = 5_000))
    }

    @Test
    fun notesSayWhenTheyAreFrom() {
        val zone = ZoneId.of("Australia/Sydney")
        fun at(day: Int, hour: Int) = LocalDate.of(2026, 9, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val now = at(29, 18)
        assertEquals("Earlier today", MemoryBank.whenSaid(at(29, 9), now, zone))
        assertEquals("Yesterday", MemoryBank.whenSaid(at(28, 23), now, zone))
        assertEquals("3 days ago", MemoryBank.whenSaid(at(26, 12), now, zone))
        assertEquals("On 2 September", MemoryBank.whenSaid(at(2, 12), now, zone))
    }

    @Test
    fun relativeDatesArePinned() {
        val today = LocalDate.of(2026, 9, 29) // a Tuesday
        assertEquals("Ozzy has a dentist appointment on Friday (2 October 2026)",
            MemoryBank.pinDates("Ozzy has a dentist appointment on Friday", today))
        assertEquals("Ana arrives tomorrow (Wednesday 30 September 2026)", MemoryBank.pinDates("Ana arrives tomorrow", today))
        assertEquals("the day after tomorrow (Thursday 1 October 2026)", MemoryBank.pinDates("the day after tomorrow", today))
        assertEquals("Ozzy starts at Canva next month (October 2026)", MemoryBank.pinDates("Ozzy starts at Canva next month", today))
        assertEquals("Ana visits next week (the week of 5 October 2026)", MemoryBank.pinDates("Ana visits next week", today))
        assertEquals("they met last Sunday (27 September 2026)", MemoryBank.pinDates("they met last Sunday", today))
        assertEquals("the party is this Tuesday (29 September 2026)", MemoryBank.pinDates("the party is this Tuesday", today))
        assertEquals("recurring days stay as they are", "Ozzy goes to the gym every Monday and on Fridays",
            MemoryBank.pinDates("Ozzy goes to the gym every Monday and on Fridays", today))
        val once = MemoryBank.pinDates("Ana arrives on Friday", today)
        assertEquals("pinning twice changes nothing", once, MemoryBank.pinDates(once, today))
    }

    @Test
    fun theOwnersWordsRetoldAboutThem() {
        fun retold(said: String) = MemoryBank.inThirdPerson(said, "Ozzy")
        assertEquals("Ozzy parked on level 3", retold("I parked on level 3"))
        assertEquals("Ozzy likes their coffee black", retold("I like my coffee black"))
        assertEquals("Ozzy's wifi password is on the fridge", retold("my wifi password is on the fridge"))
        assertEquals("Ozzy is allergic to peanuts", retold("I'm allergic to peanuts"))
        assertEquals("Ozzy thinks they left their keys at work", retold("I think I left my keys at work"))
        assertEquals("Ozzy usually goes running at 6", retold("I usually go running at 6"))
        assertEquals("Ozzy watches F1 on Sundays", retold("i watch F1 on Sundays"))
        assertEquals("Ozzy studies at night", retold("I study at night"))
        assertEquals("Ozzy doesn't eat pork", retold("I don't eat pork"))
        assertEquals("Ozzy has two cats", retold("I have two cats"))
        assertEquals("Ozzy put the spare key under the mat", retold("I put the spare key under the mat"))
        assertEquals("The vet called Ozzy about Rex", retold("the vet called me about Rex"))
        assertEquals("Ozzy and their wife are going to Bali", retold("me and my wife are going to Bali"))
        assertEquals("The blue car is Ozzy's", retold("the blue car is mine"))
        assertEquals("Ozzy has a sister called Ana", retold("Ozzy has a sister called Ana"))
    }

    @Test
    fun wordEndingsFold() {
        for ((a, b) in listOf("parked" to "parking", "liked" to "likes", "hated" to "hate", "running" to "run",
            "parties" to "party", "studied" to "study", "meetings" to "meeting", "named" to "name", "coding" to "code")) {
            assertEquals("$a ~ $b", MemoryBank.stem(a), MemoryBank.stem(b))
        }
        assertFalse(MemoryBank.stem("hopping") == MemoryBank.stem("hoping"))
        assertTrue(MemoryBank.similar(MemoryBank.stem("movies"), MemoryBank.stem("movie")))
        assertFalse(MemoryBank.similar("car", "card"))
    }
}
