package com.lnsgroup.elise.watch.health

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log

private const val TAG = "WatchLocation"

object WatchLocation {
    @Volatile private var cachedLat: Double? = null
    @Volatile private var cachedLon: Double? = null
    @Volatile private var cacheMs: Long = 0L
    private const val CACHE_TTL_MS = 5 * 60_000L // 5 min

    fun getLastKnown(context: Context): Pair<Double, Double>? {
        // Retourner le cache si récent
        if (cachedLat != null && System.currentTimeMillis() - cacheMs < CACHE_TTL_MS) {
            return Pair(cachedLat!!, cachedLon!!)
        }

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best: Location? = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider ->
                try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }.minByOrNull { loc -> -(loc.accuracy) } // meilleure précision

            if (best != null) {
                cachedLat = best.latitude
                cachedLon = best.longitude
                cacheMs = System.currentTimeMillis()
                Log.d(TAG, "Location: ${best.latitude}, ${best.longitude} (±${best.accuracy}m)")
                Pair(best.latitude, best.longitude)
            } else {
                Log.w(TAG, "No location available")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location error: ${e.message}")
            null
        }
    }
}
