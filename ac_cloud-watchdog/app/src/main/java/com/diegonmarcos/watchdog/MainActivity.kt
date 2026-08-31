package com.diegonmarcos.watchdog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.updater.UpdateOverlayFragment
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
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
 * THE UI AND THE DATA ARE TWO THINGS
 * They used to be one, and that was the bug. Every pixel came from a
 * transcription of the panel's ratatui buffer, which meant the interface was a
 * property of having already reached a machine — so a refused connection left
 * nothing to draw and the app opened on an error message where a dashboard
 * should be.
 *
 * Now the interface ships. `my-watchdog-tui app-shell` renders the same
 * template the desktop report uses, against an empty machine, and that page is
 * an asset in this APK: it opens with no network, nothing installed on the
 * phone and nobody's sshd running. The machine is asked for numbers separately
 * and window.__wdRender swaps them in, so a refresh that does not land leaves
 * the last dashboard on screen instead of taking the app down with it.
 *
 * One template, not two: the phone and the desktop report cannot drift apart
 * on palette, box order or which tabs exist, because neither of them draws
 * anything this repo wrote.
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
        // The UI, shipped. It is the page `my-watchdog-tui app-shell` renders —
        // the same template as the desktop report, against an empty machine —
        // so it opens instantly, with no network and nothing installed on the
        // phone, and the drawer, the tabs and every panel frame are already
        // there before anything has been measured.
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                // Only once the page exists: __wdRender is defined by the
                // shell's own script, and calling it earlier is a no-op that
                // looks exactly like an unreachable machine.
                bridge.refresh()
            }
        }
        web.loadUrl("file:///android_asset/watchdog-app.html")

        // SELF-UPDATE, the same wiring every other constellation app has.
        // This app shipped without it, which is its own joke: the one that
        // tells you when a machine is out of date could only be updated by
        // fetching its APK by hand.
        Updater.start(this)
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    /**
     * Which env to PREFER. Read fresh on every call — that is why the bridge
     * takes a lambda rather than a value — so a pin written by the picker
     * takes effect on the next connection without rebuilding anything.
     *
     * A preference, not a destination: WatchdogSsh tries the others after it,
     * because which Linux is installed on a phone is not something this app
     * can know and Termux may be the only one there.
     */
    private val backend: String
        get() = getSharedPreferences(WatchdogBridge.PREFS, 0)
            .getString(WatchdogBridge.KEY_BACKEND, null)
            ?: com.diegonmarcos.superapp.watchdog.BuildConfig.WATCHDOG_BACKEND_DEFAULT

    /**
     * The panel is a process on the other side, so leaving has to end it —
     * otherwise every launch strands a `tui --serve` on the phone and they
     * accumulate until something notices the fan.
     */
    /**
     * The updater's progress, as an overlay over whatever is on screen.
     *
     * android.R.id.content rather than a container of our own: this activity
     * sets the WebView as its whole content view, and the overlay has to sit
     * ABOVE the dashboard rather than replace it — an update that hides the
     * machine you were watching is the wrong trade.
     */
    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        when (state) {
            is UpdateProgress.State.Idle ->
                frag?.let { supportFragmentManager.commit { remove(it) } }
            else -> {
                if (frag == null) {
                    supportFragmentManager.commit {
                        add(android.R.id.content, UpdateOverlayFragment.newInstance(), tag)
                    }
                } else {
                    (frag as? UpdateOverlayFragment)?.applyState(state)
                }
            }
        }
    }

    override fun onDestroy() {
        // Dropped with the rest: the listener holds this activity, and a
        // rotation would otherwise leave the old one reachable from a static.
        UpdateProgress.setListener(null)
        bridge.stop()
        ssh.close()
        super.onDestroy()
    }
}
