package com.naomi.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Where the phone is — for the weather without a city, distances, and so the brain knows the
 * neighbourhood. Uses the last known fix, or a fresh balanced-power one (up to 5 s), and names
 * it via the platform geocoder when it can. Cached for a few minutes, since every smart-mode
 * turn asks. Returns null without location permission or a fix.
 */
object DeviceLocation {

    data class Place(val lat: Double, val lon: Double, val name: String?)

    private const val CACHE_MS = 5 * 60_000L
    @Volatile private var cached: Pair<Long, Place>? = null

    suspend fun current(context: Context): Place? {
        cached?.let { (at, place) -> if (System.currentTimeMillis() - at < CACHE_MS) return place }
        return locate(context)?.also { cached = System.currentTimeMillis() to it }
    }

    private suspend fun locate(context: Context): Place? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val location = try {
            fused.lastLocation.awaitOrNull()
                ?: withTimeoutOrNull(5_000) {
                    fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).awaitOrNull()
                }
        } catch (e: SecurityException) {
            null
        } ?: return null
        val name = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION") // the listener variant is API 33+; this runs off the main thread
                Geocoder(context, Locale.ENGLISH).getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()
        }
        return Place(location.latitude, location.longitude, name)
    }

    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
        addOnCanceledListener { cont.resume(null) }
    }
}
