package eu.mapando.vehiclemapping

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera and the GPS while a capture session runs,
 * so it keeps shooting with the screen off.
 *
 * Each session writes to shared storage, where file managers and the gallery can see it:
 * JPEG files (with GPS EXIF) in `Pictures/VehicleMapping/<session>/` and an `index.csv`
 * with one row per photo in `Documents/VehicleMapping/<session>/`.
 */
class CaptureService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var cameraExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null

    private var started = false
    private var imageCapture: ImageCapture? = null
    private var trigger: CaptureTrigger? = null
    private var sessionName: String? = null
    private var index: Writer? = null

    // Touched on the main thread only.
    private var lastLocation: Location? = null
    private var captureInFlight = false
    private var photos = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            CaptureState.status.update {
                it.copy(
                    hasFix = true,
                    accuracyM = loc.accuracy.toInt(),
                    speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0,
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY

        if (!hasPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        startInForeground()
        startSession()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, photos))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission") // Checked by hasPermissions() before the session starts.
    private fun startSession() {
        val settings = Settings.load(this)
        trigger = CaptureTrigger(
            settings.mode, settings.intervalSeconds, settings.distanceMeters, settings.skipWhenStationary
        )

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionName = name
        index = try {
            val uri = contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "index.csv")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER/$name")
                },
            )
            contentResolver.openOutputStream(uri!!)!!.bufferedWriter().apply {
                write("file,time_utc,epoch_ms,lat,lon,accuracy_m,speed_mps,bearing_deg,altitude_m\n")
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create index.csv", e)
            fail(getString(R.string.error_storage, e.message ?: e.javaClass.simpleName))
            return
        }
        photos = 0
        CaptureState.status.value = CaptureStatus(running = true, sessionName = name)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VehicleMapping:capture")
            .apply { acquire(MAX_SESSION_MS) }

        fused.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build(),
            locationCallback,
            Looper.getMainLooper(),
        )

        bindCamera()

        lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(90)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .build()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = surfaceProvider
                    this.preview = it
                }
                provider.unbindAll()
                val camera =
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                val note = applyAntiBlur(camera, Settings.load(this))
                CaptureState.status.update { it.copy(cameraNote = note) }
                imageCapture = capture
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                fail(getString(R.string.error_camera, e.message ?: e.javaClass.simpleName))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Applies the anti-blur settings the device supports and returns a line describing the outcome.
     *
     * A fixed shutter speed with automatic ISO needs the exposure-time priority mode added in
     * Android 16. Where it is missing, the sports scene mode is the closest hint the camera accepts.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyAntiBlur(camera: Camera, settings: Settings): String? {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val options = CaptureRequestOptions.Builder()
        val notes = mutableListOf<String>()

        if (settings.shutterDenominator > 0) {
            val wantedNs = 1_000_000_000L / settings.shutterDenominator
            val range = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val exposureNs = if (range != null) wantedNs.coerceIn(range.lower, range.upper) else wantedNs
            val sceneModes =
                info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)
            val sportsMode = listOf(CameraMetadata.CONTROL_SCENE_MODE_SPORTS, CameraMetadata.CONTROL_SCENE_MODE_ACTION)
                .firstOrNull { it in sceneModes }

            if (supportsShutterPriority(info)) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                if (Build.VERSION.SDK_INT >= 36) {
                    options.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                        CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
                    )
                }
                options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                notes += getString(R.string.note_shutter_fixed, (1_000_000_000L / exposureNs).toInt())
            } else if (sportsMode != null) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                options.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, sportsMode)
                notes += getString(R.string.note_shutter_sports)
            } else {
                notes += getString(R.string.note_shutter_unsupported)
            }
        }

        if (settings.focusAtInfinity) {
            val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocus > 0f) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                notes += getString(R.string.note_focus_infinity)
            }
        }

        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
        return notes.joinToString(" · ").ifEmpty { null }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun supportsShutterPriority(info: Camera2CameraInfo): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
        return modes?.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY) == true
    }

    private fun tick() {
        val capture = imageCapture ?: return
        val trigger = trigger ?: return
        val name = sessionName ?: return
        if (captureInFlight) return

        val loc = lastLocation
        val now = SystemClock.elapsedRealtime()
        val fix = loc?.let {
            Fix(
                lat = it.latitude,
                lon = it.longitude,
                timeMs = it.elapsedRealtimeNanos / 1_000_000,
                speedMps = if (it.hasSpeed()) it.speed else null,
            )
        }
        if (loc == null || fix == null || !trigger.shouldCapture(now, fix)) {
            if (fix == null || now - fix.timeMs > CaptureTrigger.MAX_FIX_AGE_MS) {
                CaptureState.status.update { it.copy(hasFix = false) }
            }
            return
        }

        if (StatFs(Environment.getExternalStorageDirectory().path).availableBytes < MIN_FREE_BYTES) {
            fail(getString(R.string.error_disk))
            return
        }

        trigger.markCaptured(now, fix)
        captureInFlight = true

        val wallMs = System.currentTimeMillis()
        val fileName = "img_%05d.jpg".format(Locale.US, photos + 1)
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER/$name")
            },
        )
            .setMetadata(ImageCapture.Metadata().apply { location = loc })
            .build()

        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val row = csvRow(fileName, wallMs, loc)
                ContextCompat.getMainExecutor(this@CaptureService).execute {
                    captureInFlight = false
                    val writer = index ?: return@execute
                    writer.write(row)
                    writer.flush()
                    photos++
                    CaptureState.status.update { it.copy(photos = photos) }
                    if (photos % 10 == 0) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification())
                    }
                }
            }

            override fun onError(e: ImageCaptureException) {
                Log.w(TAG, "Capture failed", e)
                ContextCompat.getMainExecutor(this@CaptureService).execute { captureInFlight = false }
            }
        })
    }

    private fun fail(message: String) {
        CaptureState.status.update { it.copy(error = message) }
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        preview = null
        fused.removeLocationUpdates(locationCallback)
        runCatching { index?.close() }
        index = null
        wakeLock?.takeIf { it.isHeld }?.release()
        cameraExecutor.shutdown()
        CaptureState.status.update { it.copy(running = false, hasFix = false) }
        super.onDestroy() // Destroys the lifecycle, which unbinds the camera and stops the ticker.
    }

    private var preview: Preview? = null

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "eu.mapando.vehiclemapping.STOP"
        private const val FOLDER = "VehicleMapping"
        private const val TICK_MS = 100L
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        private const val MAX_SESSION_MS = 12L * 60 * 60 * 1000

        private var instance: CaptureService? = null
        private var surfaceProvider: Preview.SurfaceProvider? = null

        /** Called by the activity (main thread) to show or hide the live preview. */
        fun setPreviewSurface(provider: Preview.SurfaceProvider?) {
            surfaceProvider = provider
            instance?.preview?.surfaceProvider = provider
        }

        fun hasPermissions(context: Context): Boolean =
            listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }

        internal fun csvRow(fileName: String, wallMs: Long, loc: Location): String {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(wallMs))
            fun opt(has: Boolean, value: Double, pattern: String) =
                if (has) pattern.format(Locale.US, value) else ""
            return listOf(
                fileName,
                iso,
                wallMs.toString(),
                "%.7f".format(Locale.US, loc.latitude),
                "%.7f".format(Locale.US, loc.longitude),
                opt(loc.hasAccuracy(), loc.accuracy.toDouble(), "%.1f"),
                opt(loc.hasSpeed(), loc.speed.toDouble(), "%.2f"),
                opt(loc.hasBearing(), loc.bearing.toDouble(), "%.1f"),
                opt(loc.hasAltitude(), loc.altitude, "%.1f"),
            ).joinToString(",") + "\n"
        }
    }
}
