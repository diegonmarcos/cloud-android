package com.diegonmarcos.cloudnav

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.cloudnav.maps.MapsLayerPrefs

/**
 * Real 3D terrain (tilt-rise mountains) via MapLibre GL JS in a WebView.
 * MapLibre Native (the app's main map renderer, [com.diegonmarcos.cloudnav.maps.MapsMapFragment])
 * has no terrain support — this is a separate, additive screen, not a replacement.
 *
 * Exaggeration is controlled from Configs > Layers ([MapsLayerPrefs],
 * native SharedPreferences source of truth): pushed into the page on load
 * via `cloudNavSetExaggeration(...)`, and the page's own in-view slider
 * writes back through the `AndroidBridge` JS interface below, so both
 * stay in sync instead of drifting into two separate settings.
 */
class TerrainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var layerPrefs: MapsLayerPrefs

    /** Exposed to terrain_map.html as `window.AndroidBridge`. Not private --
     *  WebView's addJavascriptInterface uses reflection to find the
     *  @JavascriptInterface methods, which has been unreliable against
     *  non-public classes on some Android versions. */
    inner class AndroidBridge {
        @JavascriptInterface
        fun setExaggeration(value: Int) {
            layerPrefs.terrainExaggeration = value
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layerPrefs = MapsLayerPrefs(this)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        val zoom = intent.getDoubleExtra(EXTRA_ZOOM, 13.0)
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!lat.isNaN() && !lon.isNaN()) {
                    view.evaluateJavascript("cloudNavRecenter($lat,$lon,$zoom)", null)
                }
                view.evaluateJavascript("cloudNavSetExaggeration(${layerPrefs.terrainExaggeration})", null)
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
