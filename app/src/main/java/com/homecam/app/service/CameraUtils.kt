package com.homecam.app.service

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

data class CameraInfo(
    val index: Int,
    val cameraId: String,
    val logicalCameraId: String,
    val label: String
)

object CameraUtils {
    private const val TAG = "CameraUtils"

    fun enumerateCameras(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val result = mutableListOf<CameraInfo>()
        val processedIds = mutableSetOf<String>()
        try {
            val knownIds = manager.cameraIdList.toMutableSet()
            val originalCameraIds = manager.cameraIdList.toSet()

            val logicalRearId = manager.cameraIdList.firstOrNull { id ->
                try { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                catch (e: Exception) { false }
            } ?: "0"
            Log.d(TAG, "Logical rear camera ID: $logicalRearId")

            for (probeId in 0..5) {
                val pid = probeId.toString()
                if (pid !in knownIds) {
                    try {
                        manager.getCameraCharacteristics(pid)
                        knownIds.add(pid)
                        Log.d(TAG, "Probed extra camera ID: $pid")
                    } catch (_: Exception) { }
                }
            }

            for (id in knownIds) {
                if (id in processedIds) continue
                processedIds.add(id)
                val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { null }
                if (chars == null) {
                    result.add(CameraInfo(result.size, id, id, "摄像头 ${result.size} (未知)"))
                    continue
                }

                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val lensDesc = getLensTypeLabel(chars)
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                Log.d(TAG, "Camera $id: facing=$facing, focalLengths=${focalLengths?.joinToString()}, lensType=$lensDesc")


                val actualLogicalId = if (id in originalCameraIds || facing != CameraCharacteristics.LENS_FACING_BACK) id else logicalRearId
                val cameraLabel = "摄像头 ${result.size} (${getFacingLabel(facing)}$lensDesc)"
                result.add(CameraInfo(result.size, id, actualLogicalId, cameraLabel))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        val isLogical = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                        if (isLogical) {
                            val rawIds = chars.get(android.hardware.camera2.CameraCharacteristics.Key("android.physicalCameraIds", Set::class.java))
                            @Suppress("UNCHECKED_CAST")
                            val physicalIds = rawIds as? Set<String>
                            Log.d(TAG, "Camera $id is logical multi-camera, physicalIds=$physicalIds")
                            if (physicalIds != null) {
                                for (pid in physicalIds) {
                                    if (pid in processedIds) continue
                                    processedIds.add(pid)
                                    val pChars = try { manager.getCameraCharacteristics(pid) } catch (e: Exception) { null }
                                    val pLens = getLensTypeLabel(pChars)
                                    val facingLabel = getFacingLabel(chars.get(CameraCharacteristics.LENS_FACING))
                                    val pLabel = "摄像头 ${result.size} ($facingLabel$pLens)"
                                    result.add(CameraInfo(result.size, pid, id, pLabel))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Logical multi-camera enumeration failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "enumerateCameras failed", e)
        }
        Log.d(TAG, "enumerateCameras: found ${result.size} cameras: $result")
        return result
    }

    fun getFacingLabel(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> "后置"
        CameraCharacteristics.LENS_FACING_FRONT -> "前置"
        else -> "外接"
    }

    fun getLensTypeLabel(chars: CameraCharacteristics?): String {
        if (chars == null) return ""
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return ""
        if (focalLengths.isEmpty()) return ""
        return when {
            focalLengths[0] < 2.5f -> " 广角"
            focalLengths[0] > 12.0f -> " 长焦"
            else -> ""
        }
    }
}
