package com.homecam.app.service

object ServiceManager {
    @Volatile
    var instance: CameraService? = null
}
