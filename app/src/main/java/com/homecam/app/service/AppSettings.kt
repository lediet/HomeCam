package com.homecam.app.service

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object AppSettings {
    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun getScaleFactor(context: Context): Float {
        val value = prefs(context).getString("scale_factor", "0.75") ?: "0.75"
        return value.toFloatOrNull()?.coerceIn(0.1f, 1.0f) ?: 0.75f
    }

    fun getCameraIndex(context: Context): Int {
        return prefs(context).getInt("camera_index", 0)
    }

    fun setCameraIndex(context: Context, index: Int) {
        prefs(context).edit().putInt("camera_index", maxOf(0, index)).apply()
    }

    fun getCameraId(context: Context): String {
        return prefs(context).getString("camera2_id", "0") ?: "0"
    }

    fun setCameraId(context: Context, cameraId: String) {
        prefs(context).edit().putString("camera2_id", cameraId).apply()
    }

    fun getLogicalCameraId(context: Context): String {
        return prefs(context).getString("camera2_logical_id", "0") ?: "0"
    }

    fun setLogicalCameraId(context: Context, cameraId: String) {
        prefs(context).edit().putString("camera2_logical_id", cameraId).apply()
    }

    fun getFps(context: Context): Int {
        val value = prefs(context).getString("fps", "15") ?: "15"
        return value.toIntOrNull() ?: 15
    }

    fun getWebPort(context: Context): Int {
        val value = prefs(context).getString("web_port", "8080") ?: "8080"
        return value.toIntOrNull() ?: 8080
    }

    fun getRtspPort(context: Context): Int {
        val value = prefs(context).getString("rtsp_port", "8554") ?: "8554"
        return value.toIntOrNull() ?: 8554
    }

    fun isRtspEnabled(context: Context): Boolean =
        prefs(context).getBoolean("rtsp_enabled", true)

    fun isMjpgEnabled(context: Context): Boolean =
        prefs(context).getBoolean("mjpg_enabled", true)

    fun isMotionDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("motion_detection", true)

    fun isCryDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("cry_detection", false)

    fun isSleepDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("sleep_detection", false)

    fun isFallDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("fall_detection", false)

    fun isPhoneDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("phone_detection", false)

    fun getInferenceBackend(context: Context): String =
        prefs(context).getString("inference_backend", "cpu") ?: "cpu"

    fun getDetectionTarget(context: Context): String =
        prefs(context).getString("detection_target", "person") ?: "person"

    fun getSaveDurationSec(context: Context): Int {
        val v = prefs(context).all["save_duration"]
        return when (v) {
            is Int -> v
            is String -> v.toIntOrNull() ?: 3
            else -> 3
        }
    }

    fun getMaxVideoCount(context: Context): Int {
        val v = prefs(context).all["max_video_count"]
        return when (v) {
            is Int -> v
            is String -> v.toIntOrNull() ?: 50
            else -> 50
        }
    }

    fun getMaxStorageMb(context: Context): Int {
        return prefs(context).getInt("max_storage_mb", 200)
    }

    fun getDetectionIntervalFrames(context: Context): Int {
        val value = prefs(context).getString("detection_interval", "3") ?: "3"
        return value.toIntOrNull()?.coerceIn(1, 10) ?: 3
    }
}
