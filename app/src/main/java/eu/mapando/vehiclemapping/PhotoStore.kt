package eu.mapando.vehiclemapping

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

data class PendingPhoto(
    val id: Long,
    val session: String,
    val fileName: String,
    val uri: Uri,
    val sizeBytes: Long,
    val epochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val altitudeM: Double?,
)

data class PendingSummary(val count: Int = 0, val bytes: Long = 0)

/** Local record of every captured photo and whether it still has to be uploaded. */
class PhotoStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "photos.db", null, 1) {

    val pending = MutableStateFlow(PendingSummary())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photos (
                   id INTEGER PRIMARY KEY,
                   session TEXT NOT NULL,
                   file_name TEXT NOT NULL,
                   uri TEXT NOT NULL,
                   size_bytes INTEGER NOT NULL,
                   epoch_ms INTEGER NOT NULL,
                   lat REAL NOT NULL,
                   lon REAL NOT NULL,
                   accuracy_m REAL,
                   speed_mps REAL,
                   bearing_deg REAL,
                   altitude_m REAL,
                   state INTEGER NOT NULL DEFAULT $STATE_PENDING
               )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(session: String, fileName: String, uri: Uri, sizeBytes: Long, epochMs: Long, loc: Location) {
        writableDatabase.insert("photos", null, ContentValues().apply {
            put("session", session)
            put("file_name", fileName)
            put("uri", uri.toString())
            put("size_bytes", sizeBytes)
            put("epoch_ms", epochMs)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            if (loc.hasAccuracy()) put("accuracy_m", loc.accuracy.toDouble())
            if (loc.hasSpeed()) put("speed_mps", loc.speed.toDouble())
            if (loc.hasBearing()) put("bearing_deg", loc.bearing.toDouble())
            if (loc.hasAltitude()) put("altitude_m", loc.altitude)
        })
        refresh()
    }

    fun nextPending(): PendingPhoto? =
        readableDatabase.rawQuery(
            "SELECT id, session, file_name, uri, size_bytes, epoch_ms, lat, lon, accuracy_m, speed_mps, " +
                "bearing_deg, altitude_m FROM photos WHERE state = $STATE_PENDING ORDER BY id LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) return null
            fun optDouble(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
            PendingPhoto(
                id = c.getLong(0),
                session = c.getString(1),
                fileName = c.getString(2),
                uri = Uri.parse(c.getString(3)),
                sizeBytes = c.getLong(4),
                epochMs = c.getLong(5),
                lat = c.getDouble(6),
                lon = c.getDouble(7),
                accuracyM = optDouble(8),
                speedMps = optDouble(9),
                bearingDeg = optDouble(10),
                altitudeM = optDouble(11),
            )
        }

    fun markUploaded(id: Long) = setState(id, STATE_UPLOADED)

    /** The file was removed from the phone before it could be uploaded. */
    fun markMissing(id: Long) = setState(id, STATE_MISSING)

    private fun setState(id: Long, state: Int) {
        writableDatabase.execSQL("UPDATE photos SET state = $state WHERE id = $id")
        refresh()
    }

    fun refresh() {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM photos WHERE state = $STATE_PENDING", null
        ).use { c ->
            c.moveToFirst()
            pending.value = PendingSummary(c.getInt(0), c.getLong(1))
        }
    }

    companion object {
        private const val STATE_PENDING = 0
        private const val STATE_UPLOADED = 1
        private const val STATE_MISSING = 2

        @Volatile
        private var instance: PhotoStore? = null

        fun get(context: Context): PhotoStore =
            instance ?: synchronized(this) {
                instance ?: PhotoStore(context.applicationContext).also { instance = it }
            }
    }
}
