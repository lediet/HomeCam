package com.homecam.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_records")
data class VideoRecord(
    @PrimaryKey val fileName: String,
    val timestamp: Long,
    val eventType: String,
    val durationSec: Int,
    val fileSize: Long
)
