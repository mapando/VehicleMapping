package eu.mapando.vehiclemapping

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var intervalLayout: TextInputLayout
    private lateinit var interval: TextInputEditText
    private lateinit var skipStationary: MaterialSwitch
    private lateinit var cameraNote: TextView
    private lateinit var shutter: Spinner
    private lateinit var focusInfinity: MaterialSwitch
    private lateinit var startStop: Button
    private lateinit var uploadStatus: TextView
    private lateinit var upload: Button

    private var settings = Settings()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (CaptureService.hasPermissions(this)) {
                CaptureService.start(this)
            } else {
                status.setText(R.string.permissions_needed)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        modeGroup = findViewById(R.id.modeGroup)
        intervalLayout = findViewById(R.id.intervalLayout)
        interval = findViewById(R.id.interval)
        skipStationary = findViewById(R.id.skipStationary)
        cameraNote = findViewById(R.id.cameraNote)
        shutter = findViewById(R.id.shutter)
        focusInfinity = findViewById(R.id.focusInfinity)
        startStop = findViewById(R.id.startStop)
        uploadStatus = findViewById(R.id.uploadStatus)
        upload = findViewById(R.id.upload)
        findViewById<Button>(R.id.server).setOnClickListener { showServerDialog() }
        upload.setOnClickListener { onUploadClicked() }

        val store = PhotoStore.get(this)
        lifecycleScope.launch(Dispatchers.IO) { store.refresh() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(UploadService.status, store.pending, CaptureState.status) { u, p, c -> Triple(u, p, c) }
                    .collect { (u, p, c) -> renderUpload(u, p, c.running) }
            }
        }

        settings = Settings.load(this)
        modeGroup.check(if (settings.mode == TriggerMode.DISTANCE) R.id.modeDistance else R.id.modeTime)
        skipStationary.isChecked = settings.skipWhenStationary
        shutter.setSelection(SHUTTER_DENOMINATORS.indexOf(settings.shutterDenominator).coerceAtLeast(0))
        focusInfinity.isChecked = settings.focusAtInfinity
        showModeFields()

        modeGroup.setOnCheckedChangeListener { _, _ ->
            settings = readSettings()
            showModeFields()
        }

        startStop.setOnClickListener {
            if (CaptureState.status.value.running) {
                CaptureService.stop(this)
            } else {
                settings = readSettings()
                settings.save(this)
                showModeFields()
                CaptureState.status.value = CaptureStatus()
                requestPermissionsAndStart()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureState.status.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CaptureService.setPreviewSurface(preview.surfaceProvider)
    }

    override fun onStop() {
        CaptureService.setPreviewSurface(null)
        super.onStop()
    }

    private fun requestPermissionsAndStart() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionRequest.launch(wanted.toTypedArray())
    }

    /** Reads the form, keeping the stored value of the field the current mode does not show. */
    private fun readSettings(): Settings {
        val shownMode = settings.mode
        val typed = interval.text?.toString()?.toIntOrNull()
        val newMode =
            if (modeGroup.checkedRadioButtonId == R.id.modeTime) TriggerMode.TIME else TriggerMode.DISTANCE
        return settings.copy(
            mode = newMode,
            distanceMeters = if (shownMode == TriggerMode.DISTANCE) typed?.coerceIn(1, 1000)
                ?: settings.distanceMeters else settings.distanceMeters,
            intervalSeconds = if (shownMode == TriggerMode.TIME) typed?.coerceIn(1, 3600)
                ?: settings.intervalSeconds else settings.intervalSeconds,
            skipWhenStationary = skipStationary.isChecked,
            shutterDenominator = SHUTTER_DENOMINATORS.getOrElse(shutter.selectedItemPosition) { 0 },
            focusAtInfinity = focusInfinity.isChecked,
        )
    }

    private fun showModeFields() {
        val distance = settings.mode == TriggerMode.DISTANCE
        intervalLayout.hint =
            getString(if (distance) R.string.interval_hint_distance else R.string.interval_hint_time)
        interval.setText((if (distance) settings.distanceMeters else settings.intervalSeconds).toString())
        // In distance mode a stationary car never triggers, so the switch only matters in time mode.
        skipStationary.visibility = if (distance) View.GONE else View.VISIBLE
    }

    private fun render(s: CaptureStatus) {
        startStop.setText(if (s.running) R.string.stop else R.string.start)
        preview.visibility = if (s.running) View.VISIBLE else View.INVISIBLE
        for (v in listOf(modeGroup, interval, skipStationary, shutter, focusInfinity)) v.isEnabled = !s.running
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !s.running

        if (s.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        cameraNote.text = s.cameraNote
        cameraNote.visibility = if (s.running && s.cameraNote != null) View.VISIBLE else View.GONE

        status.text = when {
            s.error != null -> s.error
            s.running && !s.hasFix -> getString(R.string.status_waiting_gps)
            s.running -> getString(R.string.status_running, s.photos, s.accuracyM, s.speedKmh)
            s.sessionName != null -> getString(R.string.status_last_session, s.photos, s.sessionName)
            else -> getString(R.string.status_idle)
        }
    }

    private fun renderUpload(u: UploadStatus, pending: PendingSummary, capturing: Boolean) {
        upload.text = if (u.running) {
            getString(R.string.upload_button_running, u.done, u.total)
        } else {
            getString(R.string.upload_button, pending.count)
        }
        upload.isEnabled = u.running || !capturing
        uploadStatus.text = u.message
        uploadStatus.visibility = if (!u.running && u.message != null) View.VISIBLE else View.GONE
    }

    private fun onUploadClicked() {
        if (UploadService.status.value.running) {
            UploadService.stop(this)
            return
        }
        if (!ServerConfig.load(this).isComplete) {
            showServerDialog()
            return
        }
        val pending = PhotoStore.get(this).pending.value
        when {
            pending.count == 0 -> showUploadMessage(getString(R.string.upload_nothing))
            else -> when (UploadService.networkKind(this)) {
                NetworkKind.NONE -> showUploadMessage(getString(R.string.upload_no_network))
                NetworkKind.WIFI -> UploadService.start(this, allowMetered = false)
                NetworkKind.METERED -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.upload_metered_title)
                    .setMessage(
                        getString(
                            R.string.upload_metered_message,
                            pending.count,
                            Formatter.formatShortFileSize(this, pending.bytes),
                        )
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.upload_metered_confirm) { _, _ ->
                        UploadService.start(this, allowMetered = true)
                    }
                    .show()
            }
        }
    }

    private fun showUploadMessage(message: String) {
        UploadService.status.value = UploadStatus(message = message)
    }

    private fun showServerDialog() {
        val config = ServerConfig.load(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val url = EditText(this).apply {
            setHint(R.string.server_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(config.url)
        }
        val token = EditText(this).apply {
            setHint(R.string.server_token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(config.token)
        }
        val delete = MaterialSwitch(this).apply {
            setText(R.string.server_delete_after)
            isChecked = config.deleteAfterUpload
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(url)
            addView(token)
            addView(delete)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_title)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                ServerConfig(url.text.toString(), token.text.toString(), delete.isChecked).save(this)
            }
            .show()
    }

    private companion object {
        /** Matches the order of R.array.shutter_options; 0 means automatic. */
        val SHUTTER_DENOMINATORS = listOf(0, 500, 1000, 2000)
    }
}
