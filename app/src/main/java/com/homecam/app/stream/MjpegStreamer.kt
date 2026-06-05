package com.homecam.app.stream

import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

class MjpegStreamer {

    interface FrameListener {
        fun onFrame(jpegData: ByteArray)
    }

    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val frameListeners = CopyOnWriteArrayList<FrameListener>()

    private val boundary = "--boundary"
    private val crlf = "\r\n"

    fun addClient(output: OutputStream) {
        clients.add(output)
    }

    fun removeClient(output: OutputStream) {
        clients.remove(output)
    }

    fun addFrameListener(listener: FrameListener) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: FrameListener) {
        frameListeners.remove(listener)
    }

    fun pushFrame(jpegData: ByteArray) {
        val header = "$boundary$crlf" +
                "Content-Type: image/jpeg$crlf" +
                "Content-Length: ${jpegData.size}$crlf$crlf"

        val packet = header.toByteArray() + jpegData + crlf.toByteArray()

        // clients list is currently unused (Web MJPEG goes through frameListeners).
        // Skip when empty to avoid unnecessary GC allocations.
        if (clients.isNotEmpty()) {
            val toRemove = mutableListOf<OutputStream>()
            clients.forEach { client ->
                try {
                    client.write(packet)
                    client.flush()
                } catch (e: Exception) {
                    toRemove.add(client)
                }
            }
            clients.removeAll(toRemove)
        }

        frameListeners.forEach { listener ->
            try {
                listener.onFrame(jpegData)
            } catch (_: Exception) {}
        }
    }

    fun clientCount(): Int = clients.size

    fun clear() {
        clients.clear()
        frameListeners.clear()
    }
}
