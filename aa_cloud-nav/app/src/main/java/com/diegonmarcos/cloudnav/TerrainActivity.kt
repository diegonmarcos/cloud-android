package com.diegonmarcos.cloudnav

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * Real 3D terrain (tilt-rise mountains) via MapLibre GL JS in a WebView.
 * MapLibre Native (the app's main map renderer, [com.diegonmarcos.cloudnav.maps.MapsMapFragment])
 * has no terrain support — this is a separate, additive screen, not a replacement.
 */
class TerrainActivity : AppCompatActivity() {

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
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        val zoom = intent.getDoubleExtra(EXTRA_ZOOM, 13.0)
        if (!lat.isNaN() && !lon.isNaN()) {
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("cloudNavRecenter($lat,$lon,$zoom)", null)
                }
            }
        }
        webView.loadUrl("file:///android_asset/terrain_map.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ZOOM = "zoom"
    }
}
