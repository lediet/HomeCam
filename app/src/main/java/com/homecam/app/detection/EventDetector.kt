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
        if (!AppSettings.isMotionDetectionEnabled(context) &&
            !AppSettings.isDangerDetectionEnabled(context)) return

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
                            if (isPerson && AppSettings.isDangerDetectionEnabled(context)) {
                                onEventDetected("danger")
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
    }
}
