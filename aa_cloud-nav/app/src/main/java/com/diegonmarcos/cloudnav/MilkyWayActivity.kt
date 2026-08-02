package com.diegonmarcos.cloudnav

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

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
        webView.loadUrl("file:///android_asset/galaxy/galaxy_map.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
