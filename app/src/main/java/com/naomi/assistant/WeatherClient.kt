package com.naomi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Tiny weather lookup via open-meteo.com — free, no API key, no account.
 * Geocodes a city name (or takes the phone's own location), fetches current conditions plus
 * a 7-day forecast in one call, and returns one spoken-style sentence — in English, or in
 * Brazilian Portuguese when she speaks it.
 * Network-bound, so call off the main thread.
 */
class WeatherClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * The weather for [city] — or, when no city is named, for [here] (the phone's location) —
     * [day] days from today: 0 is right now, 1 tomorrow, up to 6. In Portuguese if [pt].
     */
    suspend fun forecast(city: String, day: Int = 0, here: DeviceLocation.Place? = null, pt: Boolean = false): String =
        withContext(Dispatchers.IO) {
            fun say(en: String, ptBr: String) = if (pt) ptBr else en
            if (day !in 0..6) return@withContext say("I can only see about a week ahead.", "Só consigo ver até uma semana à frente.")
            val unreachable = say("I couldn't reach the weather service.", "Não consegui falar com o serviço do tempo.")
            try {
                val (lat, lon, name) = if (city.isNotBlank()) {
                    val geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${enc(city)}")
                        ?: return@withContext unreachable
                    val results = JSONObject(geo).optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        return@withContext say("I couldn't find a place called $city.", "Não achei nenhum lugar chamado $city.")
                    }
                    val place = results.getJSONObject(0)
                    Triple(place.optDouble("latitude"), place.optDouble("longitude"), place.optString("name", city))
                } else {
                    here ?: return@withContext say("Which city's weather would you like?", "De qual cidade você quer o tempo?")
                    Triple(here.lat, here.lon, here.name ?: say("your area", "sua região"))
                }

                val wx = get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=7" +
                        "&current=temperature_2m,weather_code" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                ) ?: return@withContext unreachable
                report(JSONObject(wx), day, name, pt)
            } catch (e: java.net.UnknownHostException) {
                say("I'm offline, so I can't check the weather right now.", "Estou sem internet, então não consigo ver o tempo agora.")
            } catch (e: Exception) {
                say("I couldn't get the weather: ${e.message}", "Não consegui ver o tempo: ${e.message}")
            }
        }

    private fun get(url: String): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {

        /** One spoken sentence from an open-meteo forecast response, in Portuguese if [pt]. */
        fun report(json: JSONObject, day: Int, place: String, pt: Boolean = false): String {
            if (pt) return reportPt(json, day, place)
            val daily = json.optJSONObject("daily") ?: return "I couldn't get the weather for $place."
            val high = daily.optJSONArray("temperature_2m_max")?.optDouble(day)
            val low = daily.optJSONArray("temperature_2m_min")?.optDouble(day)
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(day, -1) ?: -1

            if (day == 0) {
                val cur = json.optJSONObject("current") ?: return "I couldn't get the weather for $place."
                val now = "It's ${cur.optDouble("temperature_2m").roundToInt()} degrees and " +
                    "${describe(cur.optInt("weather_code"))} in $place"
                val extras = listOfNotNull(
                    high?.takeIf { !it.isNaN() }?.let { "a high of ${it.roundToInt()} today" },
                    rain.takeIf { it >= 30 }?.let { "a $it percent chance of rain" },
                    "no rain expected".takeIf { rain in 0 until NO_RAIN },
                )
                return if (extras.isEmpty()) "$now." else "$now, with ${extras.joinToString(" and ")}."
            }

            val code = daily.optJSONArray("weather_code")?.optInt(day) ?: return "I couldn't get the forecast for $place."
            val date = daily.optJSONArray("time")?.optString(day)
            val label = if (day == 1) "Tomorrow" else date?.let {
                "On " + LocalDate.parse(it).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            } ?: "That day"
            val temps = if (high != null && low != null && !high.isNaN() && !low.isNaN())
                ", with a high of ${high.roundToInt()} and a low of ${low.roundToInt()}" else ""
            val chance = when {
                rain >= 20 -> ", and a $rain percent chance of rain"
                rain in 0 until NO_RAIN -> ", and no rain expected"
                else -> ""
            }
            return "$label in $place: ${describe(code)}$temps$chance."
        }

        /**
         * [report] in Brazilian Portuguese: "Agora faz 22 graus e céu limpo em Cromer, com máxima
         * de 22 hoje." / "Amanhã em Cromer: chuva, com máxima de 19 e mínima de 13, e 60 por cento
         * de chance de chuva."
         */
        private fun reportPt(json: JSONObject, day: Int, place: String): String {
            val daily = json.optJSONObject("daily") ?: return "Não consegui ver o tempo em $place."
            val high = daily.optJSONArray("temperature_2m_max")?.optDouble(day)?.takeIf { !it.isNaN() }
            val low = daily.optJSONArray("temperature_2m_min")?.optDouble(day)?.takeIf { !it.isNaN() }
            val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(day, -1) ?: -1

            if (day == 0) {
                val cur = json.optJSONObject("current") ?: return "Não consegui ver o tempo em $place."
                val now = "Agora faz ${cur.optDouble("temperature_2m").roundToInt()} graus e " +
                    "${describePt(cur.optInt("weather_code"))} em $place"
                val extras = listOfNotNull(
                    high?.let { "máxima de ${it.roundToInt()} hoje" },
                    rain.takeIf { it >= 30 }?.let { "$it por cento de chance de chuva" },
                )
                // "Vai chover?" gets its answer even when the answer is no.
                val dry = if (rain in 0 until NO_RAIN) ", sem previsão de chuva" else ""
                if (extras.isEmpty()) return "$now$dry."
                return "$now, com ${extras.joinToString(" e ")}$dry."
            }

            val code = daily.optJSONArray("weather_code")?.optInt(day) ?: return "Não consegui ver a previsão para $place."
            val date = daily.optJSONArray("time")?.optString(day)
            val label = if (day == 1) "Amanhã" else date?.let {
                val weekday = LocalDate.parse(it).dayOfWeek
                // "No sábado", "Na segunda": the weekend days are masculine in Portuguese.
                (if (weekday.value >= 6) "No " else "Na ") + weekday.getDisplayName(TextStyle.FULL, PT_BR).substringBefore('-')
            } ?: "Nesse dia"
            val temps = if (high != null && low != null) ", com máxima de ${high.roundToInt()} e mínima de ${low.roundToInt()}" else ""
            val chance = when {
                rain >= 20 -> ", e $rain por cento de chance de chuva"
                rain in 0 until NO_RAIN -> ", sem previsão de chuva"
                else -> ""
            }
            return "$label em $place: ${describePt(code)}$temps$chance."
        }

        private val PT_BR = Locale.forLanguageTag("pt-BR")

        // Below this chance of rain the report says there's none to expect — so "will it rain?" is
        // answered, and a retelling doesn't guess.
        private const val NO_RAIN = 10

        /** WMO weather codes in Portuguese, as it reads after "faz 22 graus e" and on its own. */
        private fun describePt(code: Int): String = when (code) {
            0 -> "céu limpo"
            1, 2 -> "céu quase limpo"
            3 -> "nublado"
            45, 48 -> "neblina"
            51, 53, 55, 56, 57 -> "garoa"
            61, 63, 65, 66, 67, 80, 81, 82 -> "chuva"
            71, 73, 75, 77, 85, 86 -> "neve"
            95, 96, 99 -> "tempestade"
            else -> "tempo ameno"
        }

        /** WMO weather codes → a plain spoken word. */
        private fun describe(code: Int): String = when (code) {
            0 -> "clear"
            1, 2 -> "mostly clear"
            3 -> "cloudy"
            45, 48 -> "foggy"
            51, 53, 55, 56, 57 -> "drizzly"
            61, 63, 65, 66, 67, 80, 81, 82 -> "rainy"
            71, 73, 75, 77, 85, 86 -> "snowy"
            95, 96, 99 -> "stormy"
            else -> "mild"
        }

        /** Days from today for a spoken day — "tomorrow", "friday", "amanhã", "sexta"… — or 0 (today/now). */
        fun dayOffset(text: String, today: LocalDate = LocalDate.now()): Int = dayNamed(text, today) ?: 0

        /**
         * Days from today for a day named in [text], in English or Portuguese — "day after
         * tomorrow" / "depois de amanhã" 2, "tomorrow" / "amanhã" 1, "today" / "hoje" 0, a
         * weekday its next one — or null if it names none.
         */
        fun dayNamed(text: String, today: LocalDate = LocalDate.now()): Int? {
            val t = text.lowercase()
            if (wordRegex("\\b(day after tomorrow|depois de amanh[ãa])\\b").containsMatchIn(t)) return 2
            if (wordRegex("\\b(tomorrow|amanh[ãa])\\b").containsMatchIn(t)) return 1
            if (wordRegex("\\b(today|tonight|hoje)\\b").containsMatchIn(t)) return 0
            val named = WEEKDAYS.entries.firstOrNull { (name, _) -> wordRegex("\\b$name\\b").containsMatchIn(t) }?.value
                ?: return null
            return (named.value - today.dayOfWeek.value + 7) % 7
        }

        // Weekday names as said: English, and Portuguese with or without "-feira" and accents.
        private val WEEKDAYS: Map<String, java.time.DayOfWeek> =
            java.time.DayOfWeek.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() } + mapOf(
                "segunda" to java.time.DayOfWeek.MONDAY, "terça" to java.time.DayOfWeek.TUESDAY,
                "terca" to java.time.DayOfWeek.TUESDAY, "quarta" to java.time.DayOfWeek.WEDNESDAY,
                "quinta" to java.time.DayOfWeek.THURSDAY, "sexta" to java.time.DayOfWeek.FRIDAY,
                "sábado" to java.time.DayOfWeek.SATURDAY, "sabado" to java.time.DayOfWeek.SATURDAY,
                "domingo" to java.time.DayOfWeek.SUNDAY,
            )
    }
}
