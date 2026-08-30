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
 * slowly enough each time to look nearly right and never be.
 *
 * So it runs `my-watchdog-tui tui --serve` inside nix-on-droid and keeps it: keys
 * go down its stdin, frames come back off its stdout. So the app has every
 * command the CLI has and the exact same screen, and neither is a claim about
 * this code — the keys ARE Monitor::on_key and the screen IS the ratatui
 * buffer. Adding a key to the panel adds it here with no work.
 *
 * The grid is computed from the WebView's own measurements rather than
 * guessed: character width comes from measuring a monospace run in the page,
 * so a phone, that phone rotated, and a foldable opened each get a terminal
 * that fits instead of one scaled down to a texture.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var ssh: WatchdogSsh
    private lateinit var bridge: WatchdogBridge

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
        bridge = WatchdogBridge(this, web, ssh) { backend }
        web.addJavascriptInterface(bridge, "AndroidWatchdog")
        web.loadUrl("file:///android_asset/watchdog.html")
    }

    /**
     * Which env to talk to. A field for now, and the picker writes it later —
     * the bridge reads it through a lambda precisely so it is read fresh at
     * call time rather than captured once at construction.
     */
    private var backend: String = com.diegonmarcos.superapp.watchdog.BuildConfig.WATCHDOG_BACKEND_DEFAULT

    /**
     * The panel is a process on the other side, so leaving has to end it —
     * otherwise every launch strands a `tui --serve` on the phone and they
     * accumulate until something notices the fan.
     */
    override fun onDestroy() {
        bridge.stop()
        ssh.close()
        super.onDestroy()
    }
}
