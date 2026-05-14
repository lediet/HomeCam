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

    fun getFps(context: Context): Int {
        val value = prefs(context).getString("fps", "15") ?: "15"
        return value.toIntOrNull() ?: 15
    }

    fun getWebPort(context: Context): Int {
        val value = prefs(context).getString("web_port", "8080") ?: "8080"
        return value.toIntOrNull() ?: 8080
    }

    fun isMotionDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("motion_detection", true)

    fun isCryDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("cry_detection", false)

    fun isDangerDetectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean("danger_detection", false)

    fun getSaveDurationSec(context: Context): Int {
        return prefs(context).getInt("save_duration", 3)
    }

    fun getMaxVideoCount(context: Context): Int {
        return prefs(context).getInt("max_video_count", 50)
    }

    fun getMaxStorageMb(context: Context): Int {
        return prefs(context).getInt("max_storage_mb", 200)
    }

    fun getDetectionIntervalFrames(context: Context): Int {
        return if (getFps(context) >= 30) 5 else 3
    }
}
