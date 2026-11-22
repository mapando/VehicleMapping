package eu.mapando.vehiclemapping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ServerConfig(
    val url: String = "",
    val token: String = "",
    val deleteAfterUpload: Boolean = false,
) {
    val isComplete get() = url.isNotBlank() && token.isNotBlank()

    fun save(context: Context) {
        prefs(context).edit()
            .putString("url", url.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putBoolean("deleteAfterUpload", deleteAfterUpload)
            .apply()
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("server", Context.MODE_PRIVATE)

        fun load(context: Context): ServerConfig {
            val p = prefs(context)
            return ServerConfig(
                url = p.getString("url", "") ?: "",
                token = p.getString("token", "") ?: "",
                deleteAfterUpload = p.getBoolean("deleteAfterUpload", false),
            )
        }
    }
}

data class UploadStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
)

enum class NetworkKind { NONE, WIFI, METERED }

/**
 * Foreground service that sends pending photos to the server, oldest first, one at a time.
 * It stops at the first photo that keeps failing, so nothing is skipped silently; the server
 * treats a repeated upload as a no-op, which makes retrying safe.
 */
class UploadService : LifecycleService() {

    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        val allowMetered = intent?.getBooleanExtra(EXTRA_ALLOW_METERED, false) ?: false
        val store = PhotoStore.get(this)
        store.refresh()
        val total = store.pending.value.count
        status.value = UploadStatus(running = true, total = total)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.upload_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0, total), type)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VehicleMapping:upload")
            .apply { acquire(MAX_RUN_MS) }

        lifecycleScope.launch(Dispatchers.IO) {
            val message = runUploads(store, ServerConfig.load(this@UploadService), allowMetered, total)
            status.update { it.copy(message = message) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Returns the message to show when the run ends. */
    private suspend fun runUploads(
        store: PhotoStore,
        config: ServerConfig,
        allowMetered: Boolean,
        total: Int,
    ): String {
        val endpoint = "${config.url}/api/photos".toHttpUrlOrNull()
            ?: return getString(R.string.upload_error_url)
        var done = 0
        var failures = 0

        while (lifecycleScope.isActive) {
            val photo = store.nextPending() ?: return getString(R.string.upload_finished, done)

            when (networkKind(this)) {
                NetworkKind.NONE -> return getString(R.string.upload_error_offline, done)
                NetworkKind.METERED -> if (!allowMetered) return getString(R.string.upload_paused_wifi, done)
                NetworkKind.WIFI -> Unit
            }

            val result = try {
                send(endpoint.toString(), config.token, photo)
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Photo no longer on the phone: ${photo.fileName}")
                store.markMissing(photo.id)
                continue
            } catch (e: IOException) {
                Log.w(TAG, "Upload failed", e)
                SendResult.Retry(e.message ?: e.javaClass.simpleName)
            }

            when (result) {
                SendResult.Ok -> {
                    failures = 0
                    store.markUploaded(photo.id)
                    if (config.deleteAfterUpload) {
                        runCatching { contentResolver.delete(photo.uri, null, null) }
                    }
                    done++
                    status.update { it.copy(done = done) }
                    if (done % 5 == 0) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(done, total))
                    }
                }
                is SendResult.Fatal -> return result.message
                is SendResult.Retry -> {
                    failures++
                    if (failures >= MAX_ATTEMPTS) return getString(R.string.upload_error_generic, result.detail, done)
                    delay(2_000L * failures)
                }
            }
        }
        return getString(R.string.upload_stopped, done)
    }

    private sealed interface SendResult {
        data object Ok : SendResult
        data class Retry(val detail: String) : SendResult
        data class Fatal(val message: String) : SendResult
    }

    private fun send(endpoint: String, token: String, photo: PendingPhoto): SendResult {
        // Opened here so a file deleted from the phone is detected before any network traffic.
        contentResolver.openInputStream(photo.uri)?.close() ?: throw FileNotFoundException()

        val image = object : RequestBody() {
            override fun contentType() = "image/jpeg".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                val input = contentResolver.openInputStream(photo.uri) ?: throw FileNotFoundException()
                input.source().use { sink.writeAll(it) }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("session", photo.session)
            .addFormDataPart("file_name", photo.fileName)
            .addFormDataPart("epoch_ms", photo.epochMs.toString())
            .addFormDataPart("lat", photo.lat.toString())
            .addFormDataPart("lon", photo.lon.toString())
        photo.accuracyM?.let { form.addFormDataPart("accuracy_m", it.toString()) }
        photo.speedMps?.let { form.addFormDataPart("speed_mps", it.toString()) }
        photo.bearingDeg?.let { form.addFormDataPart("bearing_deg", it.toString()) }
        photo.altitudeM?.let { form.addFormDataPart("altitude_m", it.toString()) }
        form.addFormDataPart("file", photo.fileName, image)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .post(form.build())
            .build()

        http.newCall(request).execute().use { response ->
            return when {
                response.isSuccessful -> SendResult.Ok
                response.code == 401 -> SendResult.Fatal(getString(R.string.upload_error_token))
                response.code in 500..599 || response.code == 429 -> SendResult.Retry("HTTP ${response.code}")
                else -> SendResult.Fatal(
                    getString(R.string.upload_error_rejected, response.code, photo.fileName)
                )
            }
        }
    }

    private fun buildNotification(done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, UploadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.upload_notif_title))
            .setContentText(getString(R.string.upload_progress, done, total))
            .setProgress(total, done, false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.upload_stop), stop)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        status.update {
            it.copy(running = false, message = it.message ?: getString(R.string.upload_stopped, it.done))
        }
        super.onDestroy() // Cancels the upload coroutine.
    }

    companion object {
        private const val TAG = "UploadService"
        private const val CHANNEL_ID = "upload"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "eu.mapando.vehiclemapping.UPLOAD_STOP"
        private const val EXTRA_ALLOW_METERED = "allowMetered"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_RUN_MS = 6L * 60 * 60 * 1000

        val status = MutableStateFlow(UploadStatus())

        fun start(context: Context, allowMetered: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UploadService::class.java).putExtra(EXTRA_ALLOW_METERED, allowMetered),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UploadService::class.java))
        }

        /** Wi-Fi and other unmetered networks count as WIFI; anything billed by volume as METERED. */
        fun networkKind(context: Context): NetworkKind {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkKind.NONE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
            return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                NetworkKind.WIFI
            } else {
                NetworkKind.METERED
            }
        }
    }
}
