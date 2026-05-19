package com.homecam.app.web

import com.homecam.app.stream.MjpegStreamer
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MjpegInputStream(private val streamer: MjpegStreamer) : InputStream() {

    private data class TimedPacket(val data: ByteArray, val timestamp: Long)

    private val queue = LinkedBlockingQueue<TimedPacket>(30)
    private val closed = AtomicBoolean(false)
    private var currentBuffer: ByteArray = byteArrayOf()
    private var currentPos = 0
    private val frameSequence = AtomicLong(0)
    private val maxFrameAgeMs = 1500L

    private val listener = object : MjpegStreamer.FrameListener {
        override fun onFrame(jpegData: ByteArray) {
            if (closed.get()) return
            val seq = frameSequence.incrementAndGet()
            val header = """--boundary\r\n""" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpegData.size}\r\n" +
                    "X-Frame-Sequence: $seq\r\n\r\n"
            val packet = header.toByteArray() + jpegData + "\r\n".toByteArray()
            val timed = TimedPacket(packet, System.currentTimeMillis())
            if (!queue.offer(timed)) {
                queue.clear()
                queue.offer(timed)
            }
        }
    }

    init {
        streamer.addFrameListener(listener)
    }

    override fun read(): Int {
        if (closed.get()) return -1
        if (currentPos >= currentBuffer.size) {
            currentBuffer = nextFreshFrame() ?: return -1
            currentPos = 0
        }
        return currentBuffer[currentPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) return -1
        if (currentPos >= currentBuffer.size) {
            currentBuffer = try {
                nextFreshFrame() ?: return -1
            } catch (_: InterruptedException) {
                return -1
            }
            currentPos = 0
        }
        val available = currentBuffer.size - currentPos
        val toCopy = minOf(len, available)
        System.arraycopy(currentBuffer, currentPos, b, off, toCopy)
        currentPos += toCopy
        return toCopy
    }

    private fun nextFreshFrame(): ByteArray? {
        val now = System.currentTimeMillis()
        while (true) {
            val packet = queue.take()
            if (now - packet.timestamp < maxFrameAgeMs) {
                return packet.data
            }
            // Frame is stale, discard and try next
        }
    }

    override fun available(): Int {
        return currentBuffer.size - currentPos
    }

    override fun close() {
        closed.set(true)
        streamer.removeFrameListener(listener)
        queue.clear()
    }
}
