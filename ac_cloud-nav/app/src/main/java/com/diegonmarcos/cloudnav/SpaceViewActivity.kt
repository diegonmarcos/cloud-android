package com.diegonmarcos.cloudnav

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

/**
 * "Space View" -- renamed from the earlier "Constellations"/MilkyWayActivity
 * screen and refocused onto the Solar System (planets, moons, orbits) as
 * the primary navigable 3D content, with star dust and constellation
 * stars as toggleable backdrop layers. A three.js WebGL scene (bundled
 * locally, no network needed). See assets/space/CREDITS.md and
 * assets/space/space_view.html for the full technical writeup, data
 * provenance, and scale disclosures.
 *
 * Served via WebViewAssetLoader (https://appassets.androidplatform.net/...)
 * instead of loadUrl("file:///android_asset/...") -- required because
 * Chromium's fetch() API refuses file:// requests by CORS policy, and
 * this screen's data (constellation lines, planetary elements) loads via
 * fetch(). This is Google's own documented fix for that bug class.
 */
class SpaceViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/space/space_view.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
