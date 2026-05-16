package com.homecam.app.detection

import android.content.Context
import android.graphics.*
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.homecam.app.service.AppSettings
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.support.audio.TensorAudio

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

    private val animalLabels = setOf("cat", "dog", "bird")

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

    enum class SleepState { AWAKE, SLEEPING }

    fun initVisualDetector() {
        Log.d(TAG, "initVisualDetector() start")
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite0.tflite")
                .build()

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

        // Clear stale overlay before new detection
        lastBoxRect = null

        isProcessing = true
        try {
            val detector = objectDetector ?: run { isProcessing = false; return }
            val mpImage = BitmapImageBuilder(bitmap).build()

            val detectStart = System.nanoTime()
            val results = detector.detect(mpImage)
            lastDetectionMs = (System.nanoTime() - detectStart) / 1_000_000

            if (results == null) return

            var personFound = false
            for (detection in results.detections()) {
                for (category in detection.categories()) {
                    val categoryName = category.categoryName()
                    val score = category.score()

                    if (score <= 0.5f) continue

                    val isPerson = categoryName.equals("person", ignoreCase = true)
                    val isAnimal = animalLabels.any { categoryName.equals(it, ignoreCase = true) }

                    if (isPerson || isAnimal) {
                        if (!personFound) {
                            lastBoxRect = detection.boundingBox()
                            lastBoxLabel = categoryName
                            lastBoxScore = score
                            personFound = true
                        }

                        if (System.currentTimeMillis() - lastMotionTriggerTime > cooldownMs) {
                            lastMotionTriggerTime = System.currentTimeMillis()

                            if (AppSettings.isMotionDetectionEnabled(context)) {
                                onEventDetected("motion")
                            }
    
                        }
                    }
                }
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

    fun initSleepDetector() {
        Log.d(TAG, "initSleepDetector() start")
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
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
    }
}
