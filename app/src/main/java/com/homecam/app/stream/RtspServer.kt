package com.homecam.app.stream

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Minimal RTSP 1.0 server supporting H.264 streaming over RTP/AVP (UDP).
 *
 * Supported methods: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, PAUSE
 * Transport: RTP/AVP;unicast;client_port=X-Y
 *
 * The server expects H.264 NAL units via feedH264Nalu() and packetizes them
 * into RTP packets sent to all PLAYING sessions.
 */
class RtspServer {

    companion object {
        private const val TAG = "RtspServer"
        private const val RTSP_VERSION = "RTSP/1.0"
        private const val SERVER_NAME = "HomeCam/1.6.0"
        private const val CRLF = "\r\n"

        // Session timeout: auto-cleanup idle sessions after 60s
        private const val SESSION_TIMEOUT_MS = 60_000L

        // RTP constants
        private const val RTP_CLOCK_HZ = 90000L

        // Sequence number management
        private var globalSeqNum = 0
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val sessions = mutableListOf<RtspSession>()
    private val sessionsLock = Any()

    // Reference to encoder for SDP generation (CSD data)
    private var h264Encoder: H264Encoder? = null

    // RTP packetizer
    private val packetizer = RtpPacketizer()

    // Local IP address (set on start)
    private var localIp: String = "0.0.0.0"

    // Current video parameters (set on start)
    private var videoWidth = 640
    private var videoHeight = 480
    private var videoFps = 15

    // Frame count for RTP timestamp calculation
    private var frameCount = 0L

    fun setEncoder(encoder: H264Encoder?) {
        h264Encoder = encoder
    }

    fun setVideoParams(width: Int, height: Int, fps: Int) {
        videoWidth = width
        videoHeight = height
        videoFps = fps
    }

    fun start(port: Int) {
        if (isRunning) return
        isRunning = true
        frameCount = 0L
        packetizer.resetSsrc()

        try {
            serverSocket = ServerSocket(port).also {
                it.soTimeout = 1000  // 1s timeout for accept() to allow clean shutdown
            }
            localIp = getLocalIpAddress()
            Log.i(TAG, "RTSP server listening on port $port (IP: $localIp)")

            // Start accept loop in background thread
            Thread({ runAcceptLoop() }, "rtsp-accept").also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server on port $port", e)
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        synchronized(sessionsLock) {
            sessions.forEach { it.close() }
            sessions.clear()
        }
        Log.i(TAG, "RTSP server stopped")
    }

    fun isActive(): Boolean = isRunning

    /**
     * Called by H264Encoder to deliver encoded NAL units.
     * This method synchronously packetizes and sends RTP packets to all PLAYING sessions.
     */
    fun feedH264Nalu(nalUnits: List<ByteArray>, timestampUs: Long) {
        if (!isRunning) return

        var playingSessions: List<RtspSession>
        synchronized(sessionsLock) {
            playingSessions = sessions.filter { it.isPlaying() }.toList()
        }

        if (playingSessions.isEmpty()) return

        frameCount++
        // RTP timestamp: 90000 Hz clock
        // timestampUs is microseconds from encoder, convert to 90kHz units
        val rtpTimestamp = ((timestampUs * RTP_CLOCK_HZ) / 1_000_000L) and 0xFFFFFFFFL

        // Diagnostic: log NAL unit types being sent
        if (frameCount <= 30 || frameCount % 150 == 0L) {
            val types = nalUnits.map { (it[0].toInt() and 0x1F) }
            Log.d(TAG, "feedH264: frame#$frameCount ${nalUnits.size} NALUs types=$types " +
                    "rtpTs=$rtpTimestamp ${playingSessions.size} session(s)")
        }

        for (nalu in nalUnits) {
            // Strip Annex B start code from the NAL unit
            val cleanNalu = packetizer.stripStartCode(nalu)
            if (cleanNalu.isEmpty()) continue

            // Determine if this is the last NAL unit of the frame (marker on last NALU's last packet)
            val isLastNalu = nalu === nalUnits.last()

            // Get sequence number for this NAL unit's packets (shared across all sessions)
            val startSeqNum = getNextSeqNum()
            var endSeqNum = startSeqNum

            for (session in playingSessions) {
                try {
                    val socket = session.serverSocket ?: continue
                    val result = packetizer.packetizeNalu(
                        nalu = cleanNalu,
                        timestamp = rtpTimestamp,
                        startSeqNum = startSeqNum,
                        marker = isLastNalu,
                        targetAddress = session.clientAddress,
                        targetPort = session.clientRtpPort
                    )
                    endSeqNum = result.nextSeqNum

                    // Send all packets
                    var totalBytes = 0
                    for (pkt in result.packets) {
                        socket.send(pkt)
                        totalBytes += pkt.length
                    }
                    session.updateActivity()

                    // Diagnostic: log first few RTP sends
                    if (frameCount <= 10) {
                        Log.d(TAG, "RTP sent: ${result.packets.size} pkt(s), $totalBytes bytes " +
                                "to ${session.clientAddress}:${session.clientRtpPort}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "RTP send error to ${session.clientAddress}:${session.clientRtpPort}", e)
                }
            }

            // Advance global sequence number by actual number of packets produced
            updateSeqNum(endSeqNum)
        }
    }

    /**
     * Request a key frame to help newly connected clients decode faster.
     */
    fun requestKeyFrame() {
        h264Encoder?.requestKeyFrame()
    }

    // ---- Private: Accept Loop ----

    private fun runAcceptLoop() {
        val sock = serverSocket ?: return

        while (isRunning) {
            try {
                val clientSocket = sock.accept()
                Log.d(TAG, "New RTSP connection from ${clientSocket.inetAddress}:${clientSocket.port}")
                Thread({
                    handleClient(clientSocket)
                }, "rtsp-client-${clientSocket.port}").also { it.isDaemon = true }.start()
            } catch (e: SocketTimeoutException) {
                // Normal timeout, check if we should continue
                cleanupStaleSessions()
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Accept error", e)
                }
            }
        }
    }

    // ---- Private: Client Handler ----

    private data class RtspRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 30_000  // 30s read timeout

            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(clientSocket.getOutputStream(), Charsets.UTF_8)

            var session: RtspSession? = null

            while (isRunning && !clientSocket.isClosed) {
                val request = parseRequest(reader) ?: break

                Log.d(TAG, "RTSP ${request.method} ${request.url}")

                val cseq = request.headers["cseq"]?.toIntOrNull() ?: 0

                when (request.method.uppercase()) {
                    "OPTIONS" -> handleOptions(writer, cseq)
                    "DESCRIBE" -> {
                        session = RtspSession(clientSocket.inetAddress, clientSocket.port)
                        session.cseq = cseq
                        synchronized(sessionsLock) { sessions.add(session!!) }
                        handleDescribe(writer, cseq, session)
                    }
                    "SETUP" -> {
                        if (session != null) {
                            handleSetup(writer, cseq, session, request)
                        } else {
                            sendError(writer, cseq, 455, "Method Not Valid In This State")
                        }
                    }
                    "PLAY" -> {
                        if (session != null && session.state == RtspSession.State.READY) {
                            handlePlay(writer, cseq, session)
                        } else {
                            val stateName = session?.state?.name ?: "NO_SESSION"
                            sendError(writer, cseq, 455, "Method Not Valid In This State ($stateName)")
                        }
                    }
                    "PAUSE" -> {
                        if (session != null && session.state == RtspSession.State.PLAYING) {
                            handlePause(writer, cseq, session)
                        } else {
                            sendError(writer, cseq, 455, "Method Not Valid In This State")
                        }
                    }
                    "TEARDOWN" -> {
                        if (session != null) {
                            handleTeardown(writer, cseq, session)
                            session = null
                        } else {
                            sendResponse(writer, cseq, 200, "OK")
                        }
                        break  // Close connection after TEARDOWN
                    }
                    else -> sendError(writer, cseq, 501, "Not Implemented")
                }
            }
        } catch (e: java.net.SocketException) {
            // Client disconnected
            Log.d(TAG, "RTSP client disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "RTSP client handler error", e)
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    // ---- Private: Request Parsing ----

    private fun parseRequest(reader: BufferedReader): RtspRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ", limit = 3)
        if (parts.size < 3) return null

        val method = parts[0]
        val url = parts[1]
        val headers = mutableMapOf<String, String>()
        var contentLength = 0

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break  // End of headers

            val colonIndex = line.indexOf(":")
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().lowercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
                if (key == "content-length") {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            reader.read(buf, 0, contentLength)
            String(buf)
        } else ""

        return RtspRequest(method, url, headers, body)
    }

    // ---- Private: RTSP Command Handlers ----

    private var ssrc: Long = (1000000L..9999999L).random()

    private fun handleOptions(writer: OutputStreamWriter, cseq: Int) {
        sendResponse(writer, cseq, 200, "OK",
            mapOf("Public" to "DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, OPTIONS"))
    }

    private fun handleDescribe(writer: OutputStreamWriter, cseq: Int, session: RtspSession) {
        val encoder = h264Encoder

        // Get SPS/PPS from encoder, or use placeholder
        val sdp = buildSdp(session, encoder)

        session.state = RtspSession.State.READY

        Log.i(TAG, "=== DESCRIBE response (SDP) for session ${session.sessionId.take(8)} ===")
        Log.i(TAG, "Encoder CSD ready: ${encoder?.csdReady ?: false}")
        Log.i(TAG, "SPS bytes: ${encoder?.csdSps?.size}, PPS bytes: ${encoder?.csdPps?.size}")
        if (encoder?.csdSps != null) {
            Log.i(TAG, "SPS (first 8 hex): " + encoder.csdSps!!.take(8).joinToString("") { "%02x".format(it) })
        }
        if (encoder?.csdPps != null) {
            Log.i(TAG, "PPS (first 4 hex): " + encoder.csdPps!!.take(4).joinToString("") { "%02x".format(it) })
        }
        Log.d(TAG, "SDP:\n$sdp")

        sendResponseWithBody(writer, cseq, 200, "OK",
            mapOf(
                "Content-Base" to "rtsp://$localIp:${serverSocket?.localPort ?: 8554}/live/",
                "Content-Type" to "application/sdp"
            ),
            sdp
        )
    }

    private fun handleSetup(writer: OutputStreamWriter, cseq: Int, session: RtspSession, request: RtspRequest) {
        val transport = request.headers["transport"] ?: ""
        if (!transport.contains("RTP/AVP")) {
            sendError(writer, cseq, 461, "Unsupported Transport")
            return
        }

        // Parse client port from Transport header
        // Format: RTP/AVP;unicast;client_port=PORT-PORT
        val clientPortMatch = Regex("client_port=(\\d+)-(\\d+)").find(transport)
        if (clientPortMatch == null) {
            sendError(writer, cseq, 400, "Missing client_port in Transport header")
            return
        }

        session.clientRtpPort = clientPortMatch.groupValues[1].toInt()
        session.clientRtcpPort = clientPortMatch.groupValues[2].toInt()

        // Create a local UDP socket for sending RTP to this session
        try {
            session.serverSocket = DatagramSocket()
            session.serverRtpPort = session.serverSocket!!.localPort
        } catch (e: Exception) {
            sendError(writer, cseq, 500, "Failed to allocate RTP socket")
            return
        }

        session.state = RtspSession.State.READY

        val responseTransport = "RTP/AVP;unicast;client_port=${session.clientRtpPort}-${session.clientRtcpPort};server_port=${session.serverRtpPort}-${session.serverRtpPort + 1}"
        sendResponse(writer, cseq, 200, "OK",
            mapOf(
                "Transport" to responseTransport,
                "Session" to session.sessionId
            )
        )
    }

    private fun handlePlay(writer: OutputStreamWriter, cseq: Int, session: RtspSession) {
        session.state = RtspSession.State.PLAYING
        session.updateActivity()

        // Request key frame so client can start decoding immediately
        requestKeyFrame()

        sendResponse(writer, cseq, 200, "OK",
            mapOf(
                "Session" to session.sessionId,
                "Range" to "npt=0.000-"
            )
        )
    }

    private fun handlePause(writer: OutputStreamWriter, cseq: Int, session: RtspSession) {
        session.state = RtspSession.State.PAUSED
        sendResponse(writer, cseq, 200, "OK",
            mapOf("Session" to session.sessionId)
        )
    }

    private fun handleTeardown(writer: OutputStreamWriter, cseq: Int, session: RtspSession) {
        session.close()
        synchronized(sessionsLock) {
            sessions.remove(session)
        }
        sendResponse(writer, cseq, 200, "OK")
        Log.i(TAG, "Session ${session.sessionId.take(8)} terminated")
    }

    // ---- Private: SDP Builder ----

    private fun buildSdp(session: RtspSession, encoder: H264Encoder?): String {
        val sps = encoder?.csdSps
        val pps = encoder?.csdPps

        // Base64 encode SPS/PPS for SDP fmtp line
        val spsB64 = if (sps != null) stripStartCode(sps).let { Base64.encodeToString(it, Base64.NO_WRAP) } else ""
        val ppsB64 = if (pps != null) stripStartCode(pps).let { Base64.encodeToString(it, Base64.NO_WRAP) } else ""

        // Profile-level-id: extract from SPS bytes (byte 1 is profile, byte 2 is profile compat, byte 3 is level)
        val profileLevelId = if (sps != null) {
            val cleanSps = stripStartCode(sps)
            if (cleanSps.size >= 4) {
                String.format("%02X%02X%02X", cleanSps[1], cleanSps[2], cleanSps[3])
            } else "42C01F"  // Default Constrained Baseline 3.1
        } else "42C01F"

        return buildString {
            appendLine("v=0")
            appendLine("o=- ${session.sessionId} 0 IN IP4 $localIp")
            appendLine("s=HomeCam Live Stream")
            appendLine("c=IN IP4 0.0.0.0")
            appendLine("t=0 0")
            appendLine("a=control:*")
            append("m=video 0 RTP/AVP 96")
            append(CRLF)
            appendLine("a=rtpmap:96 H264/90000")
            if (spsB64.isNotEmpty() && ppsB64.isNotEmpty()) {
                appendLine("a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64")
            } else {
                appendLine("a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId")
            }
            appendLine("a=control:trackID=0")
            appendLine("a=framerate:$videoFps")
            appendLine("a=x-dimensions:$videoWidth,$videoHeight")
        }
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        return when {
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 0.toByte() && data[3] == 1.toByte() ->
                data.copyOfRange(4, data.size)
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
                    data[2] == 1.toByte() ->
                data.copyOfRange(3, data.size)
            else -> data
        }
    }

    // ---- Private: Response Writers ----

    private fun sendResponse(writer: OutputStreamWriter, cseq: Int, code: Int, reason: String, extraHeaders: Map<String, String>? = null) {
        val sb = StringBuilder()
        sb.append("$RTSP_VERSION $code $reason$CRLF")
        sb.append("CSeq: $cseq$CRLF")
        sb.append("Server: $SERVER_NAME$CRLF")
        if (extraHeaders != null) {
            for ((key, value) in extraHeaders) {
                sb.append("$key: $value$CRLF")
            }
        }
        sb.append(CRLF)  // End of headers

        writer.write(sb.toString())
        writer.flush()
        Log.d(TAG, "Response: $code $reason")
    }

    private fun sendResponseWithBody(
        writer: OutputStreamWriter, cseq: Int, code: Int, reason: String,
        extraHeaders: Map<String, String>,
        body: String
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("$RTSP_VERSION $code $reason$CRLF")
        sb.append("CSeq: $cseq$CRLF")
        sb.append("Server: $SERVER_NAME$CRLF")
        sb.append("Content-Length: ${bodyBytes.size}$CRLF")
        sb.append("Content-Type: application/sdp$CRLF")
        for ((key, value) in extraHeaders) {
            sb.append("$key: $value$CRLF")
        }
        sb.append(CRLF)  // End of headers

        writer.write(sb.toString())
        writer.write(body)
        writer.flush()
        Log.d(TAG, "Response: $code $reason (body=${bodyBytes.size}B)")
    }

    private fun sendError(writer: OutputStreamWriter, cseq: Int, code: Int, message: String) {
        sendResponse(writer, cseq, code, message)
    }

    // ---- Private: Utilities ----

    private fun <T> appendLine(sb: StringBuilder, line: T) {
        sb.append(line).append(CRLF)
    }

    private fun StringBuilder.appendLine(value: String) {
        this.append(value).append(CRLF)
    }

    @Synchronized
    private fun getNextSeqNum(): Int = globalSeqNum

    @Synchronized
    private fun updateSeqNum(newVal: Int) {
        globalSeqNum = newVal and 0xFFFF
    }

    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        synchronized(sessionsLock) {
            val toRemove = mutableListOf<RtspSession>()
            for (session in sessions) {
                if (!session.isActive() && now - session.lastActivityMs > SESSION_TIMEOUT_MS) {
                    toRemove.add(session)
                }
            }
            for (session in toRemove) {
                session.close()
                sessions.remove(session)
                Log.d(TAG, "Cleaned up stale session ${session.sessionId.take(8)}")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }
}