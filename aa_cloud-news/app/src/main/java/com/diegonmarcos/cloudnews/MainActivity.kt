package com.diegonmarcos.cloudnews

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.updater.Updater

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)
        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // P0 scaffold: no JS bridge yet — libs:news has data classes only, no
        // networking/store/sync. A NewsBridge (mirroring CalBridge) lands in P1
        // once the WebView UI needs to call back into Kotlin.
        webView.loadUrl("file:///android_asset/news.html")
        Updater.start(this)
    }
}
