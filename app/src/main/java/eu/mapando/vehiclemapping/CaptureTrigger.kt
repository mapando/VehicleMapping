package eu.mapando.vehiclemapping

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class TriggerMode { DISTANCE, TIME }

/** A GPS fix reduced to what the trigger needs. [speedMps] is null when the fix carries no speed. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val speedMps: Float? = null,
)

/**
 * Decides when to take the next photo. Pure logic, no Android dependencies.
 *
 * A photo without a fresh position is useless for the map, so both modes require a fix
 * no older than [MAX_FIX_AGE_MS].
 */
class CaptureTrigger(
    private val mode: TriggerMode,
    private val intervalSeconds: Int,
    private val distanceMeters: Int,
    private val skipWhenStationary: Boolean,
) {
    private var lastShotTimeMs: Long? = null
    private var lastShotFix: Fix? = null

    fun shouldCapture(nowMs: Long, fix: Fix?): Boolean {
        if (fix == null || nowMs - fix.timeMs > MAX_FIX_AGE_MS) return false
        return when (mode) {
            TriggerMode.DISTANCE -> {
                val last = lastShotFix ?: return true
                distanceMeters(last, fix) >= distanceMeters
            }
            TriggerMode.TIME -> {
                if (skipWhenStationary && fix.speedMps != null && fix.speedMps < MIN_MOVING_SPEED_MPS) {
                    return false
                }
                val last = lastShotTimeMs ?: return true
                nowMs - last >= intervalSeconds * 1000L
            }
        }
    }

    fun markCaptured(nowMs: Long, fix: Fix) {
        lastShotTimeMs = nowMs
        lastShotFix = fix
    }

    companion object {
        const val MAX_FIX_AGE_MS = 5_000L
        const val MIN_MOVING_SPEED_MPS = 1.0f
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Haversine distance in metres. */
        fun distanceMeters(a: Fix, b: Fix): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h))
        }
    }
}
