package com.homecam.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import com.homecam.app.stream.H264Encoder
import com.homecam.app.stream.MjpegStreamer
import com.homecam.app.stream.RtspServer
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
        const val ACTION_CAMERA_POWER = "com.homecam.app.CAMERA_POWER"
        const val ACTION_STATE_CHANGED = "com.homecam.app.STATE_CHANGED"

        val isRunning = AtomicBoolean(false)
        val cameraPoweredOn = AtomicBoolean(true)
        @Volatile
        var latestEventType: String? = null
        @Volatile
        var latestEventTime: Long = 0L
        @Volatile
        var latestEventLabel: String = ""
        @Volatile
        var latestDetectionMs: Long = 0L
        @Volatile
        var recordingEnabled: Boolean = true
        @Volatile
        var batteryLevel: Int = -1

        // In-memory event history (always recorded, regardless of recordingEnabled)
        val eventHistory = mutableListOf<EventRecord>()

        fun clearState() {
            isRunning.set(false)
            cameraPoweredOn.set(true)
            latestEventType = null
            latestEventTime = 0L
            latestEventLabel = ""
            latestDetectionMs = 0L
            recordingEnabled = true
            synchronized(eventHistory) {
                eventHistory.clear()
            }
        }
    }

    data class EventRecord(
        val type: String,
        val time: Long,
        val label: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cameraExecutor: ExecutorService
    private var detectExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val streamer = MjpegStreamer()
    var h264Encoder: H264Encoder? = null
    var rtspServer: RtspServer? = null
    lateinit var frameBuffer: FrameBuffer
    var videoRecorder: VideoRecorder? = null
    lateinit var eventDetector: EventDetector
    private var webServer: CamWebServer? = null

    // Camera2 fields (used when camera is not in CameraX)
    private var camera2Device: CameraDevice? = null
    private var camera2Session: CameraCaptureSession? = null
    private var camera2Reader: ImageReader? = null
    private var camera2LogicalId: String? = null
    private val camera2PendingFrames = java.util.concurrent.atomic.AtomicInteger(0)

    // Camera characteristics cache (queried once, values never change during service lifetime)
    private var cachedCameraChecked = false
    private var cachedFacing = CameraCharacteristics.LENS_FACING_BACK
    private var cachedRotation = 0

    @Volatile
    private var isRecordingEvent = false
    private val postEventFrames = mutableListOf<Pair<Long, ByteArray>>()
    @Volatile
    private var currentEventType: String? = null
    @Volatile
    private var currentEventLabel: String = ""
    private var postEventEndTime = 0L

    @Volatile
    var currentJpegQuality = 75
        private set
    private var lastFrameTimeMs = 0L
    private var frameQualityCounter = 0

    private val batteryHandler = Handler(Looper.getMainLooper())
    private val batteryUpdateRunnable = object : Runnable {
        override fun run() {
            batteryLevel = getBatteryLevel()
            batteryHandler.postDelayed(this, 60000L)
        }
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate() start")
        super.onCreate()
        Log.d(TAG, "onCreate() super done")

        cameraExecutor = Executors.newSingleThreadExecutor()
        detectExecutor = Executors.newSingleThreadExecutor()
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
            ACTION_CAMERA_POWER -> {
                val powerOn = intent.getBooleanExtra("power_on", true)
                Log.d(TAG, "ACTION_CAMERA_POWER received: powerOn=$powerOn")
                try {
                    if (powerOn) {
                        cameraPoweredOn.set(true)
                        initCamera()
                    } else {
                        cameraPoweredOn.set(false)
                        closeCamera2()
                        cameraProvider?.unbindAll()
                        streamer.clear()
                        h264Encoder?.stop()
                        h264Encoder = null
                    }
                    Log.d(TAG, "Camera power ${if (powerOn) "on" else "off"} done")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera power switch FAILED", e)
                }
                sendBroadcast(Intent(ACTION_STATE_CHANGED))
                return START_STICKY
            }
            ACTION_SWITCH_CAMERA -> {
                Log.d(TAG, "ACTION_SWITCH_CAMERA received")
                try {
                    closeCamera2()
                    cameraProvider?.unbindAll()
                    initCamera()
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

        try { if (cameraPoweredOn.get()) { initCamera(); Log.d(TAG, "initCamera() called") } else { Log.d(TAG, "cameraPoweredOn=false, skipping initCamera") } }
        catch (e: Exception) { Log.e(TAG, "initCamera FAILED", e) }

        // 在后台线程初始化 AI 检测器（MediaPipe 模型加载较慢，避免阻塞主线程导致 ANR）
        detectExecutor?.submit {
            try { initDetectors(); Log.d(TAG, "initDetectors() done") }
            catch (e: Exception) { Log.e(TAG, "initDetectors FAILED", e) }
        }

        try { startWebServer(); Log.d(TAG, "startWebServer() done") }
        catch (e: Exception) { Log.e(TAG, "startWebServer FAILED", e) }

        try { startRtspServer(); Log.d(TAG, "startRtspServer() done") }
        catch (e: Exception) { Log.e(TAG, "startRtspServer FAILED", e) }

        // 延迟10秒后开始定期更新电量（每60秒）
        batteryHandler.postDelayed(batteryUpdateRunnable, 10000L)

        return START_STICKY
    }

    private fun startForeground() {
        createNotificationChannel()

        val notifyIntent = Intent(this, com.homecam.app.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
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

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun initCamera() {
        val targetCameraId = AppSettings.getCameraId(this)
        val logicalCameraId = AppSettings.getLogicalCameraId(this)

        // Always use Camera2 for full resolution control
        Handler(Looper.getMainLooper()).postDelayed({
            closeCamera2()
            initCamera2(logicalCameraId, targetCameraId)
        }, 200)
    }


    // region Camera2 implementation (for hidden physical cameras)

    private fun getCamera2OutputSize(manager: CameraManager, cameraId: String): android.util.Size {
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val config = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return android.util.Size(960, 720)
            val sizes = config.getOutputSizes(ImageFormat.YUV_420_888) ?: return android.util.Size(960, 720)
            // Pick the largest size <= 960x720
            var best = android.util.Size(640, 480)
            for (s in sizes) {
                if (s.width <= 960 && s.height <= 720 && s.width.toLong() * s.height > best.width.toLong() * best.height) {
                    best = s
                }
            }
            best
        } catch (e: Exception) {
            Log.e(TAG, "getCamera2OutputSize failed", e)
            android.util.Size(960, 720)
        }
    }

    private fun initCamera2(logicalId: String, physicalId: String) {
        Log.d(TAG, "initCamera2: logical=$logicalId, physical=$physicalId")
        val manager = getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager

        // Log physical camera IDs for the logical camera (diagnostic)
        try {
            val logicalChars = manager.getCameraCharacteristics(logicalId)
            val physIds = logicalChars.physicalCameraIds
            Log.d(TAG, "Physical camera IDs for logical $logicalId: $physIds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get physical camera IDs", e)
        }

        val cameraToOpen = if (physicalId != logicalId) physicalId else logicalId

        // Get output size on background thread to avoid main thread block
        val mainHandler = Handler(Looper.getMainLooper())
        cameraExecutor.execute {
            try {
                val outputSize = getCamera2OutputSize(manager, cameraToOpen)
                Log.d(TAG, "Camera2 output size: ${outputSize.width}x${outputSize.height} for camera $cameraToOpen")

                // Create ImageReader (must be on main thread for surface lifecycle)
                mainHandler.post {
                    val reader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 2)
                    reader.setOnImageAvailableListener({ r ->
                        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) {
                                image.close()
                                return@setOnImageAvailableListener
                            }
                            // Drop frame if executor is backed up (prevents latency buildup and OOM)
                            if (camera2PendingFrames.get() >= 2) {
                                image.close()
                                return@setOnImageAvailableListener
                            }
                            // Only copy raw bytes on main thread (fast), convert on executor
                            val yArr = ByteArray(image.planes[0].buffer.remaining())
                            image.planes[0].buffer.get(yArr)
                            val uArr = ByteArray(image.planes[1].buffer.remaining())
                            image.planes[1].buffer.get(uArr)
                            val vArr = ByteArray(image.planes[2].buffer.remaining())
                            image.planes[2].buffer.get(vArr)
                            val iw = image.width
                            val ih = image.height
                            val ySr = image.planes[0].rowStride
                            val uSr = image.planes[1].rowStride
                            val yPs = image.planes[0].pixelStride
                            val uPs = image.planes[1].pixelStride
                            image.close()
                            camera2PendingFrames.incrementAndGet()
                            cameraExecutor.execute {
                                try {
                                    val px = yuv420ToArgbBytes(yArr, uArr, vArr, iw, ih, ySr, uSr, yPs, uPs)
                                    processCamera2Pixels(px, iw, ih)
                                } finally {
                                    camera2PendingFrames.decrementAndGet()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in image listener", e)
                            image.close()
                        }
                    }, mainHandler)
                    camera2Reader = reader
                    camera2LogicalId = cameraToOpen

                    try {
                        manager.openCamera(cameraToOpen, object : CameraDevice.StateCallback() {
                            override fun onOpened(device: CameraDevice) {
                                Log.d(TAG, "Camera2 device opened: $cameraToOpen")
                                camera2Device = device
                                createCamera2Session(device, reader, cameraToOpen)
                            }

                            override fun onDisconnected(device: CameraDevice) {
                                Log.w(TAG, "Camera2 device disconnected: $cameraToOpen")
                                device.close()
                                camera2Device = null
                            }

                            override fun onError(device: CameraDevice, error: Int) {
                                Log.e(TAG, "Camera2 device error: $cameraToOpen, error=$error")
                                device.close()
                                camera2Device = null
                            }
                        }, mainHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera2 openCamera failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getCamera2OutputSize failed on bg thread", e)
            }
        }
    }

    private fun createCamera2Session(device: CameraDevice, reader: ImageReader, physicalId: String) {
        try {
            val outputConfig = OutputConfiguration(reader.surface)

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Camera2 session configured for camera=$physicalId")
                        camera2Session = session
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        request.addTarget(reader.surface)
                        session.setRepeatingRequest(request.build(), null, null)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera2 session configure failed for camera=$physicalId")
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "createCamera2Session failed", e)
        }
    }

    private fun closeCamera2() {
        try { camera2Session?.close() } catch (_: Exception) {}
        camera2Session = null
        try { camera2Device?.close() } catch (_: Exception) {}
        camera2Device = null
        try { camera2Reader?.close() } catch (_: Exception) {}
        camera2Reader = null
        camera2LogicalId = null
    }

    private fun getSensorOrientation(manager: CameraManager, cameraId: String, facing: Int): Int {
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val degrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            degrees
        } catch (e: Exception) {
            0
        }
    }

    private fun yuv420ToArgbBytes(
        yBuf: ByteArray, uBuf: ByteArray, vBuf: ByteArray,
        width: Int, height: Int,
        yRowStride: Int, uRowStride: Int,
        yPixelStride: Int, uPixelStride: Int
    ): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val yRowOffset = y * yRowStride
            val uvRowOffset = (y / 2) * uRowStride
            for (x in 0 until width) {
                val yVal = yBuf[yRowOffset + x * yPixelStride].toInt() and 0xFF
                val uVal = uBuf[uvRowOffset + (x / 2) * uPixelStride].toInt() and 0xFF
                val vVal = vBuf[uvRowOffset + (x / 2) * uPixelStride].toInt() and 0xFF
                val r = (yVal + 1.402f * (vVal - 128)).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344f * (uVal - 128) - 0.714f * (vVal - 128)).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * (uVal - 128)).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun processCamera2Pixels(pixels: IntArray, width: Int, height: Int) {
        try {
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val croppedBitmap = bitmap

            // Apply sensor rotation (cache camera characteristics — they never change during service lifetime)
            if (!cachedCameraChecked) {
                cachedCameraChecked = true
                try {
                    val mgr = getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
                    val chars = mgr.getCameraCharacteristics(camera2LogicalId ?: "0")
                    cachedFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    cachedRotation = getSensorOrientation(mgr, camera2LogicalId ?: "0", cachedFacing)
                } catch (_: Exception) { }
            }
            val facing = cachedFacing
            val rotation = cachedRotation
            val rotatedBitmap = if (rotation != 0 || facing == CameraCharacteristics.LENS_FACING_FRONT) {
                val matrix = Matrix().apply {
                    postRotate(rotation.toFloat())
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        postScale(-1f, 1f)
                    }
                }
                Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true).also {
                    croppedBitmap.recycle()
                }
            } else croppedBitmap

            // Same processing pipeline as CameraX path
            val scale = AppSettings.getScaleFactor(this)
            val newW = (rotatedBitmap.width * scale).toInt()
            val newH = (rotatedBitmap.height * scale).toInt()
            val scaledBitmap = if (rotatedBitmap.width != newW || rotatedBitmap.height != newH) {
                Bitmap.createScaledBitmap(rotatedBitmap, newW, newH, true).also {
                    rotatedBitmap.recycle()
                }
            } else rotatedBitmap

            val timestamp = System.currentTimeMillis()

            if (AppSettings.isOverlayEnabled(this)) {
                // Overlay ON → detection on main thread, draw on bitmap
                if (AppSettings.isMotionDetectionEnabled(this)) {
                    eventDetector.analyzeFrame(scaledBitmap, timestamp)
                }
                if (AppSettings.isSleepDetectionEnabled(this)) {
                    eventDetector.analyzeSleep(scaledBitmap)
                }
                eventDetector.applyDetectionOverlay(scaledBitmap)
                latestDetectionMs = eventDetector.lastDetectionMs
            } else {
                // Overlay OFF → detection in background thread, no blocking
                val isMotion = AppSettings.isMotionDetectionEnabled(this)
                val isSleep = AppSettings.isSleepDetectionEnabled(this)
                if (isMotion || isSleep) {
                    val detectCopy = scaledBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    detectExecutor?.execute {
                        if (isMotion) eventDetector.analyzeFrame(detectCopy, System.currentTimeMillis())
                        if (isSleep) eventDetector.analyzeSleep(detectCopy)
                        latestDetectionMs = eventDetector.lastDetectionMs
                        detectCopy.recycle()
                    }
                }
            }

            // Feed to H.264 encoder for RTSP streaming (only when RTSP is enabled)
            if (AppSettings.isRtspEnabled(this)) {
                ensureEncoder(scaledBitmap.width, scaledBitmap.height)
                h264Encoder?.feedFrame(scaledBitmap, timestamp)
            }

            val jpegData = compressToJpeg(scaledBitmap, currentJpegQuality)
            if (AppSettings.isMjpgEnabled(this)) streamer.pushFrame(jpegData)
            frameBuffer.addFrame(timestamp, jpegData)

            adjustJpegQuality()

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
            Log.e(TAG, "Camera2 frame processing error", e)
        }
    }

    // endregion

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

            val timestamp = System.currentTimeMillis()

            if (AppSettings.isMotionDetectionEnabled(this)) {
                eventDetector.analyzeFrame(scaledBitmap, timestamp)
            }
            if (AppSettings.isSleepDetectionEnabled(this)) {
                eventDetector.analyzeSleep(scaledBitmap)
            }

            // Persist detection overlay (bounding box) on every frame
            eventDetector.applyDetectionOverlay(scaledBitmap)
            latestDetectionMs = eventDetector.lastDetectionMs

            // Feed to H.264 encoder for RTSP streaming (only when RTSP is enabled)
            if (AppSettings.isRtspEnabled(this)) {
                ensureEncoder(scaledBitmap.width, scaledBitmap.height)
                h264Encoder?.feedFrame(scaledBitmap, timestamp)
            }

            val jpegData = compressToJpeg(scaledBitmap, currentJpegQuality)

            if (AppSettings.isMjpgEnabled(this)) streamer.pushFrame(jpegData)
            frameBuffer.addFrame(timestamp, jpegData)

            adjustJpegQuality()

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

    private fun adjustJpegQuality() {
        val now = System.currentTimeMillis()
        if (lastFrameTimeMs == 0L) {
            lastFrameTimeMs = now
            return
        }
        frameQualityCounter++
        if (frameQualityCounter < 30) return
        frameQualityCounter = 0

        val frameInterval = 1000L / AppSettings.getFps(this@CameraService)
        val actualInterval = now - lastFrameTimeMs
        lastFrameTimeMs = now

        when {
            actualInterval > frameInterval * 3 && currentJpegQuality > 45 -> {
                currentJpegQuality = (currentJpegQuality - 5).coerceAtLeast(45)
                Log.d(TAG, "JPEG quality reduced to $currentJpegQuality (interval=${actualInterval}ms)")
            }
            actualInterval < frameInterval && currentJpegQuality < 75 -> {
                currentJpegQuality = (currentJpegQuality + 2).coerceAtMost(75)
                Log.d(TAG, "JPEG quality increased to $currentJpegQuality")
            }
        }
    }

    private fun onEventDetected(eventType: String) {
        Log.d(TAG, "onEventDetected: $eventType")
        val now = System.currentTimeMillis()

        // Parse compound event types (e.g. "phone:70" -> type="phone", label="70%")
        val (cleanType, extraLabel) = if (eventType.startsWith("phone:")) {
            "phone" to eventType.substringAfter("phone:") + "%"
        } else {
            eventType to ""
        }

        // "motion" events are for video recording only — not added to event log
        if (cleanType != "motion") {
            val label = when (cleanType) {
                "enter", "leave" -> eventDetector.currentOccupantLabel
                else -> extraLabel
            }
            latestEventType = cleanType
            latestEventTime = now
            latestEventLabel = label
            synchronized(eventHistory) {
                eventHistory.add(EventRecord(cleanType, now, label))
                if (eventHistory.size > 1000) eventHistory.removeAt(0)
            }
            // Embed event in RTSP H.264 stream via SEI NAL unit
            rtspServer?.setPendingEvent(cleanType, now, label)
            sendBroadcast(Intent(ACTION_STATE_CHANGED))
        }

        if (!recordingEnabled) {
            Log.d(TAG, "Recording disabled, skipping video")
            return
        }

        // "基于事件" strategy: skip motion-triggered recording entirely
        if (AppSettings.getRecordingStrategy(this) == "event" && cleanType == "motion") {
            return
        }

        val saveDuration = AppSettings.getSaveDurationSec(this)

        synchronized(postEventFrames) {
            if (isRecordingEvent) {
                // Already recording: override event type if the new event is more specific
                if (cleanType != "motion") {
                    currentEventType = cleanType
                    currentEventLabel = if (cleanType == "phone") extraLabel else ""
                    postEventEndTime = now + saveDuration * 1000L
                    android.util.Log.d(TAG, "onEventDetected: overrode recording type to $cleanType")
                }
                return
            }
            isRecordingEvent = true
            currentEventType = cleanType
            currentEventLabel = if (cleanType == "phone") extraLabel else ""
            postEventEndTime = now + saveDuration * 1000L
            postEventFrames.clear()
        }

        updateNotification(cleanType)
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
        val eventLabel = currentEventLabel
        currentEventLabel = ""
        videoRecorder?.saveEventVideo(eventType, preFrames, framesToSave, eventLabel)
    }

    private fun updateNotification(eventType: String) {
        val label = when (eventType) {
            "motion" -> getString(R.string.event_motion)
            "cry" -> getString(R.string.event_cry)
            "sleep" -> getString(R.string.event_sleep)
            "wake_up" -> getString(R.string.event_wake_up)
            "enter" -> getString(R.string.event_enter)
            "leave" -> getString(R.string.event_leave)
            "fall" -> getString(R.string.event_fall)
            "get_up" -> getString(R.string.event_get_up)
            "phone" -> getString(R.string.event_phone, latestEventLabel.ifEmpty { "50%" })
            else -> getString(R.string.event_unknown)
        }

        val notifyIntent = Intent(this, com.homecam.app.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
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
        if (AppSettings.isMotionDetectionEnabled(this)) {
            Log.d(TAG, "Initializing visual detector...")
            eventDetector.initVisualDetector()
            Log.d(TAG, "Visual detector initialized")
        }
        if (AppSettings.isSleepDetectionEnabled(this)) {
            Log.d(TAG, "Initializing sleep detector...")
            eventDetector.initSleepDetector()
            Log.d(TAG, "Sleep detector initialized")
        }
        if (AppSettings.isCryDetectionEnabled(this)) {
            Log.d(TAG, "Initializing audio detector...")
            eventDetector.initAudioDetector()
            eventDetector.startAudioDetection()
            Log.d(TAG, "Audio detector initialized and started")
        }
        if (AppSettings.isFallDetectionEnabled(this) || AppSettings.isSleepDetectionEnabled(this)) {
            Log.d(TAG, "Initializing pose detector...")
            eventDetector.initPoseDetector()
            Log.d(TAG, "Pose detector initialized")
        }
        if (AppSettings.isPhoneDetectionEnabled(this)) {
            Log.d(TAG, "Initializing hand detector...")
            eventDetector.initHandDetector()
            Log.d(TAG, "Hand detector initialized")
        }
    }

    private fun startWebServer() {
        val port = AppSettings.getWebPort(this)
        webServer = CamWebServer(this, port).also {
            it.start()
            it.startUdpDiscovery()
        }
        Log.d(TAG, "Web server started on port $port")
    }

    private fun startRtspServer() {
        if (!AppSettings.isRtspEnabled(this)) {
            Log.d(TAG, "RTSP server disabled in settings")
            return
        }
        val port = AppSettings.getRtspPort(this)
        rtspServer = RtspServer().also {
            it.setEncoder(h264Encoder)
            it.start(port)
        }
        Log.d(TAG, "RTSP server started on port $port")
    }

    private fun ensureEncoder(width: Int, height: Int) {
        if (h264Encoder?.isRunning() == true) return
        // Stop existing encoder if dimensions changed
        if (h264Encoder != null) {
            h264Encoder?.stop()
            h264Encoder = null
        }
        val fps = AppSettings.getFps(this)
        val encoder = H264Encoder(width, height, fps) { nalUnits, timestampUs ->
            rtspServer?.feedH264Nalu(nalUnits, timestampUs)
        }
        encoder.start()
        h264Encoder = encoder
        rtspServer?.let { server ->
            server.setEncoder(encoder)
            server.setVideoParams(width, height, fps)
        }
        // Warm up to produce CSD (SPS/PPS) immediately for SDP generation
        encoder.warmUp()
        Log.d(TAG, "H264Encoder initialized: ${width}x${height} @ ${fps}fps")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
        isRunning.set(false)
        sendBroadcast(Intent(ACTION_STATE_CHANGED))
        ServiceManager.instance = null

        closeCamera2()
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        eventDetector.release()
        webServer?.stopUdpDiscovery()
        streamer.clear()
        webServer?.stop()
        h264Encoder?.stop()
        rtspServer?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        batteryHandler.removeCallbacks(batteryUpdateRunnable)
        detectExecutor?.shutdownNow()
        cameraExecutor.shutdownNow()

        Log.d(TAG, "onDestroy() complete")
    }
}
