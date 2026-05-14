package com.homecam.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VideoDao {
    @Insert
    suspend fun insert(record: VideoRecord)

    @Delete
    suspend fun delete(record: VideoRecord)

    @Query("SELECT * FROM video_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<VideoRecord>

    @Query("SELECT * FROM video_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<VideoRecord>

    @Query("SELECT * FROM video_records ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldest(): VideoRecord?

    @Query("SELECT COUNT(*) FROM video_records")
    suspend fun getCount(): Int

    @Query("SELECT SUM(fileSize) FROM video_records")
    suspend fun getTotalSize(): Long?

    @Query("DELETE FROM video_records WHERE fileName = :fileName")
    suspend fun deleteByFileName(fileName: String)
}
