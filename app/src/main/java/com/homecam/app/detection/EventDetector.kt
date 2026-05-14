package com.homecam.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.homecam.app.service.AppSettings
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.support.label.Category

import android.util.Log

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

    private var frameCounter = 0
    @Volatile
    private var isProcessing = false

    private val cryLabels = setOf("Crying", "Crying, sobbing", "Baby cry, infant cry", "Sobbing")

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

        isProcessing = true
        try {
            val detector = objectDetector ?: run { isProcessing = false; return }
            val mpImage = BitmapImageBuilder(bitmap).build()
            val results = detector.detect(mpImage)

            for (detection in results.detections()) {
                for (category in detection.categories()) {
                    val categoryName = category.categoryName()
                    val score = category.score()

                    if (categoryName.equals("person", ignoreCase = true) && score > 0.5f) {
                        if (System.currentTimeMillis() - lastMotionTriggerTime > cooldownMs) {
                            lastMotionTriggerTime = System.currentTimeMillis()

                            if (AppSettings.isMotionDetectionEnabled(context)) {
                                onEventDetected("motion")
                            }
                            if (AppSettings.isDangerDetectionEnabled(context)) {
                                onEventDetected("danger")
                            }
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isProcessing = false
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

            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) return

            val recordingBufferSize = bufferSize * 2
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, recordingBufferSize
            )
            audioRecord?.startRecording()
            isAudioRunning = true

            audioThread = Thread {
                val audioBuffer = ShortArray(bufferSize)

                while (isAudioRunning) {
                    val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (read <= 0) continue

                    val shortArray = audioBuffer.copyOf(read)
                    try {
                        tensorAudio.load(shortArray)
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
                        e.printStackTrace()
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
