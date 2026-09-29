package com.naomi.assistant

import android.content.Context
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * "How far am I from X?" — finds X near where the phone is (the platform geocoder, biased to
 * the surrounding area), then the driving distance and time from OSRM's free public router,
 * falling back to the straight-line distance. Network-bound: call off the main thread.
 */
class DistanceClient(private val context: Context) {

    data class Found(val name: String, val lat: Double, val lon: Double)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** [query] as a place, preferring matches within about 100 km of [near]; null if none. */
    suspend fun locate(query: String, near: DeviceLocation.Place): Found? = withContext(Dispatchers.IO) {
        if (query.isBlank() || !Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        runCatching {
            @Suppress("DEPRECATION") // the listener variant is API 33+; this runs off the main thread
            (geocoder.getFromLocationName(query, 1, near.lat - 1, near.lon - 1, near.lat + 1, near.lon + 1)
                ?.firstOrNull()
                ?: geocoder.getFromLocationName(query, 1)?.firstOrNull())
                ?.let { Found(it.locality ?: it.featureName ?: query, it.latitude, it.longitude) }
        }.getOrNull()
    }

    /** One spoken sentence: how far [to] is from [from], by road when the router answers. */
    suspend fun describe(from: DeviceLocation.Place, to: Found): String = withContext(Dispatchers.IO) {
        val straight = FloatArray(1).also { Location.distanceBetween(from.lat, from.lon, to.lat, to.lon, it) }[0] / 1000.0
        val route = runCatching { drive(from, to) }.getOrNull()
        sentence(to.name, straight, route?.first, route?.second)
    }

    /** Driving (km, minutes) via OSRM, or null. */
    private fun drive(from: DeviceLocation.Place, to: Found): Pair<Double, Int>? {
        val url = "https://router.project-osrm.org/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}?overview=false"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val route = JSONObject(r.body?.string().orEmpty()).optJSONArray("routes")?.optJSONObject(0) ?: return null
            return route.optDouble("distance") / 1000.0 to (route.optDouble("duration") / 60.0).roundToInt()
        }
    }

    companion object {
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
