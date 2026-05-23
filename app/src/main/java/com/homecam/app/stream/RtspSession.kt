package com.homecam.app.stream

import java.net.InetAddress
import java.util.UUID

/**
 * Represents the state of a single RTSP client session.
 *
 * State machine:
 * INIT -> (DESCRIBE) -> READY -> (SETUP) -> READY -> (PLAY) -> PLAYING -> (TEARDOWN) -> CLOSED
 *                                                              -> (PAUSE) -> READY
 */
class RtspSession(
    val clientAddress: InetAddress,
    val clientTcpPort: Int
) {
    enum class State { INIT, READY, PLAYING, PAUSED, CLOSED }

    var state: State = State.INIT
    var sessionId: String = UUID.randomUUID().toString()
    var cseq: Int = 0

    // RTP transport parameters
    var clientRtpPort: Int = -1
    var clientRtcpPort: Int = -1
    var serverRtpPort: Int = -1
    var serverSocket: java.net.DatagramSocket? = null

    val startTimeMs: Long = System.currentTimeMillis()
    var lastActivityMs: Long = startTimeMs

    fun isActive(): Boolean = state == State.PLAYING || state == State.PAUSED

    fun isPlaying(): Boolean = state == State.PLAYING

    fun close() {
        state = State.CLOSED
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun updateActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    override fun toString(): String {
        return "RtspSession(id=${sessionId.take(8)}, state=$state, client=$clientAddress:$clientRtpPort)"
    }
}
