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

    // Actual configured encoder dimensions (may differ from requested if aligned to 16)
    private var encWidth: Int = width
    private var encHeight: Int = height
    private var encPlanar: Boolean = false

    // ---- Diagnostics counters (exposed via /api/diag/encoder) ----
    @Volatile var currentAttemptIndex: Int = -1; private set
    @Volatile var framesFed: Long = 0; private set
    @Volatile var inputStarvedDrops: Long = 0; private set
    @Volatile var outputBuffers: Long = 0; private set
    @Volatile var nalusEmitted: Long = 0; private set
    @Volatile var feedErrors: Long = 0; private set
    @Volatile var lastFeedError: String = ""; private set
    @Volatile var encoderRestarts: Int = 0; private set
    @Volatile var lastFedAtMs: Long = 0; private set
    @Volatile var lastOutputAtMs: Long = 0; private set

    // Stall watchdog: some HALs (QTI/MIUI) pass configure() yet never emit a single
    // encoded frame ("silent stall" — SDP gets a static default SPS from
    // INFO_OUTPUT_FORMAT_CHANGED, so csdReady=true while output is dead).
    // Detect: frames are being fed but nothing comes out -> release codec and
    // reconfigure with the NEXT simpler attempt, cycling through all attempts.
    private val stallCheckEveryNFrames = 45L   // ~3 s at 15 fps
    private var fedSinceLastOutput: Long = 0
    private var lastRestartAtMs: Long = 0

    // Progressively simpler configurations. Some devices (e.g. MIUI/QTI HAL)
    // reject low-latency hints, B-frame keys or non-16-aligned dimensions —
    // and some stall AFTER a successful configure, so the same ladder is
    // re-used by the stall watchdog below.
    private val attempts = listOf(
        EncoderAttempt(lowLatency = true, bFramesKey = true, align16 = false),
        EncoderAttempt(lowLatency = false, bFramesKey = false, align16 = false),
        EncoderAttempt(lowLatency = false, bFramesKey = false, align16 = true),
        EncoderAttempt(lowLatency = false, bFramesKey = false, align16 = true, planar = true)
    )

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
        startFromAttempt(0)
    }

    /**
     * (Re)configure the codec beginning at `fromIndex` in the attempt ladder.
     * Used both for initial start and for stall-watchdog recovery.
     */
    private fun startFromAttempt(fromIndex: Int) {
        val bitrate = calculateBitrate(width, height, fps)

        for (index in fromIndex until attempts.size) {
            val attempt = attempts[index]
            val cfgW = if (attempt.align16) (width / 16) * 16 else width
            val cfgH = if (attempt.align16) (height / 16) * 16 else height
            if (cfgW <= 0 || cfgH <= 0) continue
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, cfgW, cfgH).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)  // IDR every 1 second
                    if (attempt.bFramesKey) {
                        try { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) } catch (_: Exception) {}
                    }
                    if (attempt.lowLatency && android.os.Build.VERSION.SDK_INT >= 29) {
                        try { setInteger("low-latency", 1) } catch (_: Exception) {}
                    }
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        if (attempt.planar) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                        else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    )
                    try {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    } catch (_: Exception) {}
                }

                codec = MediaCodec.createEncoderByType(MIME_TYPE)
                codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec!!.start()

                encoder = codec
                encWidth = cfgW
                encHeight = cfgH
                encPlanar = attempt.planar
                isStarted = true
                currentAttemptIndex = index
                fedSinceLastOutput = 0
                lastRestartAtMs = System.currentTimeMillis()
                Log.i(TAG, "H.264 encoder started (attempt $index): ${cfgW}x${cfgH} @ ${fps}fps, " +
                        "bitrate=$bitrate, planar=${attempt.planar}, lowLatency=${attempt.lowLatency}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Encoder config attempt $index failed (${cfgW}x${cfgH}, " +
                        "planar=${attempt.planar}, lowLatency=${attempt.lowLatency}): ${e.message}")
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                encoder = null
            }
        }

        Log.e(TAG, "Failed to start H.264 encoder: all configuration attempts failed (from $fromIndex)")
        isStarted = false
        currentAttemptIndex = -1
        encoder = null
    }

    private fun codec_release_quietly() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
    }

    /**
     * Stall watchdog: reconfigure the codec at the NEXT attempt in the ladder
     * (wrapping around). Called from feedFrame on the camera thread.
     */
    private fun restartOnStall() {
        val now = System.currentTimeMillis()
        if (now - lastRestartAtMs < 3000) return  // rate-limit restarts
        lastRestartAtMs = now  // set up-front: also throttles the all-attempts-failed case
        val nextIndex = ((currentAttemptIndex + 1) % attempts.size)
        Log.w(TAG, "Encoder stall detected: fed=$framesFed outputBuffers=$outputBuffers " +
                "attempt=$currentAttemptIndex -> restarting from attempt $nextIndex")
        encoderRestarts++
        codec_release_quietly()
        encoder = null
        isStarted = false
        firstRealTimestampMs = 0L
        // Reset CSD so the new configuration's SPS/PPS is re-extracted
        // (align16 attempts change coded dimensions; a stale SDP would
        // mislead decoders).
        csdReady = false
        csdSps = null
        csdPps = null
        startFromAttempt(nextIndex)
        if (isStarted) {
            // Re-warm so SDP keeps valid CSD and output pipeline is primed
            try { warmUp() } catch (_: Exception) {}
        }
    }

    private data class EncoderAttempt(
        val lowLatency: Boolean,
        val bFramesKey: Boolean,
        val align16: Boolean,
        val planar: Boolean = false
    )

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

            framesFed++
            lastFedAtMs = System.currentTimeMillis()

            // Convert Bitmap to YUV byte array (NV12 or I420, padded to encoder dimensions)
            val yuv = bitmapToYuv(bitmap)

            val inputBufferIndex = enc.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex < 0) {
                // Encoder is backed up, drop this frame
                inputStarvedDrops++
                checkStall()
                return
            }

            val inputBuffer: ByteBuffer? = enc.getInputBuffer(inputBufferIndex)
            if (inputBuffer == null) {
                try { enc.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0) } catch (_: Exception) {}
                inputStarvedDrops++
                return
            }
            inputBuffer.clear()
            // Never overflow the codec's input buffer: some HALs hand out a
            // capacity smaller than the configured W*H*1.5 (stride/slice
            // padding differs). Feed what fits; a short tail is still a
            // valid frame for the encoder.
            val toPut = minOf(yuv.size, inputBuffer.remaining())
            inputBuffer.put(yuv, 0, toPut)

            // Presentation timestamp in microseconds, relative to first frame
            val ptsUs = (realTimestampMs - firstRealTimestampMs) * 1000

            enc.queueInputBuffer(inputBufferIndex, 0, toPut, ptsUs, 0)

            // Drain all available output buffers
            drainEncoder(enc)
            // Retry drain with short delay to reduce encoding latency
            // Without this, encoded output may only appear on the NEXT feedFrame call (1 frame delay)
            Thread.sleep(4)
            drainEncoder(enc)

            checkStall()
        } catch (e: Exception) {
            feedErrors++
            lastFeedError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "feedFrame error", e)
        }
    }

    /**
     * Called after each fed frame. If many frames went in with nothing coming
     * out, the codec silently stalled — cycle to the next configuration.
     */
    private fun checkStall() {
        fedSinceLastOutput++
        if (fedSinceLastOutput >= stallCheckEveryNFrames) {
            restartOnStall()
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
     * Warm up the encoder by encoding dummy black frames.
     * Loops until CSD (SPS/PPS) is produced or a 2s deadline passes,
     * making SPS/PPS available for SDP generation in DESCRIBE responses
     * before any real frame is encoded. Some hardware encoders emit the
     * first output only after several input frames.
     */
    fun warmUp(): Boolean {
        if (!isStarted) return false
        val dummy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dummy.eraseColor(android.graphics.Color.BLACK)
        var ts = System.currentTimeMillis()
        val deadline = ts + 2000
        var fed = 0
        while (!csdReady && ts < deadline && fed < 10) {
            feedFrame(dummy, ts)
            fed++
            ts += 66  // ~15fps pacing
            if (!csdReady) Thread.sleep(50)
        }
        dummy.recycle()
        Log.d(TAG, "Encoder warmUp complete after $fed frame(s), csdReady=$csdReady, sps=${csdSps?.size}, pps=${csdPps?.size}")
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

                        outputBuffers++
                        lastOutputAtMs = System.currentTimeMillis()
                        fedSinceLastOutput = 0

                        // Write raw data to dump file for diagnostic analysis
                        dumpData(data)

                        // Split into individual NAL units
                        val nalUnits = splitNalUnits(data)

                        val flags = bufferInfo.flags
                        val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                        // Some devices report SPS/PPS only via CODEC_CONFIG buffers
                        // instead of INFO_OUTPUT_FORMAT_CHANGED. Extract CSD from them too.
                        if (isConfig && !csdReady) {
                            for (nalu in nalUnits) {
                                if (nalu.isEmpty()) continue
                                val nalType = nalu[0].toInt() and 0x1F
                                if (nalType == 7 && csdSps == null) csdSps = nalu.copyOf()
                                if (nalType == 8 && csdPps == null) csdPps = nalu.copyOf()
                            }
                            if (csdSps != null && csdPps != null) {
                                csdReady = true
                                Log.d(TAG, "CSD extracted from CODEC_CONFIG buffer: SPS=${csdSps?.size}B, PPS=${csdPps?.size}B")
                            }
                        }

                        if (nalUnits.isNotEmpty()) {
                            // Diagnostic: log NAL unit types
                            val types = nalUnits.map { (it[0].toInt() and 0x1F).let { t -> "$t(${nalTypeName(t)})" } }
                            Log.d(TAG, "drain: ${nalUnits.size} NALUs [${types.joinToString(", ")}] " +
                                    "size=${bufferInfo.size}B flags=0x${flags.toString(16)} " +
                                    "pts=${bufferInfo.presentationTimeUs}us${if (isConfig) " CODEC_CONFIG" else ""}")
                            nalusEmitted += nalUnits.size
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
     * Convert ARGB_8888 Bitmap to YUV byte array matching the configured encoder format.
     * NV12 (semi-planar): Y plane (w*h) followed by interleaved U/V plane (w*h/2).
     * I420 (planar): Y plane, then U plane (w*h/4), then V plane (w*h/4).
     * Output is padded to encWidth x encHeight (source pixels placed top-left).
     */
    private fun bitmapToYuv(bitmap: Bitmap): ByteArray {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val w = encWidth
        val h = encHeight
        val pixels = IntArray(srcW * srcH)
        bitmap.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH)

        val yuv = ByteArray(w * h * 3 / 2)

        // Y plane: source pixels, black padding elsewhere
        // Pre-fill entire Y plane with black (16)
        for (i in 0 until w * h) yuv[i] = 16

        if (!encPlanar) {
            // NV12: pre-fill UV plane with neutral 128
            for (i in w * h until yuv.size) yuv[i] = 128.toByte()
        } else {
            val uOffset = w * h
            val vOffset = uOffset + (w * h) / 4
            for (i in uOffset until yuv.size) yuv[i] = 128.toByte()
        }

        var uvIndex = w * h
        val uPlaneStart = w * h
        val uPlaneSize = (w / 2) * (h / 2)
        val vPlaneStart = uPlaneStart + uPlaneSize

        // Clamp copy region: when 16-alignment shrinks encoder dims below the
        // source bitmap, only copy the overlapping top-left region. The rest
        // stays as the pre-filled black/neutral padding. Prevents row bleed
        // (image tearing) and out-of-bounds writes (crash).
        val copyW = minOf(srcW, w)
        val copyH = minOf(srcH, h)

        for (j in 0 until copyH) {
            val yRowBase = j * w
            val srcRowBase = j * srcW
            for (i in 0 until copyW) {
                val pixel = pixels[srcRowBase + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yRowBase + i] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (!encPlanar) {
                        // NV12: interleaved UV
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        // I420: separate U and V planes
                        val uvIdx = (j / 2) * (w / 2) + (i / 2)
                        yuv[uPlaneStart + uvIdx] = u.coerceIn(0, 255).toByte()
                        yuv[vPlaneStart + uvIdx] = v.coerceIn(0, 255).toByte()
                    }
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
