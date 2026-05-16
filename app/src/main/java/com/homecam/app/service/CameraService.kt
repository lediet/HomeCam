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
        const val ACTION_CAMERA_POWER = "com.homecam.app.CAMERA_POWER"
        const val ACTION_STATE_CHANGED = "com.homecam.app.STATE_CHANGED"

        val isRunning = AtomicBoolean(false)
        val cameraPoweredOn = AtomicBoolean(true)
        @Volatile
        var latestEventType: String? = null
        @Volatile
        var latestEventTime: Long = 0L
        @Volatile
        var latestDetectionMs: Long = 0L
        @Volatile
        var recordingEnabled: Boolean = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val streamer = MjpegStreamer()
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

    @Volatile
    private var isRecordingEvent = false
    private val postEventFrames = mutableListOf<Pair<Long, ByteArray>>()
    @Volatile
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

        try { initDetectors(); Log.d(TAG, "initDetectors() done") }
        catch (e: Exception) { Log.e(TAG, "initDetectors FAILED", e) }

        try { startWebServer(); Log.d(TAG, "startWebServer() done") }
        catch (e: Exception) { Log.e(TAG, "startWebServer FAILED", e) }

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

    private fun initCamera() {
        val targetCameraId = AppSettings.getCameraId(this)
        val logicalCameraId = AppSettings.getLogicalCameraId(this)

        // If target is NOT in CameraX (e.g. hidden physical camera on MIUI), use Camera2 directly
        if (targetCameraId != logicalCameraId) {
            // Unbind CameraX first to avoid resource conflict
            cameraProvider?.unbindAll()
            Handler(Looper.getMainLooper()).postDelayed({
                closeCamera2()
                initCamera2(logicalCameraId, targetCameraId)
            }, 500)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "ProcessCameraProvider obtained")

                // Double-check: if target camera is not in CameraX, fall back to Camera2
                val infos = cameraProvider?.availableCameraInfos
                val inCameraX = infos?.any { info ->
                    try { Camera2CameraInfo.from(info).cameraId == targetCameraId } catch (e: Exception) { false }
                } == true

                if (inCameraX) {
                    bindCameraUseCases()
                } else {
                    Log.w(TAG, "Camera $targetCameraId not in CameraX, switching to Camera2")
                    // Unbind CameraX first to avoid conflict
                    cameraProvider?.unbindAll()
                    Handler(Looper.getMainLooper()).postDelayed({
                        closeCamera2()
                        initCamera2(logicalCameraId, targetCameraId)
                    }, 500)
                }
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
            val targetCameraId = AppSettings.getCameraId(this)
            val logicalCameraId = AppSettings.getLogicalCameraId(this)
            val infos = provider.availableCameraInfos

            // Diagnostic: log all CameraX camera IDs
            val allIds = infos.mapNotNull { info ->
                try { Camera2CameraInfo.from(info).cameraId } catch (e: Exception) { null }
            }
            Log.d(TAG, "CameraX availableCameraInfos: $allIds (count=${infos.size})")

            val cameraSelector = try {
                // Try 1: match physical camera ID directly
                val physicalMatch = infos.filter { info ->
                    Camera2CameraInfo.from(info).cameraId == targetCameraId
                }
                if (physicalMatch.isNotEmpty()) {
                    CameraSelector.Builder()
                        .addCameraFilter { listOfNotNull(physicalMatch.firstOrNull()) }
                        .build()
                } else if (targetCameraId != logicalCameraId) {
                    // Try 2: physical camera not found, fall back to logical camera
                    val logicalMatch = infos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == logicalCameraId
                    }
                    if (logicalMatch.isNotEmpty()) {
                        Log.w(TAG, "Physical camera $targetCameraId not in CameraX, using logical $logicalCameraId")
                        CameraSelector.Builder()
                            .addCameraFilter { listOfNotNull(logicalMatch.firstOrNull()) }
                            .build()
                    } else {
                        val idx = AppSettings.getCameraIndex(this).coerceIn(0, infos.size - 1)
                        if (infos.isNotEmpty() && idx in infos.indices) {
                            CameraSelector.Builder()
                                .addCameraFilter { listOfNotNull(infos.getOrNull(idx)) }
                                .build()
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                    }
                } else {
                    val idx = AppSettings.getCameraIndex(this).coerceIn(0, infos.size - 1)
                    if (infos.isNotEmpty() && idx in infos.indices) {
                        CameraSelector.Builder()
                            .addCameraFilter { listOfNotNull(infos.getOrNull(idx)) }
                            .build()
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera selector failed, using default", e)
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            provider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            Log.d(TAG, "Camera bound: id=$targetCameraId, logical=$logicalCameraId, total=${infos.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Bind camera failed", e)
        }
    }

    // region Camera2 implementation (for hidden physical cameras)

    private fun getCamera2OutputSize(manager: CameraManager, cameraId: String): android.util.Size {
        return try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val config = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return android.util.Size(1280, 720)
            val sizes = config.getOutputSizes(ImageFormat.YUV_420_888) ?: return android.util.Size(1280, 720)
            // Pick the largest size <= 1280x720 that preserves 4:3 aspect ratio
            var best = android.util.Size(640, 480)
            for (s in sizes) {
                if (s.width <= 1280 && s.height <= 720 && s.width.toLong() * s.height > best.width.toLong() * best.height) {
                    best = s
                }
            }
            best
        } catch (e: Exception) {
            Log.e(TAG, "getCamera2OutputSize failed", e)
            android.util.Size(1280, 720)
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
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) (360 - degrees) % 360 else degrees
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

            // Apply sensor rotation
            val manager = getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
            val facing = try {
                manager.getCameraCharacteristics(camera2LogicalId ?: "0")
                    .get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            } catch (e: Exception) { CameraCharacteristics.LENS_FACING_BACK }
            val rotation = getSensorOrientation(manager, camera2LogicalId ?: "0", facing)
            val rotatedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
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

            if (AppSettings.isMotionDetectionEnabled(this) ||
                AppSettings.isDangerDetectionEnabled(this)) {
                eventDetector.analyzeFrame(scaledBitmap, timestamp)
            }

            eventDetector.applyDetectionOverlay(scaledBitmap)
            latestDetectionMs = eventDetector.lastDetectionMs

            val jpegData = compressToJpeg(scaledBitmap, 75)
            streamer.pushFrame(jpegData)
            frameBuffer.addFrame(timestamp, jpegData)

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

            if (AppSettings.isMotionDetectionEnabled(this) ||
                AppSettings.isDangerDetectionEnabled(this)) {
                eventDetector.analyzeFrame(scaledBitmap, timestamp)
            }

            // Persist detection overlay (bounding box) on every frame
            eventDetector.applyDetectionOverlay(scaledBitmap)
            latestDetectionMs = eventDetector.lastDetectionMs

            val jpegData = compressToJpeg(scaledBitmap, 75)

            streamer.pushFrame(jpegData)
            frameBuffer.addFrame(timestamp, jpegData)

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
        if (!recordingEnabled) {
            Log.d(TAG, "Recording disabled, skipping")
            return
        }
        val saveDuration = AppSettings.getSaveDurationSec(this)
        val now = System.currentTimeMillis()

        synchronized(postEventFrames) {
            if (isRecordingEvent) return
            isRecordingEvent = true
            currentEventType = eventType
            postEventEndTime = now + saveDuration * 1000L
            postEventFrames.clear()
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
        videoRecorder?.saveEventVideo(eventType, preFrames, framesToSave)
    }

    private fun updateNotification(eventType: String) {
        val label = when (eventType) {
            "motion" -> getString(R.string.event_motion)
            "cry" -> getString(R.string.event_cry)
            "danger" -> getString(R.string.event_danger)
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

        closeCamera2()
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        eventDetector.release()
        streamer.clear()
        webServer?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        cameraExecutor.shutdownNow()

        Log.d(TAG, "onDestroy() complete")
    }
}
