package com.homecam.app.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.homecam.app.data.VideoDao
import com.homecam.app.data.VideoRecord
import com.homecam.app.service.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoRecorder(
    private val context: Context,
    private val videoDao: VideoDao,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VideoRecorder"
    }

    private val settings = AppSettings
    private val outputDir = File(
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: context.filesDir, "HomeCam"
    )

    init {
        val created = outputDir.mkdirs()
        android.util.Log.d(TAG, "init: outputDir=$outputDir, mkdirs=$created, exists=${outputDir.exists()}")
    }

    fun saveEventVideo(
        triggerType: String,
        preFrames: List<Pair<Long, ByteArray>>,
        postFrames: List<Pair<Long, ByteArray>>
    ) {
        android.util.Log.d(TAG, "saveEventVideo: type=$triggerType, pre=${preFrames.size}, post=${postFrames.size}")
        if (preFrames.isEmpty() && postFrames.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            try {
                val allFrames = (preFrames + postFrames).sortedBy { it.first }
                if (allFrames.isEmpty()) return@launch

                val fileName = generateFileName(triggerType)
                val outputFile = File(outputDir, fileName)

                val success = encodeFramesToMp4(allFrames, outputFile)
                if (success) {
                    val durationSec = calculateDuration(allFrames)
                    val record = VideoRecord(
                        fileName = fileName,
                        timestamp = allFrames.first().first,
                        eventType = triggerType,
                        durationSec = durationSec,
                        fileSize = outputFile.length()
                    )
                    videoDao.insert(record)
                    cleanupOldVideos()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun encodeFramesToMp4(frames: List<Pair<Long, ByteArray>>, outputFile: File): Boolean {
        if (frames.isEmpty()) return false

        val firstJpeg = frames.first().second
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(firstJpeg, 0, firstJpeg.size)
            ?: return false
        val width = bitmap.width
        val height = bitmap.height

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var frameIndex = 0
            val timeoutUs = 10_000L

            while (frameIndex < frames.size) {
                val inputBufferIndex = encoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: continue
                    val (timestamp, jpeg) = frames[frameIndex]
                    val frameBitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        ?: continue

                    val yuv = bitmapToYuv(frameBitmap)
                    inputBuffer.clear()
                    inputBuffer.put(yuv)

                    val presentationTimeUs = timestamp * 1000
                    encoder.queueInputBuffer(
                        inputBufferIndex, 0, yuv.size,
                        presentationTimeUs, 0
                    )
                    frameIndex++
                }

                drainEncoder(encoder, bufferInfo, muxer, { idx ->
                    trackIndex = idx; muxerStarted = true
                }, trackIndex, muxerStarted)
            }

            encoder.signalEndOfInputStream()
            drainEncoder(encoder, bufferInfo, muxer, { idx ->
                trackIndex = idx; muxerStarted = true
            }, trackIndex, muxerStarted, endOfStream = true)

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        onTrackReady: (Int) -> Unit,
        trackIndex: Int,
        muxerStarted: Boolean,
        endOfStream: Boolean = false
    ) {
        val timeoutUs = if (endOfStream) 10_000L else 0L
        var currentTrack = trackIndex
        var started = muxerStarted

        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        currentTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                        onTrackReady(currentTrack)
                    }
                }
                outputBufferIndex >= 0 -> {
                    if (!started) {
                        currentTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                        onTrackReady(currentTrack)
                    }
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        muxer.writeSampleData(currentTrack, outputBuffer, info)
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun bitmapToYuv(bitmap: android.graphics.Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    private fun calculateDuration(frames: List<Pair<Long, ByteArray>>): Int {
        if (frames.size < 2) return 1
        val first = frames.first().first
        val last = frames.last().first
        return ((last - first) / 1000).toInt().coerceAtLeast(1)
    }

    private fun generateFileName(triggerType: String): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val typeShort = when (triggerType) {
            "motion" -> "MOT"
            "cry" -> "CRY"
            "danger" -> "DNG"
            else -> "EVT"
        }
        return "HomeCam_${typeShort}_${sdf.format(Date())}.mp4"
    }

    private suspend fun cleanupOldVideos() {
        val maxCount = settings.getMaxVideoCount(context)
        val maxSize = settings.getMaxStorageMb(context) * 1024L * 1024L

        var totalSize = videoDao.getTotalSize() ?: 0L
        var count = videoDao.getCount()

        while (count > maxCount || totalSize > maxSize) {
            val oldest = videoDao.getOldest() ?: break
            val file = File(outputDir, oldest.fileName)
            if (file.exists()) file.delete()
            totalSize -= oldest.fileSize
            count--
            videoDao.delete(oldest)
        }
    }

    fun getVideoFile(fileName: String): File? {
        val file = File(outputDir, fileName)
        return if (file.exists()) file else null
    }

    fun getOutputDir(): File = outputDir
}
