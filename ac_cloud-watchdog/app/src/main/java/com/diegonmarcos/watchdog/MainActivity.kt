package com.diegonmarcos.watchdog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.watchdog.WatchdogBridge
import com.diegonmarcos.superapp.watchdog.WatchdogSsh

/**
 * The whole app: a WebView, a bridge, and no opinion about what a dashboard
 * looks like.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO
 * It does not parse a snapshot, lay out boxes, choose colours or know what a
 * cgroup is. The panel already draws all of that, and every attempt to draw it
 * a second time somewhere else diverged on frames, alignment and palette —
 * slowly enough each time to look nearly right and never be. So the app asks
 * `my-konsole-dash tui <cols> <rows>` inside nix-on-droid for a screen at the
 * grid THIS device has, and puts the answer on screen.
 *
 * The grid is computed from the WebView's own measurements rather than
 * guessed: character width comes from measuring a monospace run in the page,
 * so a phone, that phone rotated, and a foldable opened each get a terminal
 * that fits instead of one scaled down to a texture.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var ssh: WatchdogSsh

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            // The page is a bundled asset and the only thing it fetches is
            // through the bridge, so nothing here needs file or network access
            // of its own. Turning them off means a page that somehow gained a
            // remote <script> still could not read the app's storage.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            // The transcript is a fixed grid: letting the WebView reflow it
            // would break every column at once, which is the exact failure the
            // whole transcription approach exists to end.
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            setBackgroundColor(0xFF0B0E14.toInt())
        }
        setContentView(web)

        ssh = WatchdogSsh(this)
        web.addJavascriptInterface(
            WatchdogBridge(this, web, ssh) { backend },
            "AndroidWatchdog",
        )
        web.loadUrl("file:///android_asset/watchdog.html")
    }

    /**
     * Which env to talk to. A field for now, and the picker writes it later —
     * the bridge reads it through a lambda precisely so it is read fresh at
     * call time rather than captured once at construction.
     */
    private var backend: String = com.diegonmarcos.superapp.watchdog.BuildConfig.WATCHDOG_BACKEND_DEFAULT

    override fun onDestroy() {
        ssh.close()
        super.onDestroy()
    }
}
