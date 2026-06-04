package com.homecam.app.ui

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.homecam.app.R

class UserManualActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)

        val toolbar = findViewById<MaterialToolbar>(R.id.manual_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val webView = findViewById<WebView>(R.id.manual_webview)
        webView.webViewClient = WebViewClient()

        // Load from assets — the HTML is self-contained (inline CSS)
        try {
            val html = assets.open("web/help.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL("file:///android_asset/web/", html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            webView.loadDataWithBaseURL(null,
                "<html><body><h2>加载失败</h2><p>帮助文件未找到</p></body></html>",
                "text/html", "UTF-8", null)
        }
    }

    override fun onBackPressed() {
        finish()
    }
}