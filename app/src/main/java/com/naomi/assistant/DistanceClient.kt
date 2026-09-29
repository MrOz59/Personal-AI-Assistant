package com.naomi.assistant

import android.content.Context
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * "How far am I from X?" — finds X near where the phone is (the platform geocoder, biased to
 * the surrounding area), then the driving distance and time from OSRM's free public router,
 * falling back to the straight-line distance. "Where's the nearest bus stop?" — the geocoder
 * only knows addresses and names, so kinds of places (and chains) are looked up around the phone
 * in OpenStreetMap (Nominatim), and a close one is given on foot. Network-bound: call off the
 * main thread.
 */
class DistanceClient(private val context: Context) {

    data class Found(val name: String, val lat: Double, val lon: Double)

    /** How the owner means to get there; null leaves it to the distance (walk if close, else drive). */
    enum class Travel { WALK, DRIVE, TRANSIT }

    /** A search result, before it's put into words: its name, street and suburb as the map has them. */
    private class Candidate(val name: String, val road: String?, val suburb: String?, val lat: Double, val lon: Double)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * [query] as a place: OpenStreetMap first — it knows shops, stops and landmarks by name, near
     * the phone — then the platform geocoder, preferring matches within about 100 km of [near].
     * Null if neither finds it.
     */
    suspend fun locate(query: String, near: DeviceLocation.Place): Found? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        runCatching { search(query, near.lat, near.lon, LOCATE_SPAN, limit = 10) }.getOrNull()
            ?.let(::candidates)
            ?.minByOrNull { straightKm(near, Found(it.name, it.lat, it.lon)) }
            ?.let { return@withContext Found(spelledOut(it.name.ifBlank { query }), it.lat, it.lon) }
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        runCatching {
            @Suppress("DEPRECATION") // the listener variant is API 33+; this runs off the main thread
            (geocoder.getFromLocationName(query, 1, near.lat - 1, near.lon - 1, near.lat + 1, near.lon + 1)
                ?.firstOrNull()
                ?: geocoder.getFromLocationName(query, 1)?.firstOrNull())
                ?.let { Found(it.locality ?: it.featureName ?: query, it.latitude, it.longitude) }
        }.getOrNull()
    }

    /**
     * The nearest place of a kind — "bus stop", "pharmacy" — or by name — "Woolworths" — around
     * [near], from OpenStreetMap, looking wider until something turns up. Null if there's nothing
     * within about 13 km, or the search can't be reached.
     */
    suspend fun nearest(what: String, near: DeviceLocation.Place): Found? = withContext(Dispatchers.IO) {
        for ((i, span) in NEAREST_SPANS.withIndex()) {
            // Nominatim asks for at most one request a second.
            if (i > 0) Thread.sleep(1_100)
            val found = runCatching { searchAround(what, near, span) }.getOrNull() ?: return@withContext null
            val best = found.minByOrNull { straightKm(near, Found(it.name, it.lat, it.lon)) } ?: continue
            // A chain store is named just by its brand, and filed under the lane behind its car
            // park: say where it is the way people know it — by the shopping centre it's in.
            val place = if (best.name.isBlank() || best.name.equals(kindOf(what), ignoreCase = true)) {
                Thread.sleep(1_100)
                spokenPlace(best.name, runCatching { mallAround(best) }.getOrNull(), best.suburb, best.road, what)
            } else spelledOut(best.name)
            return@withContext Found(place, best.lat, best.lon)
        }
        null
    }

    /** Places matching [what] in a box [span] degrees either side of [near]; null if the search failed. */
    private fun searchAround(what: String, near: DeviceLocation.Place, span: Double): List<Candidate>? =
        search(what, near.lat, near.lon, span, limit = 40)?.let(::candidates)

    /** A search's results, as candidates. */
    private fun candidates(list: JSONArray): List<Candidate> =
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { o ->
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val address = o.optJSONObject("address")
            fun part(vararg keys: String) = keys.firstNotNullOfOrNull { k -> address?.optString(k)?.takeIf { it.isNotBlank() } }
            Candidate(o.optString("name"), part("road"), part("suburb", "neighbourhood", "quarter", "town", "village"), lat, lon)
        }

    /** The shopping centre [c] is in (within about 250 m), or null. */
    private fun mallAround(c: Candidate): String? {
        val list = search("mall", c.lat, c.lon, MALL_SPAN, limit = 5) ?: return null
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "mall" && it.optString("name").isNotBlank() }?.optString("name")
    }

    /** OpenStreetMap's matches for [q] in a box [span] degrees either side of a point; null if the search failed. */
    private fun search(q: String, lat: Double, lon: Double, span: Double, limit: Int): JSONArray? {
        val box = "${lon - span},${lat + span},${lon + span},${lat - span}"
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=$limit&bounded=1" +
            "&viewbox=$box&q=" + URLEncoder.encode(q, "UTF-8")
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            return if (r.isSuccessful) JSONArray(r.body?.string().orEmpty()) else null
        }
    }

    /**
     * One spoken sentence about the nearest [what], [to]: which one, how far, how long — on foot
     * or by car as [travel] says, else on foot when it's within walking distance.
     */
    suspend fun describeNearest(from: DeviceLocation.Place, to: Found, what: String, travel: Travel? = null): String = withContext(Dispatchers.IO) {
        val straight = straightKm(from, to)
        if (onFoot(travel, straight)) {
            val (km, minutes) = walk(from, to, straight)
            nearestSentence(what, to.name, km, minutes, onFoot = true)
        } else {
            val drive = runCatching { route(CAR_ROUTER, from, to) }.getOrNull()
            if (drive != null) nearestSentence(what, to.name, drive.first, drive.second, onFoot = false)
            else "The nearest ${kindOf(what)} is ${to.name}, about ${km(straight)} kilometres from you in a straight line."
        }
    }

    private fun straightKm(from: DeviceLocation.Place, to: Found): Double =
        FloatArray(1).also { Location.distanceBetween(from.lat, from.lon, to.lat, to.lon, it) }[0] / 1000.0

    private fun onFoot(travel: Travel?, straightKm: Double) =
        travel == Travel.WALK || (travel == null && straightKm < WALKING_KM)

    /** (km, minutes) walking from [from] to [to]; without a route, a third more than the straight line — streets wind. */
    private fun walk(from: DeviceLocation.Place, to: Found, straightKm: Double): Pair<Double, Int> {
        val route = runCatching { route(FOOT_ROUTER, from, to) }.getOrNull()
        val km = route?.first ?: straightKm * 1.3
        return km to (route?.second ?: (km / WALKING_KMH * 60).roundToInt())
    }

    /**
     * One spoken sentence: how far [to] is from [from] — on foot or by car as [travel] says, else
     * on foot when it's close — by the router's route, or the straight line if it doesn't answer.
     */
    suspend fun describe(from: DeviceLocation.Place, to: Found, travel: Travel? = null): String = withContext(Dispatchers.IO) {
        val straight = straightKm(from, to)
        if (onFoot(travel, straight)) {
            val (km, minutes) = walk(from, to, straight)
            walkingSentence(to.name, km, minutes)
        } else {
            val route = runCatching { route(CAR_ROUTER, from, to) }.getOrNull()
            sentence(to.name, straight, route?.first, route?.second)
        }
    }

    /** (km, minutes) from [from] to [to] by the OSRM router at [base] — driving or on foot — or null. */
    private fun route(base: String, from: DeviceLocation.Place, to: Found): Pair<Double, Int>? {
        val url = "$base/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}?overview=false"
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val route = JSONObject(r.body?.string().orEmpty()).optJSONArray("routes")?.optJSONObject(0) ?: return null
            return route.optDouble("distance") / 1000.0 to (route.optDouble("duration") / 60.0).roundToInt()
        }
    }

    companion object {
        private const val USER_AGENT = "Naomi-Assistant/1.0 (personal voice assistant)"
        private const val CAR_ROUTER = "https://router.project-osrm.org"
        private const val FOOT_ROUTER = "https://routing.openstreetmap.de/routed-foot"
        // How far the search looks for the nearest place, in degrees either side (~1.3, 4.4, 13 km),
        // and for the shopping centre a store is in (~250 m).
        private val NEAREST_SPANS = listOf(0.012, 0.04, 0.12)
        private const val MALL_SPAN = 0.0025
        // A named place is looked for this far around the phone (~55 km) before anywhere.
        private const val LOCATE_SPAN = 0.5
        // Closer than this, the nearest place is given on foot.
        private const val WALKING_KM = 1.5
        private const val WALKING_KMH = 4.8

        // Street words as signs and timetables shorten them, spelled out for the voice.
        private val STREET_WORDS = mapOf(
            "st" to "Street", "rd" to "Road", "av" to "Avenue", "ave" to "Avenue", "pde" to "Parade",
            "hwy" to "Highway", "dr" to "Drive", "pl" to "Place", "cres" to "Crescent", "cct" to "Circuit",
            "blvd" to "Boulevard", "ln" to "Lane", "tce" to "Terrace", "cl" to "Close", "ct" to "Court",
            "gr" to "Grove", "sq" to "Square", "esp" to "Esplanade", "opp" to "opposite", "nr" to "near",
            "nth" to "North", "sth" to "South", "stn" to "Station", "wf" to "Wharf",
        )

        /**
         * A name as it should be said: "Iris St Opp Patanga Rd" → "Iris Street opposite Patanga
         * Road". "St" opening a name is Saint: "Mona Vale Rd at St Ives".
         */
        fun spelledOut(name: String): String =
            Regex("(^|\\b(?:at|opp|near|nr|before|after|and)\\s+)St\\.?(?=\\s+[A-Z])", RegexOption.IGNORE_CASE)
                .replace(name) { it.groupValues[1] + "Saint" }
                .let { Regex("\\b([A-Za-z]{2,4})\\b\\.?").replace(it) { m -> STREET_WORDS[m.groupValues[1].lowercase(Locale.ROOT)] ?: m.value } }

        /**
         * How a place named only by its brand (or not at all) is said: by the shopping centre it's
         * in, else its suburb, else its street — "the Woolworths at Forestway Shopping Centre",
         * "the Woolworths in Belrose", "the bus stop on Forest Way".
         */
        fun spokenPlace(name: String?, mall: String?, suburb: String?, road: String?, what: String): String {
            val it = "the " + (name?.trim()?.takeIf { n -> n.isNotEmpty() }?.let(::spelledOut) ?: kindOf(what))
            return when {
                !mall.isNullOrBlank() -> "$it at ${spelledOut(mall)}"
                !suburb.isNullOrBlank() -> "$it in $suburb"
                !road.isNullOrBlank() -> "$it on ${spelledOut(road)}"
                else -> it
            }
        }

        /** "the nearest bus stop" → "bus stop". */
        fun kindOf(what: String): String =
            what.trim().replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(closest|nearest)\\s+", RegexOption.IGNORE_CASE), "")

        fun nearestSentence(what: String, place: String, km: Double, minutes: Int, onFoot: Boolean): String {
            val time = if (onFoot) walkTime(minutes) else "around ${duration(minutes)} by car"
            return "The nearest ${kindOf(what)} is $place, about ${distance(km)} away${if (onFoot) "" else " by road"}, $time."
        }

        fun walkingSentence(place: String, km: Double, minutes: Int): String =
            "$place is about ${distance(km)} away on foot, ${walkTime(minutes)}."

        private fun distance(km: Double) = if (km < 1) "${metres(km)} metres" else "${km(km)} kilometres"

        /** "about a minute's walk", "around a 25-minute walk", "around an 8-minute walk", "around 1 hour and 5 minutes' walk". */
        private fun walkTime(minutes: Int): String = when {
            minutes <= 1 -> "about a minute's walk"
            minutes >= 60 -> "around ${duration(minutes)} on foot"
            else -> "around ${if (minutes == 8 || minutes == 11 || minutes == 18) "an" else "a"} $minutes-minute walk"
        }

        private fun metres(km: Double): Int {
            val m = km * 1000
            return if (m < 200) ((m / 10).roundToInt() * 10) else ((m / 50).roundToInt() * 50)
        }

        fun sentence(place: String, straightKm: Double, roadKm: Double?, minutes: Int?): String =
            if (roadKm != null && minutes != null)
                "$place is about ${km(roadKm)} kilometres away by road, around ${duration(minutes)} by car."
            else
                "$place is about ${km(straightKm)} kilometres from you in a straight line."

        private fun km(value: Double): String =
            if (value < 10) String.format(Locale.ENGLISH, "%.1f", value) else value.roundToInt().toString()

        private fun duration(minutes: Int): String = when {
            minutes < 1 -> "a minute"
            minutes < 60 -> "$minutes minutes"
            minutes % 60 == 0 -> "${minutes / 60} hours".replace("1 hours", "1 hour")
            else -> "${minutes / 60} hour${if (minutes >= 120) "s" else ""} and ${minutes % 60} minutes"
        }
    }
}
