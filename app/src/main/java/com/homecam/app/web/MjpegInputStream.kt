package com.homecam.app.web

import com.homecam.app.stream.MjpegStreamer
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class MjpegInputStream(private val streamer: MjpegStreamer) : InputStream() {

    private val queue = LinkedBlockingQueue<ByteArray>(30)
    private val closed = AtomicBoolean(false)
    private var currentBuffer: ByteArray = byteArrayOf()
    private var currentPos = 0

    private val listener = object : MjpegStreamer.FrameListener {
        override fun onFrame(jpegData: ByteArray) {
            if (closed.get()) return
            val header = "--boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegData.size}\r\n\r\n"
            val packet = header.toByteArray() + jpegData + "\r\n".toByteArray()
            if (!queue.offer(packet)) {
                queue.poll()
                queue.offer(packet)
            }
        }
    }

    init {
        streamer.addFrameListener(listener)
    }

    override fun read(): Int {
        if (closed.get()) return -1
        if (currentPos >= currentBuffer.size) {
            currentBuffer = queue.take() ?: return -1
            currentPos = 0
        }
        return currentBuffer[currentPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) return -1
        if (currentPos >= currentBuffer.size) {
            currentBuffer = try {
                queue.take()
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

    override fun available(): Int {
        return currentBuffer.size - currentPos
    }

    override fun close() {
        closed.set(true)
        streamer.removeFrameListener(listener)
        queue.clear()
    }
}
