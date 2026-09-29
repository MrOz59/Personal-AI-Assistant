package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The deterministic readings that override a model's numbers and days. */
class SpokenNumbersTest {

    @Test
    fun durations() {
        assertEquals(300, CommandRouter.spokenSeconds("set a timer for five minutes"))
        assertEquals(5400, CommandRouter.spokenSeconds("timer for 1 hour 30 minutes"))
        assertEquals(1800, CommandRouter.spokenSeconds("remind me in half an hour"))
        assertEquals(45, CommandRouter.spokenSeconds("forty five seconds please"))
        assertNull(CommandRouter.spokenSeconds("set a timer"))
    }

    @Test
    fun clockTimes() {
        assertEquals(6 to 30, CommandRouter.spokenClock("wake me up at 6:30 tomorrow"))
        assertEquals(18 to 30, CommandRouter.spokenClock("set an alarm for six thirty pm"))
        assertEquals(7 to 0, CommandRouter.spokenClock("alarm at 7 a.m."))
        assertEquals(0 to 15, CommandRouter.spokenClock("12:15 am"))
        assertEquals(7 to 0, CommandRouter.spokenClock("wake me at 7"))
        assertNull(CommandRouter.spokenClock("wake me up in 20 minutes"))
        assertNull(CommandRouter.spokenClock("set an alarm"))
    }

    @Test
    fun messageBodies() {
        assertEquals("i'm running late", CommandRouter.spokenMessage("text john that i'm running late"))
        assertEquals("see you at 8", CommandRouter.spokenMessage("send a whatsapp to mom saying see you at 8"))
        assertNull(CommandRouter.spokenMessage("text john"))
        assertNull(CommandRouter.spokenMessage("I think that's funny"))
    }

    @Test
    fun weatherDays() {
        val tuesday = LocalDate.of(2026, 9, 29)
        assertEquals(0, WeatherClient.dayOffset("how's the weather", tuesday))
        assertEquals(1, WeatherClient.dayOffset("how's the weather tomorrow", tuesday))
        assertEquals(2, WeatherClient.dayOffset("the day after tomorrow", tuesday))
        assertEquals(3, WeatherClient.dayOffset("will it rain on Friday", tuesday))
        assertEquals(0, WeatherClient.dayOffset("weather on tuesday", tuesday))
    }
}
