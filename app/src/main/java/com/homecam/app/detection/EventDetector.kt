package com.homecam.app.detection

import android.content.Context
import android.graphics.*
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.homecam.app.service.AppSettings
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.support.audio.TensorAudio
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

class EventDetector(
    private val context: Context,
    private val onEventDetected: (eventType: String) -> Unit
) {
    companion object {
        private const val TAG = "EventDetector"
    }

    private var objectDetector: ObjectDetector? = null
    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var isAudioRunning = false

    private var lastMotionTriggerTime = 0L
    private var lastCryTriggerTime = 0L
    private val cooldownMs = 5000L

    @Volatile
    private var frameCounter = 0
    @Volatile
    private var isProcessing = false
    @Volatile
    var lastDetectionMs: Long = 0L
        private set
    @Volatile
    private var lastBoxRect: RectF? = null
    @Volatile
    private var lastBoxLabel: String? = null
    @Volatile
    private var lastBoxScore: Float = 0f

    private val cryLabels = setOf("Crying", "Crying, sobbing", "Baby cry, infant cry", "Sobbing")

    private var detectionTarget: String = AppSettings.getDetectionTarget(context)

    // Sleep detection via FaceLandmarker
    private var faceLandmarker: FaceLandmarker? = null
    private var sleepState = SleepState.AWAKE
    private var closedEyeFrameCount = 0
    private var consecutiveOpenFrames = 0
    private var lastSleepAnalyzeTime = 0L
    private val sleepAnalyzeInterval = 1000L // 1 second between detections
    private var lastSleepTriggerTime = 0L
    private var lastWakeUpTriggerTime = 0L
    private val sleepCooldownMs = 10000L

    // Eye landmarks indices (MediaPipe 478-point face mesh)
    // Right eye: [33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246]
    // Left eye: [362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398]
    // EAR landmarks: eye corners [33, 133] right, [362, 263] left
    // Upper eyelid [159, 158, 157, 173] right, [386, 387, 388, 466] left
    // Lower eyelid [145, 144, 163, 7] right, [374, 380, 381, 382] left
    // For EAR calculation we use: p1=left corner, p2=top-left, p3=top-right, p4=right corner, p5=bottom-right, p6=bottom-left
    // Right eye indices for EAR: 33(left), 159(top-left), 158(top-right), 133(right), 153(bottom-right), 145(bottom-left)
    // Left eye indices for EAR: 362(left), 386(top-left), 385(top-right), 263(right), 374(bottom-right), 380(bottom-left)
    private val RIGHT_EYE_IDX = intArrayOf(33, 159, 158, 133, 153, 145)
    private val LEFT_EYE_IDX = intArrayOf(362, 386, 385, 263, 374, 380)
    private val SLEEP_EAR_THRESHOLD = 0.22f
    private val SLEEP_CLOSED_FRAMES = 5   // N consecutive closed-eye detections (at 1/sec) -> sleep
    private val AWAKE_OPEN_FRAMES = 5     // consecutive open-eye detections (at 1/sec) while sleeping -> wake_up

    // Fall detection via PoseLandmarker
    private var poseLandmarker: PoseLandmarker? = null
    private var fallState = FallState.STANDING
    private var fallCounter = 0
    private var fallThreshold = 15
    private var lastFallTriggerTime = 0L
    private var lastGetUpTriggerTime = 0L
    private val fallCooldownMs = 10000L

    // Phone detection via HandLandmarker
    private var handLandmarker: HandLandmarker? = null
    private var lastPhoneTriggerTime = 0L
    private val phoneCooldownMs = 30000L
    private var lastPhoneLogTime = 0L
    private var lastPhoneRect: RectF? = null
    private var lastPhoneScore: Float = 0f
    private var lastPersonRect: RectF? = null

    enum class SleepState { AWAKE, SLEEPING }

    enum class OccupancyState { EMPTY, OCCUPIED }

    enum class FallState { STANDING, POSSIBLE_FALL, FALL_EVENT }

    private var occupancyState = OccupancyState.EMPTY
    private var lastOccupiedTime = 0L
    private val occupancyTimeoutMs = 120000L
    @Volatile
    var currentOccupantLabel: String = ""
        private set

    fun translateLabel(label: String): String {
        return when (label.lowercase()) {
            "person" -> "人"
            "cat" -> "猫"
            "dog" -> "狗"
            "bird" -> "鸟"
            else -> label
        }
    }

    private fun createBaseOptions(modelPath: String): BaseOptions {
        val backend = AppSettings.getInferenceBackend(context)
        val delegate = when (backend) {
            "gpu" -> Delegate.GPU
            "xnnpack" -> Delegate.CPU
            else -> Delegate.CPU
        }
        Log.d(TAG, "Inference backend: $backend -> delegate=$delegate for $modelPath")
        return BaseOptions.builder()
            .setModelAssetPath(modelPath)
            .setDelegate(delegate)
            .build()
    }

    fun initVisualDetector() {
        Log.d(TAG, "initVisualDetector() start")
        try {
            val baseOptions = createBaseOptions("efficientdet_lite0.tflite")

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(3)
                .setScoreThreshold(0.5f)
                .build()

            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "initVisualDetector() success")
        } catch (e: Exception) {
            Log.e(TAG, "initVisualDetector() FAILED - model file missing or incompatible", e)
        }
    }

    fun initAudioDetector() {
        Log.d(TAG, "initAudioDetector() start")
        try {
audioClassifier = AudioClassifier.createFromFile(context, "yamnet.tflite")
            Log.d(TAG, "initAudioDetector() success")
        } catch (e: Exception) {
            Log.e(TAG, "initAudioDetector() FAILED - model file missing or incompatible", e)
        }
    }

    fun analyzeFrame(bitmap: Bitmap, @Suppress("UNUSED_PARAMETER") timestamp: Long) {
        if (!AppSettings.isMotionDetectionEnabled(context)) return

        frameCounter++
        val interval = AppSettings.getDetectionIntervalFrames(context)
        if (frameCounter % interval != 0) return
        if (isProcessing) return

        detectionTarget = AppSettings.getDetectionTarget(context)

        // Clear stale overlay before new detection
        lastBoxRect = null
        lastPhoneRect = null
        lastPhoneScore = 0f

        isProcessing = true
        try {
            val detector = objectDetector ?: run { isProcessing = false; return }
            val mpImage = BitmapImageBuilder(bitmap).build()

            val detectStart = System.nanoTime()
            val results = detector.detect(mpImage)
            lastDetectionMs = (System.nanoTime() - detectStart) / 1_000_000

            if (results == null) return

            var personFound = false
            var detectedLabel = ""
            val now = System.currentTimeMillis()
            for (detection in results.detections()) {
                for (category in detection.categories()) {
                    val categoryName = category.categoryName()
                    val score = category.score()

                    if (score <= 0.5f) continue

                    val isTarget = categoryName.equals(detectionTarget, ignoreCase = true)
                    val isCellPhone = categoryName.equals("cell phone", ignoreCase = true)

                    if (isCellPhone) {
                        lastPhoneRect = detection.boundingBox()
                        lastPhoneScore = score
                    }

                    if (isTarget) {
                        if (!personFound) {
                            lastBoxRect = detection.boundingBox()
                            lastBoxLabel = categoryName
                            lastBoxScore = score
                            personFound = true
                            detectedLabel = categoryName
                            lastPersonRect = detection.boundingBox()
                        }

                        // Fire "motion" for video recording (with cooldown)
                        if (now - lastMotionTriggerTime > cooldownMs) {
                            lastMotionTriggerTime = now
                            onEventDetected("motion")
                        }
                    }
                }
            }

            // Occupancy state machine for enter/leave events
            if (personFound) {
                currentOccupantLabel = translateLabel(detectedLabel)
                lastOccupiedTime = now
                if (occupancyState == OccupancyState.EMPTY) {
                    // EMPTY -> OCCUPIED transition
                    occupancyState = OccupancyState.OCCUPIED
                    onEventDetected("enter")
                }
            } else {
                if (occupancyState == OccupancyState.OCCUPIED) {
                    // Check if timeout expired since last occupant seen
                    if (now - lastOccupiedTime > occupancyTimeoutMs) {
                        occupancyState = OccupancyState.EMPTY
                        onEventDetected("leave")
                    }
                }
            }

            // --- Fall detection (only when person detected) ---
            if (personFound && AppSettings.isFallDetectionEnabled(context) && detectionTarget == "person") {
                val personRect = lastPersonRect ?: lastBoxRect
                if (personRect != null) {
                    analyzeFall(bitmap, personRect)
                }
            }

            // --- Phone detection (only when person detected) ---
            if (personFound && AppSettings.isPhoneDetectionEnabled(context) && detectionTarget == "person") {
                analyzePhone(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isProcessing = false
        }
    }

    fun applyDetectionOverlay(bitmap: Bitmap) {
        val rect = lastBoxRect ?: return
        drawDetection(bitmap, rect, lastBoxLabel ?: "person", lastBoxScore)
    }

    fun scaleDetectionRects(scaleX: Float, scaleY: Float) {
        lastBoxRect?.let { r ->
            r.set(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY)
        }
        lastPersonRect?.let { r ->
            r.set(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY)
        }
        lastPhoneRect?.let { r ->
            r.set(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY)
        }
    }

    fun initSleepDetector() {
        Log.d(TAG, "initSleepDetector() start")
        try {
            val baseOptions = createBaseOptions("face_landmarker.task")
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "initSleepDetector() success")
        } catch (e: Exception) {
            Log.e(TAG, "initSleepDetector() FAILED - model file missing", e)
        }
    }

    fun initPoseDetector() {
        Log.d(TAG, "initPoseDetector() start")
        try {
            val baseOptions = createBaseOptions("pose_landmarker.task")
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            val interval = AppSettings.getDetectionIntervalFrames(context)
            val fps = AppSettings.getFps(context)
            val detectionsPerSec = fps / interval.toFloat()
            fallThreshold = (detectionsPerSec * 3).toInt().coerceIn(5, 60)
            Log.d(TAG, "initPoseDetector() success, fallThreshold=$fallThreshold")
        } catch (e: Exception) {
            Log.e(TAG, "initPoseDetector() FAILED - model file missing", e)
        }
    }

    fun initHandDetector() {
        Log.d(TAG, "initHandDetector() start")
        try {
            val baseOptions = createBaseOptions("hand_landmarker.task")
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "initHandDetector() success")
        } catch (e: Exception) {
            Log.e(TAG, "initHandDetector() FAILED - model file missing", e)
        }
    }

    private fun onFaceLandmarkResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) return

        val landmarks = result.faceLandmarks()[0]
        val rightEAR = computeEAR(landmarks, RIGHT_EYE_IDX)
        val leftEAR = computeEAR(landmarks, LEFT_EYE_IDX)

        val bothEyesClosed = (rightEAR < SLEEP_EAR_THRESHOLD) && (leftEAR < SLEEP_EAR_THRESHOLD)
        val anyEyeOpen = (rightEAR >= SLEEP_EAR_THRESHOLD) || (leftEAR >= SLEEP_EAR_THRESHOLD)

        when (sleepState) {
            SleepState.AWAKE -> {
                if (bothEyesClosed) {
                    closedEyeFrameCount++
                    if (closedEyeFrameCount >= SLEEP_CLOSED_FRAMES) {
                        sleepState = SleepState.SLEEPING
                        closedEyeFrameCount = 0
                        if (System.currentTimeMillis() - lastSleepTriggerTime > sleepCooldownMs) {
                            lastSleepTriggerTime = System.currentTimeMillis()
                            onEventDetected("sleep")
                        }
                    }
                } else {
                    closedEyeFrameCount = 0
                }
            }
            SleepState.SLEEPING -> {
                if (anyEyeOpen) {
                    consecutiveOpenFrames++
                    if (consecutiveOpenFrames >= AWAKE_OPEN_FRAMES) {
                        sleepState = SleepState.AWAKE
                        consecutiveOpenFrames = 0
                        if (System.currentTimeMillis() - lastWakeUpTriggerTime > sleepCooldownMs) {
                            lastWakeUpTriggerTime = System.currentTimeMillis()
                            onEventDetected("wake_up")
                        }
                    }
                } else {
                    consecutiveOpenFrames = 0
                }
            }
        }
    }

    /** EAR (Eye Aspect Ratio) = (|p2-p6| + |p3-p5|) / (2 * |p1-p4|) */
    private fun computeEAR(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, eyeIdx: IntArray): Float {
        val p1 = landmarks[eyeIdx[0]]
        val p2 = landmarks[eyeIdx[1]]
        val p3 = landmarks[eyeIdx[2]]
        val p4 = landmarks[eyeIdx[3]]
        val p5 = landmarks[eyeIdx[4]]
        val p6 = landmarks[eyeIdx[5]]

        val vert1 = dist(p2, p6)
        val vert2 = dist(p3, p5)
        val horiz = dist(p1, p4)

        return (vert1 + vert2) / (2.0f * horiz)
    }

    private fun dist(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                     b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Run sleep detection on the given bitmap (throttled to 1 detection/sec) */
    fun analyzeSleep(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastSleepAnalyzeTime < sleepAnalyzeInterval) return
        lastSleepAnalyzeTime = now
        val detector = faceLandmarker ?: return
        try {
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val result = detector.detect(mpImage)
            onFaceLandmarkResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "analyzeSleep error", e)
        }
    }

    /** Run fall detection: crop person ROI -> PoseLandmarker -> state machine */
    fun analyzeFall(bitmap: Bitmap, personRect: RectF) {
        val detector = poseLandmarker ?: return
        try {
            val roi = cropWithPadding(bitmap, personRect, 0.3f)
            val mpImage = BitmapImageBuilder(roi).build()
            val result = detector.detect(mpImage)
            roi.recycle()

            if (result.landmarks().isEmpty()) {
                Log.d(TAG, "analyzeFall: no pose landmarks")
                return
            }

            val landmarks = result.landmarks()[0]
            onPoseLandmarkResult(landmarks)
        } catch (e: Exception) {
            Log.e(TAG, "analyzeFall error", e)
        }
    }

    private fun cropWithPadding(bitmap: Bitmap, rect: RectF, padRatio: Float): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val padW = rect.width() * padRatio
        val padH = rect.height() * padRatio
        val left = (rect.left - padW).coerceAtLeast(0f)
        val top = (rect.top - padH).coerceAtLeast(0f)
        val right = (rect.right + padW).coerceAtMost(w)
        val bottom = (rect.bottom + padH).coerceAtMost(h)
        return Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
    }

    /** Fall state machine based on torso angle */
    private fun onPoseLandmarkResult(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>) {
        if (landmarks.size < 25) return

        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]
        val leftHip = landmarks[23]
        val rightHip = landmarks[24]

        val midShoulderX = (leftShoulder.x() + rightShoulder.x()) / 2f
        val midShoulderY = (leftShoulder.y() + rightShoulder.y()) / 2f
        val midHipX = (leftHip.x() + rightHip.x()) / 2f
        val midHipY = (leftHip.y() + rightHip.y()) / 2f

        val dx = midHipX - midShoulderX
        val dy = midHipY - midShoulderY
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) return

        val isLandscape = AppSettings.isLandscapeMode(context)
        val effectiveDy = if (isLandscape) dx else dy
        val cosAngle = kotlin.math.abs(effectiveDy) / length
        val torsoAngleDeg = Math.toDegrees(kotlin.math.acos(cosAngle.toDouble())).toFloat()
        val now = System.currentTimeMillis()

        Log.d(TAG, String.format("Fall detection: torsoAngle=%.1f, state=%s, counter=%d/%d",
            torsoAngleDeg, fallState.name, fallCounter, fallThreshold))

        val isFallen = torsoAngleDeg > 50f

        when (fallState) {
            FallState.STANDING -> {
                if (isFallen) {
                    fallCounter++
                    fallState = FallState.POSSIBLE_FALL
                }
            }
            FallState.POSSIBLE_FALL -> {
                if (isFallen) {
                    fallCounter++
                    if (fallCounter >= fallThreshold) {
                        fallState = FallState.FALL_EVENT
                        fallCounter = 0
                        if (now - lastFallTriggerTime > fallCooldownMs) {
                            lastFallTriggerTime = now
                            Log.d(TAG, ">>> FALL event triggered")
                            onEventDetected("fall")
                        }
                    }
                } else {
                    fallState = FallState.STANDING
                    fallCounter = 0
                }
            }
            FallState.FALL_EVENT -> {
                if (!isFallen) {
                    fallState = FallState.STANDING
                    fallCounter = 0
                    if (now - lastGetUpTriggerTime > fallCooldownMs) {
                        lastGetUpTriggerTime = now
                        Log.d(TAG, ">>> GET_UP event triggered")
                        onEventDetected("get_up")
                    }
                }
            }
        }
    }

    /** Run phone detection: HandLandmarker + grasping gesture check */
    fun analyzePhone(bitmap: Bitmap) {
        val phoneRect = lastPhoneRect ?: return
        val pRect = lastPersonRect ?: lastBoxRect ?: return
        val handDetector = handLandmarker ?: return
        val now = System.currentTimeMillis()
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val handResult = handDetector.detect(mpImage)
            val phoneConfidence = lastPhoneScore
            val phoneCx = phoneRect.centerX()
            val phoneCy = phoneRect.centerY()
            val phoneSize = kotlin.math.max(phoneRect.width(), phoneRect.height())
            var handProximity = 0.0f
            for (handLandmarks in handResult.landmarks()) {
                var hx = 0f; var hy = 0f; var n = 0
                for (lm in handLandmarks) {
                    hx += lm.x() * bitmap.width
                    hy += lm.y() * bitmap.height
                    n++
                }
                if (n == 0) continue
                hx /= n; hy /= n
                val dx = hx - phoneCx
                val dy = hy - phoneCy
                val dist = kotlin.math.sqrt(dx*dx + dy*dy)
                val maxDist = phoneSize * 2.5f
                if (dist < maxDist) {
                    val score = 1.0f - (dist / maxDist)
                    if (score > handProximity) handProximity = score
                }
            }
            val combinedP = 0.6f * phoneConfidence + 0.4f * handProximity
            if (now - lastPhoneLogTime > 30000L) {
                lastPhoneLogTime = now
                Log.d(TAG, String.format("Phone detection: phoneConf=%.2f, handProx=%.2f, combined=%.2f",
                    phoneConfidence, handProximity, combinedP))
            }
            if (combinedP > 0.5f && now - lastPhoneTriggerTime > phoneCooldownMs) {
                lastPhoneTriggerTime = now
                val confidencePct = ((combinedP * 130).toInt()).coerceAtMost(100)
                Log.d(TAG, ">>> PHONE event triggered, confidence=" + confidencePct + "%")
                onEventDetected("phone:" + confidencePct)
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzePhone error", e)
        }
    }
    fun startAudioDetection() {
        if (!AppSettings.isCryDetectionEnabled(context)) return
        if (isAudioRunning) return

        val classifier = audioClassifier ?: return

        try {
            val tensorAudio = classifier.createInputTensorAudio()
            val sampleRate = tensorAudio.format.sampleRate
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) return

            val modelInputSize = tensorAudio.getTensorBuffer().flatSize
            val recordingBufferSize = maxOf(minBufferSize * 2, modelInputSize)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, recordingBufferSize
            )
            audioRecord?.startRecording()
            isAudioRunning = true

            audioThread = Thread {
                val audioBuffer = ShortArray(modelInputSize)

                while (isAudioRunning) {
                    // 读取完整的模型输入长度（15600采样点 = 0.975秒）
                    var totalRead = 0
                    while (totalRead < modelInputSize) {
                        val read = audioRecord?.read(audioBuffer, totalRead, modelInputSize - totalRead) ?: -1
                        if (read <= 0) {
                            totalRead = 0
                            break
                        }
                        totalRead += read
                    }
                    if (totalRead < modelInputSize) continue

                    try {
                        tensorAudio.load(audioBuffer)
                        val results = classifier.classify(tensorAudio)

                        if (results.isNotEmpty()) {
                            for (category in results[0].categories) {
                                if (isCryLabel(category.label) && category.score > 0.3f) {
                                    if (System.currentTimeMillis() - lastCryTriggerTime > cooldownMs) {
                                        lastCryTriggerTime = System.currentTimeMillis()
                                        onEventDetected("cry")
                                    }
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio classify error", e)
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "startAudioDetection() FAILED", e)
        }
    }

    private fun drawDetection(bitmap: Bitmap, rectF: RectF, label: String, score: Float) {
        val canvas = Canvas(bitmap)
        val imageW = bitmap.width.toFloat()

        val strokeW = (imageW * 0.006f).coerceAtLeast(2f)
        val boxPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = strokeW
        }
        canvas.drawRect(rectF, boxPaint)

        val text = "$label ${(score * 100).toInt()}%"
        val fontSize = imageW * 0.055f
        val textPaint = Paint().apply {
            color = Color.GREEN
            textSize = fontSize
            isAntiAlias = true
        }
        val textWidth = textPaint.measureText(text)
        val pad = fontSize * 0.15f
        val labelH = fontSize * 1.35f

        val bgPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(rectF.left, rectF.top - labelH, rectF.left + textWidth + pad * 2f, rectF.top, bgPaint)
        canvas.drawText(text, rectF.left + pad, rectF.top - fontSize * 0.25f, textPaint)
    }

    private fun isCryLabel(label: String): Boolean {
        return cryLabels.any { label.contains(it, ignoreCase = true) }
    }

    fun stopAudioDetection() {
        isAudioRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioThread?.interrupt()
        audioThread = null
    }

    fun release() {
        stopAudioDetection()
        try { objectDetector?.close() } catch (_: Exception) {}
        objectDetector = null
        try { audioClassifier?.close() } catch (_: Exception) {}
        audioClassifier = null
        try { faceLandmarker?.close() } catch (_: Exception) {}
        faceLandmarker = null
        try { poseLandmarker?.close() } catch (_: Exception) {}
        poseLandmarker = null
        try { handLandmarker?.close() } catch (_: Exception) {}
        handLandmarker = null
    }
}
