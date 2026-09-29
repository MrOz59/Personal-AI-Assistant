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
 * a 7-day forecast in one call, and returns one spoken-style sentence.
 * Network-bound, so call off the main thread.
 */
class WeatherClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * The weather for [city] — or, when no city is named, for [here] (the phone's location) —
     * [day] days from today: 0 is right now, 1 tomorrow, up to 6.
     */
    suspend fun forecast(city: String, day: Int = 0, here: DeviceLocation.Place? = null): String = withContext(Dispatchers.IO) {
        if (day !in 0..6) return@withContext "I can only see about a week ahead."
        try {
            val (lat, lon, name) = if (city.isNotBlank()) {
                val geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${enc(city)}")
                    ?: return@withContext "I couldn't reach the weather service."
                val results = JSONObject(geo).optJSONArray("results")
                if (results == null || results.length() == 0) {
                    return@withContext "I couldn't find a place called $city."
                }
                val place = results.getJSONObject(0)
                Triple(place.optDouble("latitude"), place.optDouble("longitude"), place.optString("name", city))
            } else {
                here ?: return@withContext "Which city's weather would you like?"
                Triple(here.lat, here.lon, here.name ?: "your area")
            }

            val wx = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=7" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            ) ?: return@withContext "I couldn't reach the weather service."
            report(JSONObject(wx), day, name)
        } catch (e: java.net.UnknownHostException) {
            "I'm offline, so I can't check the weather right now."
        } catch (e: Exception) {
            "I couldn't get the weather: ${e.message}"
        }
    }

    private fun get(url: String): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {

        /** One spoken sentence from an open-meteo forecast response. */
        fun report(json: JSONObject, day: Int, place: String): String {
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
            val chance = if (rain >= 20) ", and a $rain percent chance of rain" else ""
            return "$label in $place: ${describe(code)}$temps$chance."
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

        /** Days from today for a spoken day — "tomorrow", "friday"… — or 0 (today/now). */
        fun dayOffset(text: String, today: LocalDate = LocalDate.now()): Int {
            val t = text.lowercase()
            if (Regex("\\b(day after tomorrow)\\b").containsMatchIn(t)) return 2
            if (Regex("\\btomorrow\\b").containsMatchIn(t)) return 1
            val names = java.time.DayOfWeek.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }
            val named = names.entries.firstOrNull { (name, _) -> Regex("\\b$name\\b").containsMatchIn(t) }?.value
                ?: return 0
            return (named.value - today.dayOfWeek.value + 7) % 7
        }
    }
}
