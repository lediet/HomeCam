package com.homecam.app

import android.app.Application
import android.util.Log
import com.homecam.app.data.VideoDatabase

class HomeCamApp : Application() {

    companion object {
        private const val TAG = "HomeCam"
    }

    val database: VideoDatabase by lazy { VideoDatabase.getInstance(this) }

    override fun onCreate() {
        Log.d(TAG, "HomeCamApp.onCreate() start")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===", throwable)
        }
        super.onCreate()
        Log.d(TAG, "HomeCamApp.onCreate() done")
    }
}
