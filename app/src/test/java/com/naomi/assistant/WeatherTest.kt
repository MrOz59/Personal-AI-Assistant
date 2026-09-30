package com.naomi.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Date

class WeatherTest {

    // A Wednesday.
    private val today = LocalDate.of(2026, 9, 30)

    @Test
    fun daysInEnglishAndPortuguese() {
        assertEquals(1, WeatherClient.dayNamed("will it rain tomorrow?", today))
        assertEquals(1, WeatherClient.dayNamed("Vai chover amanhã", today))
        assertEquals(1, WeatherClient.dayNamed("vai chover amanha?", today))
        assertEquals(2, WeatherClient.dayNamed("e depois de amanhã?", today))
        assertEquals(0, WeatherClient.dayNamed("tá frio hoje?", today))
        assertEquals(2, WeatherClient.dayNamed("e na sexta-feira", today))
        assertEquals(3, WeatherClient.dayNamed("no sábado vai fazer sol?", today))
        assertEquals(4, WeatherClient.dayNamed("e domingo", today))
        assertEquals(2, WeatherClient.dayNamed("on Friday", today))
        // Nothing named: null, so a follow-up can take the day asked before.
        assertNull(WeatherClient.dayNamed("mas foi uma pergunta", today))
        assertNull(WeatherClient.dayNamed("amãhem", today))
        assertEquals(0, WeatherClient.dayOffset("mas foi uma pergunta", today))
    }

    private val forecast = JSONObject("""
        {"current": {"temperature_2m": 21.6, "weather_code": 0},
         "daily": {"time": ["2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"],
                   "weather_code": [0, 61, 3, 80, 2],
                   "temperature_2m_max": [22.2, 19.4, 20.0, 18.0, 25.0],
                   "temperature_2m_min": [12.0, 13.1, 11.0, 10.0, 15.0],
                   "precipitation_probability_max": [5, 60, 10, 40, 0]}}
    """.trimIndent())

    @Test
    fun forecastInPortuguese() {
        assertEquals("Agora faz 22 graus e céu limpo em Cromer, com máxima de 22 hoje, sem previsão de chuva.",
            WeatherClient.report(forecast, 0, "Cromer", pt = true))
        assertEquals("Amanhã em Cromer: chuva, com máxima de 19 e mínima de 13, e 60 por cento de chance de chuva.",
            WeatherClient.report(forecast, 1, "Cromer", pt = true))
        assertEquals("Na sexta em Cromer: nublado, com máxima de 20 e mínima de 11.",
            WeatherClient.report(forecast, 2, "Cromer", pt = true))
        assertEquals("On Friday in Cromer: cloudy, with a high of 20 and a low of 11.",
            WeatherClient.report(forecast, 2, "Cromer"))
        assertEquals("It's 22 degrees and clear in Cromer, with a high of 22 today and no rain expected.",
            WeatherClient.report(forecast, 0, "Cromer"))
        assertEquals("No domingo em Cromer: céu quase limpo, com máxima de 25 e mínima de 15, sem previsão de chuva.",
            WeatherClient.report(forecast, 4, "Cromer", pt = true))
        assertEquals("On Sunday in Cromer: mostly clear, with a high of 25 and a low of 15, and no rain expected.",
            WeatherClient.report(forecast, 4, "Cromer"))
        assertEquals("No sábado em Cromer: chuva, com máxima de 18 e mínima de 10, e 40 por cento de chance de chuva.",
            WeatherClient.report(forecast, 3, "Cromer", pt = true))
        // English as before.
        assertEquals("Tomorrow in Cromer: rainy, with a high of 19 and a low of 13, and a 60 percent chance of rain.",
            WeatherClient.report(forecast, 1, "Cromer"))
    }

    @Test
    fun weatherAskedInPortuguese() {
        for (asked in listOf("Vai chover amanhã", "vai chover amanhã?", "tá frio hoje", "como tá o tempo", "vai fazer sol no sábado",
            "qual a previsão do tempo pra amanhã", "está chovendo agora?", "chuva amanhã")) {
            assertTrue(asked, AssistantBrain.WEATHER_PT.containsMatchIn(asked.lowercase()))
        }
        for (said in listOf("adoro dias de chuva", "ontem choveu muito", "quanto tempo falta", "mas foi uma pergunta")) {
            assertFalse(said, AssistantBrain.WEATHER_PT.containsMatchIn(said))
        }
    }

    @Test
    fun aRetellingMustAgreeOnRain() {
        val dry = "Amanhã em Cromer: nublado, com máxima de 28 e mínima de 14, sem previsão de chuva."
        assertFalse(CloudBrain.agreesOnRain(dry, "Vai chover amanhã na Cromer, 28 de máxima e 14 de mínima, sem chuva prevista."))
        assertFalse(CloudBrain.agreesOnRain(dry, "Amanhã é nublado e tem expectativa de chuva, só 14 graus no mínimo."))
        assertTrue(CloudBrain.agreesOnRain(dry, "Amanhã vai ser nublado, 28 e 14, mas felizmente não há previsão de chuva."))
        assertTrue(CloudBrain.agreesOnRain(dry, "Nublado, 28 de máxima e 14 de mínima, e não vai chover."))
        assertTrue(CloudBrain.agreesOnRain(dry, "Nublado amanhã, máxima de 28 e mínima de 14."))
        val wet = "Tomorrow in Cromer: rainy, with a high of 19 and a low of 13, and a 60 percent chance of rain."
        assertFalse(CloudBrain.agreesOnRain(wet, "Tomorrow's 19 and 13 and you'll stay dry, no rain in sight."))
        assertTrue(CloudBrain.agreesOnRain(wet, "Grab an umbrella: 19 and 13 with a 60 percent chance of rain."))
        assertFalse(CloudBrain.agreesOnRain("It's 22 degrees and clear in Cromer, with a high of 22 today and no rain expected.",
            "22 and clear now, but it's going to rain later."))
    }

    @Test
    fun portugueseQuestionsThatReadLikeStatements() {
        val pt = CloudBrain.systemPrompt("You are Naomi.", TurnContext(language = Language.PORTUGUESE), Date(0))
        assertTrue(pt.contains("\"vai chover amanhã\" asks whether it will rain tomorrow"))
        assertTrue(pt.contains("\"vai chover amanhã\" → {\"action\": {\"type\":\"weather\""))
        assertFalse(CloudBrain.systemPrompt("You are Naomi.", TurnContext(), Date(0)).contains("vai chover"))
    }
}
