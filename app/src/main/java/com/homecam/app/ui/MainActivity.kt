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
import android.text.method.ScrollingMovementMethod
import android.text.SpannableString
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.method.LinkMovementMethod
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.homecam.app.R
import com.homecam.app.service.AppSettings
import com.homecam.app.service.ServiceManager
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
    private lateinit var eventLog: TextView
    private lateinit var rtspUrlText: TextView
    private lateinit var mjpgUrlText: TextView
    private var backPressedTime = 0L
    private val backPressInterval = 2000L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val eventRefreshRunnable = object : Runnable {
        override fun run() {
            if (CameraService.isRunning.get()) {
                updateUI()
            }
            uiHandler.postDelayed(this, 2000)
        }
    }

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
            eventLog = findViewById(R.id.event_log)
            eventLog.movementMethod = ScrollingMovementMethod()
            rtspUrlText = findViewById(R.id.rtsp_url_text)
            mjpgUrlText = findViewById(R.id.mjpg_url_text)
            Log.d(TAG, "findViewById all done")
        } catch (e: Exception) {
            Log.e(TAG, "findViewById FAILED", e)
            return
        }

        setupCameraSwitchButton()

        recordingSwitch.setOnCheckedChangeListener { _, isChecked ->
            CameraService.recordingEnabled = isChecked
        }

        findViewById<ImageButton>(R.id.open_folder_button).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
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

        findViewById<LinearLayout>(R.id.help_button).setOnClickListener {
            showAboutDialog()
        }

        Log.d(TAG, "onCreate() complete")
    }

    override fun onResume() {
        Log.d(TAG, "onResume()")
        super.onResume()
        registerReceiver(stateReceiver, IntentFilter(CameraService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        uiHandler.postDelayed(eventRefreshRunnable, 2000)
        updateUI()
    }

    override fun onBackPressed() {
        if (CameraService.isRunning.get()) {
            // 监控开启时直接退出
            super.onBackPressed()
        } else {
            // 监控关闭时双次返回退出
            if (System.currentTimeMillis() - backPressedTime > backPressInterval) {
                backPressedTime = System.currentTimeMillis()
                Toast.makeText(this, R.string.exit_confirm, Toast.LENGTH_SHORT).show()
            } else {
                CameraService.clearState()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finishAffinity()
                }
            }
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause()")
        super.onPause()
        uiHandler.removeCallbacks(eventRefreshRunnable)
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
                    "sleep" -> getString(R.string.event_sleep)
                    "wake_up" -> getString(R.string.event_wake_up)
                    "enter" -> getString(R.string.event_enter, CameraService.latestEventLabel.ifEmpty { "未知" })
                    "leave" -> getString(R.string.event_leave, CameraService.latestEventLabel.ifEmpty { "未知" })
                    "fall" -> getString(R.string.event_fall)
                    "get_up" -> getString(R.string.event_get_up)
                    "phone" -> getString(R.string.event_phone, CameraService.latestEventLabel.ifEmpty { "50%" })
                    else -> eventType
                }
                latestEvent.text = "$time $label"
            } else {
                latestEvent.text = getString(R.string.no_events)
            }

            updateEventLog()

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

            updateStreamUrls(running)
        } catch (e: Exception) {
            Log.e(TAG, "updateUI FAILED", e)
        }
    }

    private fun updateEventLog() {
        val events: List<CameraService.EventRecord>
        synchronized(CameraService.eventHistory) {
            events = CameraService.eventHistory.takeLast(100).reversed().toList()
        }
        if (events.isEmpty()) {
            eventLog.text = getString(R.string.no_events)
            return
        }
        val lines = events.joinToString("\n") { record ->
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(record.time))
            val label = when (record.type) {
                "motion" -> getString(R.string.event_motion)
                "cry" -> getString(R.string.event_cry)
                "sleep" -> getString(R.string.event_sleep)
                "wake_up" -> getString(R.string.event_wake_up)
                "enter" -> getString(R.string.event_enter, record.label.ifEmpty { "未知" })
                "leave" -> getString(R.string.event_leave, record.label.ifEmpty { "未知" })
                "fall" -> getString(R.string.event_fall)
                "get_up" -> getString(R.string.event_get_up)
                "phone" -> getString(R.string.event_phone, record.label.ifEmpty { "50%" })
                else -> record.type
            }
            "$time $label"
        }
        eventLog.text = lines
    }

    private fun updateStreamUrls(running: Boolean) {
        if (!running) {
            rtspUrlText.visibility = TextView.GONE
            mjpgUrlText.visibility = TextView.GONE
            return
        }
        val ip = getLocalIpAddress()
        if (AppSettings.isRtspEnabled(this)) {
            val rtspPort = AppSettings.getRtspPort(this)
            rtspUrlText.text = "RTSP: rtsp://$ip:$rtspPort/live"
            rtspUrlText.visibility = TextView.VISIBLE
        } else {
            rtspUrlText.visibility = TextView.GONE
        }
        if (AppSettings.isMjpgEnabled(this)) {
            val webPort = AppSettings.getWebPort(this)
            mjpgUrlText.text = "MJPEG: http://$ip:$webPort"
            mjpgUrlText.visibility = TextView.VISIBLE
        } else {
            mjpgUrlText.visibility = TextView.GONE
        }
    }


    private fun checkPermissionsAndStart() {
        // Check that at least one streaming method is enabled
        if (!AppSettings.isMjpgEnabled(this) && !AppSettings.isRtspEnabled(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.stream_warning_title))
                .setMessage(getString(R.string.stream_warning_message))
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

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

    private fun showAboutDialog() {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }

        // Open Source
        container.addView(TextView(this).apply {
            text = getString(R.string.dialog_open_source)
            textSize = 14f
            setLineSpacing(6f, 1f)
        })

        // Separator
        container.addView(TextView(this).apply {
            text = ""
            height = 32
        })

        // Donation
        container.addView(TextView(this).apply {
            text = getString(R.string.dialog_donation)
            textSize = 14f
            setLineSpacing(6f, 1f)
        })

        // QR Code
        container.addView(TextView(this).apply {
            text = getString(R.string.dialog_qr_description)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12, 0, 8)
        })

        try {
            val inputStream = assets.open("QR/QR.jpg")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            val qrView = ImageView(this).apply {
                setImageBitmap(bitmap)
                layoutParams = LinearLayout.LayoutParams(400, 400).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            container.addView(qrView)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load QR code", e)
        }

        // Separator
        container.addView(TextView(this).apply {
            text = ""
            height = 24
        })

        // GitHub link
        val githubText = getString(R.string.dialog_github)
        val githubSpannable = SpannableString(githubText)
        val urlStart = githubText.indexOf("https://")
        if (urlStart >= 0) {
            githubSpannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/lediet/HomeCam"))
                    startActivity(intent)
                }
            }, urlStart, githubText.length, 0)
        }
        container.addView(TextView(this).apply {
            text = githubSpannable
            textSize = 14f
            setLineSpacing(6f, 1f)
            movementMethod = LinkMovementMethod.getInstance()
        })

        // Separator
        container.addView(TextView(this).apply {
            text = ""
            height = 20
        })

        // Feedback
        container.addView(TextView(this).apply {
            text = getString(R.string.dialog_feedback)
            textSize = 14f
            setLineSpacing(6f, 1f)
        })

        // Separator
        container.addView(TextView(this).apply {
            text = ""
            height = 20
        })

        // Disclaimer
        container.addView(TextView(this).apply {
            text = getString(R.string.dialog_disclaimer)
            textSize = 12f
            setTextColor(0xff888888.toInt())
            setLineSpacing(4f, 1f)
        })

        scrollView.addView(container)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_about_title))
            .setView(scrollView)
            .setPositiveButton("知道了", null)
            .show()
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
