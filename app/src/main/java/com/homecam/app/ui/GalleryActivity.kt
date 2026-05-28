package com.homecam.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.homecam.app.HomeCamApp
import com.homecam.app.R
import com.homecam.app.data.VideoRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"
    }

    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        container = findViewById(R.id.gallery_container)
        emptyView = findViewById(R.id.gallery_empty)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.gallery_toolbar)
            .setNavigationOnClickListener { finish() }

        loadVideos()
    }

    private fun loadVideos() {
        Thread({
            try {
                val app = applicationContext as HomeCamApp
                val dbRecords = kotlinx.coroutines.runBlocking {
                    app.database.videoDao().getAll()
                }
                val recordMap = dbRecords.associateBy { it.fileName }

                val dir = File(this.getExternalFilesDir(null), "HomeCam")
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                if (files.isEmpty()) {
                    mainHandler.post { emptyView.visibility = View.VISIBLE }
                    return@Thread
                }

                mainHandler.post { emptyView.visibility = View.GONE }

                for (file in files) {
                    val record = recordMap[file.name]
                    val card = createVideoCard(file, record)
                    mainHandler.post { container.addView(card) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadVideos failed", e)
                mainHandler.post {
                    Toast.makeText(this, "加载视频列表失败", Toast.LENGTH_LONG).show()
                }
            }
        }, "gallery-loader").start()
    }

    private fun createVideoCard(file: File, record: VideoRecord?): View {
        val density = resources.displayMetrics.density

        // Card container
        val card = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * density).toInt()) }
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setBackgroundResource(R.drawable.card_background)
            elevation = 2f * density
        }

        // Thumbnail
        val thumbSize = (120 * density).toInt()
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(thumbSize, (68 * density).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 0, (8 * density).toInt(), 0)
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        card.addView(thumb)

        // Info column
        val infoCol = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            orientation = LinearLayout.VERTICAL
        }

        val modTime = file.lastModified()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(modTime))
        val sizeMB = "%.1f MB".format(file.length() / (1024f * 1024f))

        // Use DB record info if available, otherwise parse from filename
        val eventInfo = if (record != null) {
            val base = parseEventType(file.name)
            if (record.eventLabel.isNotEmpty()) "${base} (${record.eventLabel})" else base
        } else {
            parseEventType(file.name)
        }

        TextView(this).apply {
            text = dateStr
            textSize = 14f
            setTextColor(0xff333333.toInt())
        }.let { infoCol.addView(it) }

        TextView(this).apply {
            text = eventInfo
            textSize = 12f
            setTextColor(0xff888888.toInt())
        }.let { infoCol.addView(it) }

        TextView(this).apply {
            text = sizeMB
            textSize = 12f
            setTextColor(0xff888888.toInt())
        }.let { infoCol.addView(it) }

        card.addView(infoCol)

        // Play button
        val playBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            )
            gravity = android.view.Gravity.CENTER
            text = "\u25B6"
            textSize = 20f
            setTextColor(0xffe94560.toInt())
        }
        card.addView(playBtn)

        // Click to play
        card.setOnClickListener { playVideo(file) }

        // Load thumbnail in background
        loadThumbnail(file, thumb)

        return card
    }

    private fun loadThumbnail(file: File, imageView: ImageView) {
        Thread({
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val bitmap = retriever.frameAtTime
                retriever.release()
                if (bitmap != null) {
                    val thumb = Bitmap.createScaledBitmap(bitmap, 240, 136, true)
                    mainHandler.post { imageView.setImageBitmap(thumb) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail failed: ${file.name}", e)
            }
        }, "thumb-${file.name}").start()
    }

    private fun playVideo(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "playVideo failed", e)
            Toast.makeText(this, "无法播放此视频", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseEventType(fileName: String): String {
        return when {
            fileName.contains("_MOT_") -> "\uD83D\uDC64 人物移动"
            fileName.contains("_CRY_") -> "\uD83D\uDD0A 婴儿哭声"
            fileName.contains("_SLEEP_") -> "\uD83D\uDCA4 宝宝睡着了"
            fileName.contains("_WAKE_") -> "\uD83D\uDE34 宝宝睡醒了"
            fileName.contains("_ENTER_") -> "\uD83D\uDEB6 有人进入"
            fileName.contains("_LEAVE_") -> "\uD83D\uDEAA 有人离开"
            fileName.contains("_FALL_") -> "\uD83D\uDE35 有人摔倒"
            fileName.contains("_PHONE_") -> "\uD83D\uDCF1 玩手机"
            else -> "\uD83D\uDCC1 录像"
        }
    }
}
