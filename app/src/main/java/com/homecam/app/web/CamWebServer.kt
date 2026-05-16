package com.homecam.app.web

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.homecam.app.HomeCamApp
import com.homecam.app.service.AppSettings
import com.homecam.app.service.CameraService
import com.homecam.app.service.CameraUtils
import com.homecam.app.service.ServiceManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.FileInputStream

class CamWebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val gson = Gson()

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
            uri == "/api/videos" -> serveVideoList()
            uri.startsWith("/videos/") -> serveVideoFile(uri)
            uri == "/api/frame.jpg" -> serveFrameJpeg()
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
                "danger" to AppSettings.isDangerDetectionEnabled(context)
            ),
            "latest_event" to CameraService.latestEventType,
            "latest_event_time" to CameraService.latestEventTime,
            "current_camera_id" to AppSettings.getCameraId(context),
            "current_logical_camera_id" to AppSettings.getLogicalCameraId(context),
            "camera_powered" to CameraService.cameraPoweredOn.get()
        )

        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
    }

    private fun serveEvents(): Response {
        return try {
            val app = context.applicationContext as HomeCamApp
            val events = runBlocking {
                app.database.videoDao().getRecent(20)
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(events))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    private fun serveVideoList(): Response {
        return try {
            val app = context.applicationContext as HomeCamApp
            val videos = runBlocking {
                app.database.videoDao().getAll()
            }
            val videoList = videos.map { record ->
                mapOf(
                    "fileName" to record.fileName,
                    "timestamp" to record.timestamp,
                    "eventType" to record.eventType,
                    "durationSec" to record.durationSec,
                    "fileSize" to record.fileSize,
                    "url" to "/videos/${record.fileName}"
                )
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(videoList))
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
