package com.diegonmarcos.watchdog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.watchdog.WatchdogBridge

/**
 * A WebView showing the shipped dashboard, filled from envelopes the env
 * pushes into this app. Nothing here measures anything or reaches any host.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: WatchdogBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            setBackgroundColor(0xFF0B0E14.toInt())
        }
        setContentView(web)
        bridge = WatchdogBridge(this, web)
        web.addJavascriptInterface(bridge, "AndroidWatchdog")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) = bridge.renderLast()
        }
        web.loadUrl("file:///android_asset/watchdog-app.html")
    }

    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized) bridge.renderLast()
    }
}
