package com.diegonmarcos.cloudnav

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * 3D navigable Milky Way / solar system explorer, embedding the AAS
 * WorldWide Telescope research web app (MIT-licensed, web.wwtassets.org) in
 * a WebView. This is NOT a hand-rolled 3D engine — WWT already provides,
 * with its own on-screen UI, everything this screen was asked for: a
 * star/Milky Way background layer, toggleable constellation figures/
 * boundaries/labels, a real solar-system view mode with accurate planet
 * ephemeris, and time controls (pause = static, resume = dynamic motion).
 * See CREDITS.md for attribution. Requires network for both the app shell
 * and its imagery, same as every other online-only screen in this app.
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
        webView.loadUrl("file:///android_asset/milkyway_map.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
