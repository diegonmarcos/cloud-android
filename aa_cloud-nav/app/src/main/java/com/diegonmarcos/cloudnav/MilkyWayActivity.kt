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
 * Real, free-fly-navigable 3D star field: a three.js WebGL scene (bundled
 * locally, no network needed) rendering 119,614 real stars from the HYG
 * v4.4 catalog at their actual parallax-derived 3D positions, plus a
 * constellation-line overlay, a procedural galactic-plane "star dust" band,
 * and a real-orbital-mechanics solar-system mode. See
 * assets/galaxy/CREDITS.md and assets/galaxy/galaxy_map.html for the full
 * technical writeup and data provenance.
 *
 * This REPLACES an earlier version of this screen that embedded AAS
 * WorldWide Telescope's web app — WWT's default view is a flat sky
 * panorama, not actual navigable 3D, which is specifically what was asked
 * for here; the embed is gone, not just unused.
 *
 * Served via WebViewAssetLoader (https://appassets.androidplatform.net/...)
 * instead of loadUrl("file:///android_asset/...") -- the real, reported bug
 * here was a full black screen, root-caused to Chromium's fetch() API
 * refusing file:// requests by CORS policy (script/link tags aren't
 * affected, which is why terrain_map.html's <script src> loading never hit
 * this, but galaxy_map.html's fetch() calls for the star/constellation/
 * planet data all silently failed). This is Google's own documented fix
 * for exactly this class of bug, not a workaround.
 */
class MilkyWayActivity : AppCompatActivity() {

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
        webView.loadUrl("https://appassets.androidplatform.net/assets/galaxy/galaxy_map.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
