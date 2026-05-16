package com.homecam.app.ui

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.homecam.app.R
import com.homecam.app.service.AppSettings
import com.homecam.app.service.CameraInfo
import com.homecam.app.service.CameraService
import com.homecam.app.service.CameraUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CameraService.ACTION_STATE_CHANGED) {
                Log.d(TAG, "Received state change broadcast, updating UI")
                updateUI()
            }
        }
    }

    private lateinit var statusIndicator: ImageView
    private lateinit var statusText: TextView
    private lateinit var ipAddress: TextView
    private lateinit var toggleButton: Button
    private lateinit var cameraSwitchButton: ImageButton
    private lateinit var latestEvent: TextView
    private lateinit var detectionTime: TextView
    private lateinit var recordingSwitch: androidx.appcompat.widget.SwitchCompat

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permission result: $permissions")
        // Only CAMERA and RECORD_AUDIO are essential for the service to work.
        // POST_NOTIFICATIONS is optional — foreground service notifications
        // are shown regardless on Android 13+.
        val essentialGranted = permissions
            .filterKeys { it == Manifest.permission.CAMERA || it == Manifest.permission.RECORD_AUDIO }
            .values.all { it }
        if (essentialGranted) {
            startCameraService()
        } else {
            statusText.text = getString(R.string.permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() start")
        super.onCreate(savedInstanceState)
        Log.d(TAG, "super.onCreate() done, about to setContentView")

        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "setContentView done")
        } catch (e: Exception) {
            Log.e(TAG, "setContentView FAILED", e)
            return
        }

        try {
            setSupportActionBar(findViewById(R.id.toolbar))
            Log.d(TAG, "setSupportActionBar done")
        } catch (e: Exception) {
            Log.e(TAG, "setSupportActionBar FAILED", e)
        }

        try {
            statusIndicator = findViewById(R.id.status_indicator)
            statusText = findViewById(R.id.status_text)
            ipAddress = findViewById(R.id.ip_address)
            toggleButton = findViewById(R.id.toggle_button)
            cameraSwitchButton = findViewById(R.id.camera_switch_button)
            latestEvent = findViewById(R.id.latest_event)
            detectionTime = findViewById(R.id.detection_time)
            recordingSwitch = findViewById(R.id.recording_switch)
            Log.d(TAG, "findViewById all done")
        } catch (e: Exception) {
            Log.e(TAG, "findViewById FAILED", e)
            return
        }

        setupCameraSwitchButton()

        recordingSwitch.setOnCheckedChangeListener { _, isChecked ->
            CameraService.recordingEnabled = isChecked
        }

        toggleButton.setOnClickListener {
            if (CameraService.isRunning.get()) {
                stopCameraService()
            } else {
                checkPermissionsAndStart()
            }
        }

        findViewById<LinearLayout>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        Log.d(TAG, "onCreate() complete")
    }

    override fun onResume() {
        Log.d(TAG, "onResume()")
        super.onResume()
        registerReceiver(stateReceiver, IntentFilter(CameraService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        updateUI()
    }

    override fun onPause() {
        Log.d(TAG, "onPause()")
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    private fun setupCameraSwitchButton() {
        cameraSwitchButton.setOnClickListener {
            showCameraSelectorDialog()
        }
        updateCameraSwitchIcon()
    }

    private fun updateCameraSwitchIcon() {
        val count = CameraUtils.enumerateCameras(this).size
        val cameraId = AppSettings.getCameraId(this)
        cameraSwitchButton.contentDescription = if (count > 0) {
            "当前摄像头:$cameraId"
        } else {
            getString(R.string.camera_select)
        }
    }

    private fun showCameraSelectorDialog() {
        val cameras = CameraUtils.enumerateCameras(this)
        if (cameras.isEmpty()) return

        val currentCameraId = AppSettings.getCameraId(this)
        val currentIdx = cameras.indexOfFirst { it.cameraId == currentCameraId }.coerceIn(0, cameras.size - 1)
        val names = cameras.map { it.label }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.camera_select)
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                AppSettings.setCameraIndex(this, cameras[which].index)
                AppSettings.setCameraId(this, cameras[which].cameraId)
                AppSettings.setLogicalCameraId(this, cameras[which].logicalCameraId)
                updateCameraSwitchIcon()
                if (CameraService.isRunning.get()) {
                    val intent = Intent(this, CameraService::class.java).apply {
                        action = CameraService.ACTION_SWITCH_CAMERA
                    }
                    startService(intent)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateUI() {
        updateCameraSwitchIcon()
        try {
            val running = CameraService.isRunning.get()
            if (running) {
                statusIndicator.setImageResource(R.drawable.ic_status_on)
                statusText.text = getString(R.string.status_running)
                toggleButton.text = getString(R.string.stop_monitoring)
                val ip = getLocalIpAddress()
                val port = AppSettings.getWebPort(this)
                ipAddress.text = "http://$ip:$port"
                ipAddress.visibility = TextView.VISIBLE
            } else {
                statusIndicator.setImageResource(R.drawable.ic_status_off)
                statusText.text = getString(R.string.status_stopped)
                toggleButton.text = getString(R.string.start_monitoring)
                ipAddress.visibility = TextView.GONE
            }

            val eventType = CameraService.latestEventType
            val eventTime = CameraService.latestEventTime
            if (eventType != null && eventTime > 0) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(eventTime))
                val label = when (eventType) {
                    "motion" -> getString(R.string.event_motion)
                    "cry" -> getString(R.string.event_cry)
                    "danger" -> getString(R.string.event_danger)
                    else -> eventType
                }
                latestEvent.text = "$time $label"
            } else {
                latestEvent.text = getString(R.string.no_events)
            }

            val detectMs = CameraService.latestDetectionMs
            if (detectMs > 0 && CameraService.isRunning.get()) {
                val interval = AppSettings.getDetectionIntervalFrames(this)
                detectionTime.text = "检测: ${detectMs}ms | 间隔: ${interval}帧"
                detectionTime.visibility = TextView.VISIBLE
            } else {
                detectionTime.visibility = TextView.GONE
            }

            recordingSwitch.isChecked = CameraService.recordingEnabled
            recordingSwitch.visibility = if (CameraService.isRunning.get()) TextView.VISIBLE else TextView.GONE
        } catch (e: Exception) {
            Log.e(TAG, "updateUI FAILED", e)
        }
    }

    private fun checkPermissionsAndStart() {
        val ungranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "checkPermissionsAndStart: ungranted=$ungranted")

        if (ungranted.isEmpty()) {
            startCameraService()
        } else {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun startCameraService() {
        Log.d(TAG, "startCameraService()")
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) updateUI()
        }, 500)
    }

    private fun stopCameraService() {
        Log.d(TAG, "stopCameraService()")
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(intent)
        updateUI()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) updateUI()
        }, 500)
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }
}
