package com.homecam.app.stream

import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

class RtspSession(
    val clientAddress: InetAddress,
    val clientTcpPort: Int,
    val clientSocket: Socket? = null
) {
    enum class State { INIT, READY, PLAYING, PAUSED, CLOSED }
    enum class TransportMode { UDP, TCP }

    var state: State = State.INIT
    var sessionId: String = UUID.randomUUID().toString()
    var cseq: Int = 0

    // RTP transport parameters
    var clientRtpPort: Int = -1
    var clientRtcpPort: Int = -1
    var serverRtpPort: Int = -1
    var serverSocket: java.net.DatagramSocket? = null

    // TCP interleaved transport
    var transportMode: TransportMode = TransportMode.UDP
    var tcpOutputStream: OutputStream? = null
    var interleavedRtpChannel: Int = 0

    val startTimeMs: Long = System.currentTimeMillis()
    var lastActivityMs: Long = startTimeMs

    // Consecutive RTP send failures: a hard-broken client (cable pulled, process
    // killed without TEARDOWN) fails every send. After a threshold the session
    // is force-closed instead of being kept alive by control-side heartbeats.
    @Volatile var consecutiveSendErrors: Int = 0

    fun isActive(): Boolean = state == State.PLAYING || state == State.PAUSED

    fun isPlaying(): Boolean = state == State.PLAYING

    fun close() {
        state = State.CLOSED
        try { clientSocket?.shutdownInput() } catch (_: Exception) {}
        try { clientSocket?.shutdownOutput() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { (tcpOutputStream as? java.io.Closeable)?.close() } catch (_: Exception) {}
        tcpOutputStream = null
    }

    fun updateActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    override fun toString(): String {
        return "RtspSession(id=${sessionId.take(8)}, state=$state, client=$clientAddress:$clientRtpPort, mode=${transportMode.name})"
    }
}