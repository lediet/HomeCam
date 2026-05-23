package com.homecam.app.stream

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Real-time H.264 encoder using MediaCodec.
 *
 * Accepts ARGB_8888 Bitmap frames, converts to NV12, feeds to MediaCodec,
 * and delivers encoded H.264 NAL units to a callback.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onNalu: (nalUnits: List<ByteArray>, timestampUs: Long) -> Unit
) {

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10_000L

        // Diagnostic: log NAL unit type names
        private fun nalTypeName(type: Int): String = when (type) {
            1 -> "non-IDR"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            9 -> "AUD"
            24 -> "STAP-A"
            28 -> "FU-A"
            else -> "UNKNOWN($type)"
        }

        private fun calculateBitrate(width: Int, height: Int, fps: Int): Int {
            val pixels = width * height
            return when {
                pixels <= 307_200 -> 500_000     // 640x480 or less: 500 kbps
                pixels <= 921_600 -> 1_000_000   // 1280x720: 1 Mbps
                else -> 2_000_000               // 1920x1080: 2 Mbps
            }
        }
    }

    private var encoder: MediaCodec? = null
    private var isStarted = false

    // CSD (Codec Specific Data): SPS and PPS for SDP generation
    @Volatile
    var csdSps: ByteArray? = null
        private set
    @Volatile
    var csdPps: ByteArray? = null
        private set
    @Volatile
    var csdReady: Boolean = false
        private set

    // Track frame presentation timestamps
    private var startTimestampMs: Long = 0L
    private var firstRealTimestampMs: Long = 0L

    // Diagnostic: raw H.264 dump to file
    private var dumpStream: java.io.FileOutputStream? = null

    /**
     * Enable raw H.264 bitstream dump.
     * The saved .264 file can be analyzed with:
     *   ffprobe -show_frames dump.264
     *   ffplay dump.264
     */
    fun startDump(filePath: String) {
        try {
            dumpStream = java.io.FileOutputStream(filePath)
            Log.d(TAG, "H.264 dump started: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open dump file", e)
        }
    }

    fun stopDump() {
        try { dumpStream?.close() } catch (_: Exception) {}
        dumpStream = null
        Log.d(TAG, "H.264 dump stopped")
    }

    fun isDumping(): Boolean = dumpStream != null

    private fun dumpData(data: ByteArray) {
        try { dumpStream?.write(data) } catch (_: Exception) {}
    }

    fun start() {
        if (isStarted) return
        try {
            val bitrate = calculateBitrate(width, height, fps)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)  // IDR every 1 second
                // Low-latency settings
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)       // No B-frames (no frame reordering)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try { setInteger("low-latency", 1) } catch (_: Exception) {}
                }
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }

            isStarted = true
            Log.d(TAG, "H.264 encoder started: ${width}x${height} @ ${fps}fps, bitrate=${bitrate}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 encoder", e)
            isStarted = false
            encoder = null
        }
    }

    /**
     * Feed a Bitmap frame to the encoder.
     * Must be called from a single thread (the camera processing thread).
     */
    fun feedFrame(bitmap: Bitmap, realTimestampMs: Long) {
        if (!isStarted) return
        val enc = encoder ?: return

        try {
            // Record start timestamp on first frame
            if (firstRealTimestampMs == 0L) {
                firstRealTimestampMs = realTimestampMs
                startTimestampMs = System.nanoTime() / 1000  // us
            }

            // Convert Bitmap to NV12 byte array
            val nv12 = bitmapToNv12(bitmap)

            val inputBufferIndex = enc.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex < 0) {
                // Encoder is backed up, drop this frame
                return
            }

            val inputBuffer: ByteBuffer = enc.getInputBuffer(inputBufferIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(nv12)

            // Presentation timestamp in microseconds, relative to first frame
            val ptsUs = (realTimestampMs - firstRealTimestampMs) * 1000

            enc.queueInputBuffer(inputBufferIndex, 0, nv12.size, ptsUs, 0)

            // Drain all available output buffers
            drainEncoder(enc)
            // Retry drain with short delay to reduce encoding latency
            // Without this, encoded output may only appear on the NEXT feedFrame call (1 frame delay)
            Thread.sleep(4)
            drainEncoder(enc)
        } catch (e: Exception) {
            Log.e(TAG, "feedFrame error", e)
        }
    }

    /**
     * Request a key frame (IDR) immediately.
     * Useful when a new client connects and needs to start decoding.
     */
    fun requestKeyFrame() {
        val enc = encoder ?: return
        try {
            val bundle = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            enc.setParameters(bundle)
            Log.d(TAG, "Key frame requested")
        } catch (e: Exception) {
            Log.e(TAG, "requestKeyFrame failed", e)
        }
    }

    /**
     * Warm up the encoder by encoding a dummy black frame.
     * This forces MediaCodec to produce CSD (SPS/PPS) immediately,
     * making them available for SDP generation in DESCRIBE responses
     * before any real frame is encoded.
     */
    fun warmUp(): Boolean {
        if (!isStarted) return false
        val dummy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dummy.eraseColor(android.graphics.Color.BLACK)
        feedFrame(dummy, System.currentTimeMillis())
        dummy.recycle()
        // Wait for CSD to be ready (max 200ms)
        val deadline = System.currentTimeMillis() + 200
        while (!csdReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        Log.d(TAG, "Encoder warmUp complete, csdReady=$csdReady, sps=${csdSps?.size}, pps=${csdPps?.size}")
        return csdReady
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        try {
            encoder?.stop()
        } catch (_: Exception) {}
        try {
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null
        csdReady = false
        csdSps = null
        csdPps = null
        firstRealTimestampMs = 0L
        startTimestampMs = 0L
        Log.d(TAG, "H.264 encoder stopped")
    }

    fun isRunning(): Boolean = isStarted

    private fun drainEncoder(enc: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = enc.dequeueOutputBuffer(bufferInfo, 0L)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break  // No more output available
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Extract CSD (codec configuration data)
                    val newFormat = enc.outputFormat
                    extractCsd(newFormat)
                }
                outputIndex >= 0 -> {
                    var outputBuffer: ByteBuffer? = null
                    try {
                        outputBuffer = enc.getOutputBuffer(outputIndex)
                    } catch (_: Exception) {}

                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Read the encoded data (including CODEC_CONFIG which contains SPS/PPS)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)

                        // Write raw data to dump file for diagnostic analysis
                        dumpData(data)

                        // Split into individual NAL units
                        val nalUnits = splitNalUnits(data)
                        if (nalUnits.isNotEmpty()) {
                            // Diagnostic: log NAL unit types
                            val types = nalUnits.map { (it[0].toInt() and 0x1F).let { t -> "$t(${nalTypeName(t)})" } }
                            val flags = bufferInfo.flags
                            val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            Log.d(TAG, "drain: ${nalUnits.size} NALUs [${types.joinToString(", ")}] " +
                                    "size=${bufferInfo.size}B flags=0x${flags.toString(16)} " +
                                    "pts=${bufferInfo.presentationTimeUs}us${if (isConfig) " CODEC_CONFIG" else ""}")
                            onNalu(nalUnits, bufferInfo.presentationTimeUs)
                        }
                    }

                    enc.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun extractCsd(format: MediaFormat) {
        try {
            val spsRaw = format.getByteBuffer("csd-0") ?: return
            val ppsRaw = format.getByteBuffer("csd-1") ?: return

            csdSps = ByteArray(spsRaw.remaining()).also { spsRaw.get(it) }
            csdPps = ByteArray(ppsRaw.remaining()).also { ppsRaw.get(it) }
            csdReady = true
            Log.d(TAG, "CSD extracted: SPS=${csdSps?.size}B, PPS=${csdPps?.size}B")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract CSD", e)
        }
    }

    /**
     * Convert ARGB_8888 Bitmap to NV12 byte array.
     * NV12: Y plane (w*h) followed by interleaved U/V plane (w*h/2).
     */
    private fun bitmapToNv12(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val yuv = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        var uvIndex = w * h

        for (j in 0 until h) {
            for (i in 0 until w) {
                val pixel = pixels[j * w + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Y component
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                // U and V components (sampled 2x2)
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    // NV12: U at even offset, V at odd offset
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    /**
     * Split a byte array of concatenated H.264 NAL units (with Annex B start codes)
     * into individual NAL units (WITH start code stripped).
     */
    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var start = 0
        var i = 0

        while (i < data.size - 3) {
            // Look for Annex B start code: 0x00000001 or 0x000001
            val is4ByteStart = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val is3ByteStart = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte()

            if (is4ByteStart || is3ByteStart) {
                val codeLen = if (is4ByteStart) 4 else 3
                if (start > 0) {
                    // Extract NAL unit (exclude trailing start code)
                    val nalu = data.copyOfRange(start, i)
                    if (nalu.isNotEmpty()) {
                        nalUnits.add(nalu)
                    }
                }
                start = i + codeLen
                i = start
            } else {
                i++
            }
        }

        // Last NAL unit
        if (start < data.size) {
            val nalu = data.copyOfRange(start, data.size)
            if (nalu.isNotEmpty()) {
                nalUnits.add(nalu)
            }
        }

        return nalUnits
    }

}
