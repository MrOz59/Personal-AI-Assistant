package com.naomi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "How do I get there by bus?" — public transport directions from Transitous (transitous.org), a
 * free, community-run router over the transit agencies' own timetables, Sydney's buses, trains
 * and ferries among them. No key needed. Network-bound: call off the main thread.
 */
class TransitClient {

    /** One part of a trip: walking, or riding a [route] (towards [headsign]) from [from] to [to]. */
    data class Leg(
        val mode: String,
        val route: String?,
        val headsign: String?,
        val from: String,
        val to: String,
        val start: Instant,
        val minutes: Int,
    ) {
        val walking: Boolean get() = mode == "WALK"
    }

    data class Trip(val legs: List<Leg>, val arrive: Instant)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The trip that arrives first, leaving now, from [from] to the point [lat], [lon]; null if none or unreachable. */
    suspend fun plan(from: DeviceLocation.Place, lat: Double, lon: Double): Trip? = withContext(Dispatchers.IO) {
        val url = "https://api.transitous.org/api/v1/plan?fromPlace=${from.lat},${from.lon}&toPlace=$lat,$lon&numItineraries=3"
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) best(JSONObject(r.body?.string().orEmpty())) else null
            }
        }.getOrNull()
    }

    companion object {
        private const val USER_AGENT = "Naomi-Assistant/1.0 (personal voice assistant)"
        private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

        /** The itinerary in a Transitous answer that arrives first, or null if it has none that rides anything. */
        fun best(answer: JSONObject): Trip? {
            val itineraries = answer.optJSONArray("itineraries") ?: return null
            return (0 until itineraries.length()).mapNotNull { itineraries.optJSONObject(it) }.mapNotNull { it ->
                val legs = it.optJSONArray("legs") ?: return@mapNotNull null
                val parsed = (0 until legs.length()).mapNotNull { i -> legs.optJSONObject(i) }.map { leg ->
                    Leg(
                        mode = leg.optString("mode"),
                        route = leg.optString("routeShortName").ifBlank { null },
                        headsign = leg.optString("headsign").ifBlank { null },
                        from = leg.optJSONObject("from")?.optString("name").orEmpty(),
                        to = leg.optJSONObject("to")?.optString("name").orEmpty(),
                        start = Instant.parse(leg.optString("startTime")),
                        minutes = Math.round(leg.optDouble("duration", 0.0) / 60).toInt(),
                    )
                }
                if (parsed.none { !it.walking }) null else Trip(parsed, Instant.parse(it.optString("endTime")))
            }.minByOrNull { it.arrive }
        }

        /**
         * [trip] to [place] as she'd say it: "Walk about 10 minutes to Frenchs Forest Road opposite
         * Inverness Avenue and catch the 160X bus towards Chatswood at 8:55 PM. Ride 3 minutes to
         * Rabbett Street at Forest Way, then it's a 5-minute walk to the Woolworths at Forestway
         * Shopping Centre. You'd get there around 9:13 PM."
         */
        fun spoken(trip: Trip, place: String, zone: ZoneId = ZoneId.systemDefault(), now: Instant? = Instant.now()): String {
            fun at(t: Instant) = CLOCK.format(t.atZone(zone))
            fun stop(name: String) = DistanceClient.spelledOut(name)
            val parts = mutableListOf<String>()
            var rides = 0
            trip.legs.forEachIndexed { i, leg ->
                val last = i == trip.legs.lastIndex
                when {
                    leg.walking && i == 0 && !last ->
                        if (leg.minutes >= 1) parts += "Walk about ${minutes(leg.minutes)} to ${stop(leg.to)}"
                    leg.walking && last ->
                        parts += if (leg.minutes >= 1) "then it's ${walk(leg.minutes)} to $place" else "and you're at $place"
                    leg.walking -> if (leg.minutes >= 1) parts += "walk ${minutes(leg.minutes)} to ${stop(leg.to)}"
                    else -> {
                        val ride = listOfNotNull(leg.route, vehicle(leg.mode)).joinToString(" ")
                        val towards = leg.headsign?.let { " towards ${stop(it)}" }.orEmpty()
                        val catch = if (rides == 0) "catch" else "then change to"
                        // Where to catch it, unless the walk there just said so.
                        val where = if (parts.isEmpty()) " from ${stop(leg.from)}" else ""
                        parts += "$catch the $ride$towards$where at ${at(leg.start)}"
                        parts += (if (leg.minutes >= 1) "ride ${minutes(leg.minutes)} to ${stop(leg.to)}" else "get off at ${stop(leg.to)}") +
                            if (last) ", and you're there" else ""
                        rides++
                    }
                }
            }
            return sentences(parts) + " You'd get there around ${at(trip.arrive)}." + (now?.let { leaveTip(trip, it) }?.let { " $it" } ?: "")
        }

        /**
         * When to set off to catch the first ride: "You'll want to head out now to make it." when
         * there's barely time for the walk to the stop, "You've got about 12 minutes before you need
         * to leave." when there's a while; nothing in between.
         */
        fun leaveTip(trip: Trip, now: Instant): String? {
            val ride = trip.legs.firstOrNull { !it.walking } ?: return null
            val walkFirst = trip.legs.takeWhile { it.walking }.sumOf { it.minutes }
            val spare = ((ride.start.epochSecond - now.epochSecond) / 60).toInt() - walkFirst
            return when {
                spare <= 2 -> "You'll want to head out now to make it."
                spare >= 10 -> "You've got about $spare minutes before you need to leave."
                else -> null
            }
        }

        /** The steps joined into speech: a sentence break before each ride, commas otherwise. */
        private fun sentences(parts: List<String>): String {
            val out = StringBuilder()
            for (p in parts) {
                when {
                    out.isEmpty() -> out.append(p.replaceFirstChar { it.uppercase() })
                    p.startsWith("catch") -> out.append(" and ").append(p)
                    p.startsWith("ride") -> out.append(". ").append(p.replaceFirstChar { it.uppercase() })
                    else -> out.append(", ").append(p)
                }
            }
            return out.append('.').toString()
        }

        private fun vehicle(mode: String): String? = when (mode) {
            "BUS", "COACH" -> "bus"
            "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "SUBURBAN" -> "train"
            "METRO", "SUBWAY" -> "metro"
            "TRAM" -> "light rail"
            "FERRY" -> "ferry"
            else -> null
        }

        private fun minutes(n: Int) = if (n == 1) "a minute" else "$n minutes"

        private fun walk(n: Int) = when (n) {
            1 -> "a minute's walk"
            8, 11, 18 -> "an $n-minute walk"
            else -> "a $n-minute walk"
        }
    }
}
