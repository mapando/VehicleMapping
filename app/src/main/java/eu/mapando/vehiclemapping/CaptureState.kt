package eu.mapando.vehiclemapping

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class Settings(
    val mode: TriggerMode = TriggerMode.DISTANCE,
    val distanceMeters: Int = 15,
    val intervalSeconds: Int = 1,
    val skipWhenStationary: Boolean = true,
) {
    fun save(context: Context) {
        prefs(context).edit()
            .putString("mode", mode.name)
            .putInt("distanceMeters", distanceMeters)
            .putInt("intervalSeconds", intervalSeconds)
            .putBoolean("skipWhenStationary", skipWhenStationary)
            .apply()
    }

    companion object {
        private fun prefs(context: Context) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(context: Context): Settings {
            val p = prefs(context)
            val d = Settings()
            return Settings(
                mode = runCatching { TriggerMode.valueOf(p.getString("mode", null) ?: "") }
                    .getOrDefault(d.mode),
                distanceMeters = p.getInt("distanceMeters", d.distanceMeters),
                intervalSeconds = p.getInt("intervalSeconds", d.intervalSeconds),
                skipWhenStationary = p.getBoolean("skipWhenStationary", d.skipWhenStationary),
            )
        }
    }
}

data class CaptureStatus(
    val running: Boolean = false,
    val photos: Int = 0,
    val hasFix: Boolean = false,
    val accuracyM: Int = 0,
    val speedKmh: Int = 0,
    val sessionName: String? = null,
    val error: String? = null,
)

/** Shared between [CaptureService] (writer) and [MainActivity] (reader). */
object CaptureState {
    val status = MutableStateFlow(CaptureStatus())
}
