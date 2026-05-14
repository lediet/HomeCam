package com.homecam.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.homecam.app.HomeCamApp
import com.homecam.app.R
import com.homecam.app.detection.EventDetector
import com.homecam.app.recorder.FrameBuffer
import com.homecam.app.recorder.VideoRecorder
import com.homecam.app.stream.MjpegStreamer
import com.homecam.app.web.CamWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        const val CHANNEL_ID = "homecam_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.homecam.app.START"
        const val ACTION_STOP = "com.homecam.app.STOP"
        const val ACTION_SWITCH_CAMERA = "com.homecam.app.SWITCH_CAMERA"
        const val ACTION_STATE_CHANGED = "com.homecam.app.STATE_CHANGED"

        val isRunning = AtomicBoolean(false)
        @Volatile
        var latestEventType: String? = null
        @Volatile
        var latestEventTime: Long = 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val streamer = MjpegStreamer()
    lateinit var frameBuffer: FrameBuffer
    lateinit var videoRecorder: VideoRecorder
    lateinit var eventDetector: EventDetector
    private var webServer: CamWebServer? = null

    @Volatile
    private var isRecordingEvent = false
    private var postEventFrames = mutableListOf<Pair<Long, ByteArray>>()
    private var currentEventType: String? = null
    private var postEventEndTime = 0L

    override fun onCreate() {
        Log.d(TAG, "onCreate() start")
        super.onCreate()
        Log.d(TAG, "onCreate() super done")

        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "cameraExecutor created")

        val fps = AppSettings.getFps(this)
        frameBuffer = FrameBuffer(fps * 10)
        Log.d(TAG, "frameBuffer created, capacity=${fps * 10}")

        try {
            val app = application as HomeCamApp
            videoRecorder = VideoRecorder(this, app.database.videoDao(), scope)
            Log.d(TAG, "videoRecorder created")
        } catch (e: Exception) {
            Log.e(TAG, "videoRecorder init FAILED", e)
        }

        eventDetector = EventDetector(this) { eventType -> onEventDetected(eventType) }
        Log.d(TAG, "eventDetector created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() action=${intent?.action}")
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SWITCH_CAMERA -> {
                Log.d(TAG, "ACTION_SWITCH_CAMERA received")
                try {
                    cameraProvider?.unbindAll()
                    bindCameraUseCases()
                    Log.d(TAG, "Camera switched")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera switch FAILED", e)
                }
                return START_STICKY
            }
        }

        try {
            startForeground()
            Log.d(TAG, "startForeground() done")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() FAILED", e)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning.set(true)
        sendBroadcast(Intent(ACTION_STATE_CHANGED))
        ServiceManager.instance = this

        try { acquireWakeLock(); Log.d(TAG, "wakeLock acquired") }
        catch (e: Exception) { Log.e(TAG, "acquireWakeLock FAILED", e) }

        try { initCamera(); Log.d(TAG, "initCamera() called") }
        catch (e: Exception) { Log.e(TAG, "initCamera FAILED", e) }

        try { initDetectors(); Log.d(TAG, "initDetectors() done") }
        catch (e: Exception) { Log.e(TAG, "initDetectors FAILED", e) }

        try { startWebServer(); Log.d(TAG, "startWebServer() done") }
        catch (e: Exception) { Log.e(TAG, "startWebServer FAILED", e) }

        return START_STICKY
    }

    private fun startForeground() {
        createNotificationChannel()

        val notifyIntent = Intent(this, com.homecam.app.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HomeCam::CameraWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "ProcessCameraProvider obtained")
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        try {
            val infos = provider.availableCameraInfos
            val cameraIndex = AppSettings.getCameraIndex(this).coerceIn(0, infos.size - 1)
            val idx = cameraIndex
            val cameraSelector = if (infos.isNotEmpty() && cameraIndex in infos.indices) {
                CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        listOfNotNull(cameras.getOrNull(idx))
                    }
                    .build()
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            provider.bindToLifecycle(
                this,
                cameraSelector,
                imageAnalysis
            )
            Log.d(TAG, "Camera bound to lifecycle, index=$cameraIndex/${infos.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Bind camera failed", e)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val buffer = imageProxy.planes[0].buffer
            val rowStride = imageProxy.planes[0].rowStride
            val pixelStride = imageProxy.planes[0].pixelStride

            val cropWidth = imageProxy.width
            val cropHeight = imageProxy.height

            buffer.rewind()
            val rowPixelCount = rowStride / pixelStride
            val bitmap = Bitmap.createBitmap(rowPixelCount, cropHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = if (rowPixelCount != cropWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, cropWidth, cropHeight).also { bitmap.recycle() }
            } else bitmap

            val rotation = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true).also {
                    croppedBitmap.recycle()
                }
            } else croppedBitmap

            val scale = AppSettings.getScaleFactor(this)
            val newW = (rotatedBitmap.width * scale).toInt()
            val newH = (rotatedBitmap.height * scale).toInt()
            val scaledBitmap = if (rotatedBitmap.width != newW || rotatedBitmap.height != newH) {
                Bitmap.createScaledBitmap(rotatedBitmap, newW, newH, true).also {
                    rotatedBitmap.recycle()
                }
            } else rotatedBitmap

            val jpegData = compressToJpeg(scaledBitmap, 75)
            val timestamp = System.currentTimeMillis()

            streamer.pushFrame(jpegData)
            frameBuffer.addFrame(timestamp, jpegData)

            if (AppSettings.isMotionDetectionEnabled(this) ||
                AppSettings.isDangerDetectionEnabled(this)) {
                eventDetector.analyzeFrame(scaledBitmap, timestamp)
            }

            var shouldFinish = false
            if (isRecordingEvent) {
                synchronized(postEventFrames) {
                    postEventFrames.add(timestamp to jpegData)
                    if (timestamp >= postEventEndTime) {
                        isRecordingEvent = false
                        shouldFinish = true
                    }
                }
            }

            if (shouldFinish) {
                finishEventVideo()
            }

            scaledBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun onEventDetected(eventType: String) {
        Log.d(TAG, "onEventDetected: $eventType")
        synchronized(postEventFrames) {
            if (isRecordingEvent) return
            isRecordingEvent = true
        }

        val saveDuration = AppSettings.getSaveDurationSec(this)
        val now = System.currentTimeMillis()
        currentEventType = eventType
        postEventEndTime = now + saveDuration * 1000L

        synchronized(postEventFrames) {
            postEventFrames.clear()
            frameBuffer.getLatestFrame()?.let { postEventFrames.add(now to it.second) }
        }

        latestEventType = eventType
        latestEventTime = now

        updateNotification(eventType)
    }

    private fun finishEventVideo() {
        val saveDuration = AppSettings.getSaveDurationSec(this)
        val eventTime = postEventEndTime - saveDuration * 1000L
        val preStartTime = eventTime - saveDuration * 1000L
        val preFrames = frameBuffer.getFramesInRange(preStartTime, eventTime)

        val eventType = currentEventType ?: return
        val framesToSave: List<Pair<Long, ByteArray>>
        synchronized(postEventFrames) {
            framesToSave = postEventFrames.toList()
            postEventFrames.clear()
        }
        currentEventType = null
        videoRecorder.saveEventVideo(eventType, preFrames, framesToSave)
    }

    private fun updateNotification(eventType: String) {
        val label = when (eventType) {
            "motion" -> getString(R.string.event_motion)
            "cry" -> getString(R.string.event_cry)
            "danger" -> getString(R.string.event_danger)
            else -> getString(R.string.event_unknown)
        }

        val notifyIntent = Intent(this, com.homecam.app.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_event_text, label))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun initDetectors() {
        if (AppSettings.isMotionDetectionEnabled(this) ||
            AppSettings.isDangerDetectionEnabled(this)) {
            Log.d(TAG, "Initializing visual detector...")
            eventDetector.initVisualDetector()
            Log.d(TAG, "Visual detector initialized")
        }
        if (AppSettings.isCryDetectionEnabled(this)) {
            Log.d(TAG, "Initializing audio detector...")
            eventDetector.initAudioDetector()
            eventDetector.startAudioDetection()
            Log.d(TAG, "Audio detector initialized and started")
        }
    }

    private fun startWebServer() {
        val port = AppSettings.getWebPort(this)
        webServer = CamWebServer(this, port).also { it.start() }
        Log.d(TAG, "Web server started on port $port")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
        isRunning.set(false)
        sendBroadcast(Intent(ACTION_STATE_CHANGED))
        ServiceManager.instance = null

        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        eventDetector.release()
        streamer.clear()
        webServer?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        cameraExecutor.shutdownNow()

        Log.d(TAG, "onDestroy() complete")
    }
}
