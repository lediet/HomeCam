package com.homecam.app.web

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.homecam.app.service.AppSettings
import com.homecam.app.service.CameraService
import com.homecam.app.service.CameraUtils
import com.homecam.app.HomeCamApp
import com.homecam.app.service.ServiceManager
import kotlinx.coroutines.runBlocking
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class CamWebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CamWebServer"
    }

    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    private var udpThread: Thread? = null
    private val udpPort = 45678
    private val deviceId: String by lazy {
        try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) { "unknown" }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        return when {
            uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html")
            uri == "/style.css" -> serveAsset("web/style.css", "text/css")
            uri == "/app.js" -> serveAsset("web/app.js", "application/javascript")
            uri == "/video" -> serveMjpegStream()
            uri == "/api/cameras" -> serveCameraList()
            uri == "/api/camera/switch" -> serveCameraSwitch(session)
            uri == "/api/camera/power" -> serveCameraPower(session)
            uri == "/api/status" -> serveStatus()
            uri == "/api/events" -> serveEvents()
            uri == "/api/videos" || uri == "/api/videos/" -> serveVideoList()
            uri.startsWith("/videos/") -> serveVideoFile(uri)
            uri == "/api/frame.jpg" -> serveFrameJpeg()
            uri.startsWith("/api/thumbnails/") -> serveThumbnail(uri)
            uri == "/api/diag/dump" -> serveDiagDump(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveAsset(path: String, mimeType: String): Response {
        return try {
            val input = context.assets.open(path)
            val bytes = input.readBytes()
            input.close()
            newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveMjpegStream(): Response {
        if (!AppSettings.isMjpgEnabled(context)) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT,
                "MJPEG streaming disabled"
            )
        }
        val service = ServiceManager.instance
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Service not running")

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=--boundary",
            MjpegInputStream(service.streamer)
        )
    }

    private fun serveStatus(): Response {
        val service = ServiceManager.instance
        val ip = getLocalIpAddress()
        val port = AppSettings.getWebPort(context)

        val status = mapOf(
            "running" to (service != null),
            "ip" to ip,
            "port" to port,
            "url" to "http://$ip:$port",
            "detection_modes" to mapOf(
                "motion" to AppSettings.isMotionDetectionEnabled(context),
                "cry" to AppSettings.isCryDetectionEnabled(context),
                "sleep" to AppSettings.isSleepDetectionEnabled(context),
                "fall" to AppSettings.isFallDetectionEnabled(context),
                "phone" to AppSettings.isPhoneDetectionEnabled(context)
            ),
            "latest_event" to CameraService.latestEventType,
            "latest_event_time" to CameraService.latestEventTime,
            "latest_event_label" to CameraService.latestEventLabel,
            "current_camera_id" to AppSettings.getCameraId(context),
            "current_logical_camera_id" to AppSettings.getLogicalCameraId(context),
            "camera_powered" to CameraService.cameraPoweredOn.get(),
            "rtsp_url" to "rtsp://$ip:${AppSettings.getRtspPort(context)}/live",
            "rtsp_enabled" to AppSettings.isRtspEnabled(context),
            "mjpg_url" to "http://$ip:$port",
            "mjpg_enabled" to AppSettings.isMjpgEnabled(context)
        )

        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
    }

    private fun serveEvents(): Response {
        val events = synchronized(CameraService.eventHistory) {
            CameraService.eventHistory.map { record ->
                mapOf(
                    "type" to record.type,
                    "time" to record.time,
                    "label" to record.label
                )
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(events))
    }

    private fun serveVideoList(): Response {
        return try {
            val app = context.applicationContext as HomeCamApp
            val videos = runBlocking {
                app.database.videoDao().getAll()
            }
            val videoDir = context.getExternalFilesDir(null)?.let { java.io.File(it, "HomeCam") }
            val service = ServiceManager.instance
            val fileMap = mutableMapOf<String, Map<String, Any>>()
            val knownNames = mutableSetOf<String>()
            videos.forEach { record ->
                // Only include DB records whose files still exist on disk
                if (videoDir != null && java.io.File(videoDir, record.fileName).exists()) {
                    knownNames.add(record.fileName)
                    fileMap[record.fileName] = mapOf(
                        "fileName" to record.fileName,
                        "timestamp" to record.timestamp,
                        "eventType" to record.eventType,
                        "eventLabel" to record.eventLabel,
                        "durationSec" to record.durationSec,
                        "fileSize" to record.fileSize,
                        "url" to "/videos/${record.fileName}"
                    )
                }
            }
            val recorder = service?.videoRecorder
            if (recorder != null) {
                val dir = recorder.getOutputDir()
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                if (files != null) {
                    files.sortedByDescending { it.lastModified() }.forEach { f ->
                        if (f.name !in knownNames) {
                            fileMap[f.name] = mapOf(
                                "fileName" to f.name,
                                "timestamp" to f.lastModified(),
                                "eventType" to "unknown",
                                "durationSec" to 0,
                                "fileSize" to f.length(),
                                "url" to "/videos/${f.name}"
                            )
                        }
                    }
                }
            }
            val resultList = fileMap.values.toList().sortedByDescending { it["timestamp"] as Long }
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(resultList))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    private fun serveVideoFile(uri: String): Response {
        val fileName = uri.removePrefix("/videos/")
        if (fileName.contains("..") || fileName.contains("/")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid file name")
        }
        val service = ServiceManager.instance
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Service not running")

        val file = service?.videoRecorder?.getVideoFile(fileName)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video not found")

        return try {
            val fis = FileInputStream(file)
            newChunkedResponse(Response.Status.OK, "video/mp4", fis)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading file")
        }
    }

    private fun serveFrameJpeg(): Response {
        val service = ServiceManager.instance
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Service not running")

        val frame = service.frameBuffer.getLatestFrame()
            ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "")

        return newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            ByteArrayInputStream(frame.second), frame.second.size.toLong()
        )
    }

    private fun serveThumbnail(uri: String): Response {
        val fileName = uri.removePrefix("/api/thumbnails/")
        if (fileName.contains("..") || fileName.contains("/")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid")
        }
        val service = ServiceManager.instance
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Service not running")
        val file = service.videoRecorder?.getVideoFile(fileName)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video not found")
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.frameAtTime
            retriever.release()
            if (bitmap == null) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "image/jpeg", "")
            }
            val thumbnail = android.graphics.Bitmap.createScaledBitmap(bitmap, 320, 240, true)
            val baos = java.io.ByteArrayOutputStream()
            thumbnail.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(baos.toByteArray()), baos.size().toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    /**
     * Diagnostic: Start/stop raw H.264 bitstream dump.
     * Usage:
     *   GET /api/diag/dump?action=start  — starts dump to cache dir
     *   GET /api/diag/dump?action=stop   — stops dump, returns file path
     *   The .264 file can be analyzed with: ffprobe -show_frames dump.264
     */
    private fun serveDiagDump(session: IHTTPSession): Response {
        val action = session.parms["action"] ?: ""
        val service = ServiceManager.instance
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json",
                gson.toJson(mapOf("error" to "Service not running")))

        val encoder = service.h264Encoder
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json",
                gson.toJson(mapOf("error" to "H264Encoder not initialized, wait for first frame")))

        return when (action) {
            "start" -> {
                val filePath = context.cacheDir.absolutePath + "/h264_dump.264"
                encoder.startDump(filePath)
                newFixedLengthResponse(Response.Status.OK, "application/json",
                    gson.toJson(mapOf("status" to "started", "path" to filePath)))
            }
            "stop" -> {
                encoder.stopDump()
                newFixedLengthResponse(Response.Status.OK, "application/json",
                    gson.toJson(mapOf("status" to "stopped")))
            }
            else -> {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("error" to "action must be 'start' or 'stop'")))
            }
        }
    }

    private fun serveCameraList(): Response {
        return try {
            val cameras = CameraUtils.enumerateCameras(context)
            val cameraList = cameras.map { camera ->
                mapOf(
                    "index" to camera.index,
                    "cameraId" to camera.cameraId,
                    "logicalCameraId" to camera.logicalCameraId,
                    "label" to camera.label
                )
            }
            val response = mapOf(
                "cameras" to cameraList,
                "currentCameraId" to AppSettings.getCameraId(context),
                "currentLogicalCameraId" to AppSettings.getLogicalCameraId(context)
            )
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Camera enumeration failed")
        }
    }

    private fun serveCameraSwitch(session: IHTTPSession): Response {
        return try {
            val cameraId = session.parms["cameraId"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "error" to "Missing cameraId"))
            )
            val logicalCameraId = session.parms["logicalCameraId"] ?: cameraId

            val cameras = CameraUtils.enumerateCameras(context)
            val match = cameras.find { it.cameraId == cameraId }
            if (match == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    gson.toJson(mapOf("success" to false, "error" to "Invalid cameraId: $cameraId"))
                )
            }

            AppSettings.setCameraIndex(context, match.index)
            AppSettings.setCameraId(context, cameraId)
            AppSettings.setLogicalCameraId(context, logicalCameraId)

            val service = ServiceManager.instance
            val switching = if (service != null) {
                val intent = Intent(context, CameraService::class.java).apply {
                    action = CameraService.ACTION_SWITCH_CAMERA
                }
                context.startService(intent)
                true
            } else {
                false
            }

            val result = mutableMapOf<String, Any>(
                "success" to true,
                "cameraId" to cameraId,
                "switching" to switching
            )
            if (!switching) {
                result["warning"] = "Service not running, settings saved"
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(result))
        } catch (e: Exception) {
            val errorResult = mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", gson.toJson(errorResult))
        }
    }

    private fun serveCameraPower(session: IHTTPSession): Response {
        val action = session.parms["action"]
        if (action != "on" && action != "off") {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(mapOf("success" to false, "error" to "Missing or invalid action (must be 'on' or 'off')"))
            )
        }

        val powerOn = action == "on"
        CameraService.cameraPoweredOn.set(powerOn)

        val service = ServiceManager.instance
        if (service != null) {
            val intent = Intent(context, CameraService::class.java).apply {
                setAction(CameraService.ACTION_CAMERA_POWER)
                putExtra("power_on", powerOn)
            }
            context.startService(intent)
        }

        val result = mapOf(
            "success" to true,
            "power" to powerOn
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(result))
    }


    fun startUdpDiscovery() {
        try {
            udpSocket = DatagramSocket(udpPort)
            udpSocket?.broadcast = true
            udpThread = Thread({ runUdpListener() }, "udp-discovery")
            udpThread?.isDaemon = true
            udpThread?.start()
            Log.d(TAG, "UDP discovery listener started on port $udpPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP discovery listener", e)
        }
    }

    fun stopUdpDiscovery() {
        udpThread?.interrupt()
        udpThread = null
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        Log.d(TAG, "UDP discovery listener stopped")
    }

    private fun runUdpListener() {
        val socket = udpSocket ?: return
        val buffer = ByteArray(1024)
        while (!Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (msg == "HOMECAM_DISCOVER") {
                    val ip = getLocalIpAddress()
                    val port = AppSettings.getWebPort(context)
                    val name = "HomeCam-" + deviceId.takeLast(6)
                    val rtspPort = AppSettings.getRtspPort(context)
                    val response = "HOMECAM_RESPONSE|" + name + "|" + ip + "|" + port + "|" + deviceId + "|" + rtspPort
                    val responseBytes = response.toByteArray(Charsets.UTF_8)
                    val responsePacket = DatagramPacket(
                        responseBytes, responseBytes.size,
                        packet.address, packet.port
                    )
                    socket.send(responsePacket)
                    Log.d(TAG, "UDP discovery responded to " + packet.address?.hostAddress)
                }
            } catch (e: java.net.SocketException) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "UDP socket error", e)
                }
                break
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "UDP listener error", e)
                }
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
