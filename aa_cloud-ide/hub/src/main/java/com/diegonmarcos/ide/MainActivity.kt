package com.diegonmarcos.ide

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * The Cloud-IDE home — a thin WebView launcher for the my-konsole frontend
 * (assets/frontend/, seeded by build.sh::step_bundle_frontend). The frontend
 * talks to the local my-konsole engine over ws://127.0.0.1:7333 by default
 * (transport.js), so the WebView needs no JS injection for v1 — just the
 * settings below plus the cleartext-to-localhost network security config.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true   // CRITICAL — frontend session-restore uses localStorage
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true   // file:// page opening a ws:// connection
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()       // keep navigation in-app
            webChromeClient = WebChromeClient()   // console logging
            loadUrl("file:///android_asset/frontend/index.html")
        }

        setContentView(webView)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
