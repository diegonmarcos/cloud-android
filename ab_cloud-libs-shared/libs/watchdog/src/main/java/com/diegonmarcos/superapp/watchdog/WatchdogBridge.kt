package com.diegonmarcos.superapp.watchdog

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The `AndroidWatchdog` JavascriptInterface — the page's only way off-device,
 * and it does not go off-device.
 *
 * Threading, the same shape cloud-ide's TerminalBridge uses:
 *   - @JavascriptInterface methods arrive on a WebView background thread.
 *   - ssh runs on [pool], so that thread is never the one waiting on a socket.
 *   - results go back through webView.post { evaluateJavascript(...) }, because
 *     evaluateJavascript must be called on the UI thread.
 *
 * JS contract:
 *   window.__wdScreen(reqId, ok, payload)   one screen, or the reason there is none
 *
 * The page asks for a grid and gets HTML back. It does not parse a snapshot,
 * lay anything out, or know what a cgroup is — every one of those would be a
 * second implementation of the panel, and the last one took a week to admit
 * was unfixable.
 */
class WatchdogBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val ssh: WatchdogSsh,
    private val backend: () -> String,
) {

    // Two: one carries keys down, one is free for start/stop. The frame
    // reader gets a thread of its own, so a blocking read never holds up
    // the next keystroke — that would make the panel feel dead under load.
    private val pool = Executors.newFixedThreadPool(2)

    /**
     * Fetch one screen at [cols]x[rows].
     *
     * Single-threaded on purpose: refreshes are serialised, so a slow env
     * cannot stack up connections, and a rotation mid-fetch cannot land two
     * screens out of order into the same view.
     */
    @JavascriptInterface
    fun screen(reqId: String, cols: Int, rows: Int) {
        pool.execute {
            val r = ssh.screen(backend(), cols, rows)
            reply(reqId, r.isSuccess, r.getOrElse { it.message ?: "unreachable" })
        }
    }

    // ── the live panel ────────────────────────────────────────────────────
    // One long-lived `tui --serve` per session. Every key the CLI binds works
    // through this and nothing here knows what any of them mean: `k`, `x`, a
    // digit, `:` — all of them are one line down a pipe, and the panel's own
    // on_key decides. A key table on this side would be a second one to keep
    // in step, and it would be wrong the first time a key was added.

    @Volatile private var panel: WatchdogSsh.Panel? = null
    @Volatile private var reader: Thread? = null

    /** Start (or restart) the panel at this device's grid. */
    @JavascriptInterface
    fun start(cols: Int, rows: Int) {
        pool.execute {
            stopPanel()
            ssh.open(backend(), cols, rows).fold(
                onSuccess = { p ->
                    panel = p
                    // Frames arrive on their own thread and are pushed at the
                    // page: the panel emits one per key AND one per tick, so
                    // the app cannot be the thing deciding when a screen is
                    // ready — it just paints what arrives.
                    reader = Thread {
                        while (true) {
                            val f = runCatching { p.readFrame() }.getOrNull() ?: break
                            push("frame", f)
                        }
                        push("closed", "")
                    }.apply { isDaemon = true; start() }
                },
                onFailure = { push("error", it.message ?: "unreachable") },
            )
        }
    }

    /** A keystroke, by name. "p", "1", "enter", "esc", "up", "f1". */
    @JavascriptInterface
    fun key(name: String) {
        pool.execute { runCatching { panel?.key(name) } }
    }

    /** Refresh the data without pressing anything — the frame's `a` loop. */
    @JavascriptInterface
    fun tick() {
        pool.execute { runCatching { panel?.tick() } }
    }

    /** Rotation or unfold: the panel redraws for the new grid. */
    @JavascriptInterface
    fun resize(cols: Int, rows: Int) {
        pool.execute { runCatching { panel?.resize(cols, rows) } }
    }

    @JavascriptInterface
    fun stop() {
        pool.execute { stopPanel() }
    }

    private fun stopPanel() {
        runCatching { panel?.close() }
        panel = null
        reader = null
    }

    companion object {
        const val PREFS = "watchdog"
        const val KEY_BACKEND = "backend"
    }

    private fun push(kind: String, payload: String) {
        val js = "window.__wdEvent(${JSONObject.quote(kind)}, ${JSONObject.quote(payload)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** The authorized_keys line for the setup screen. */
    @JavascriptInterface
    fun publicKey(): String = runCatching { ssh.publicKeyLine() }.getOrElse { "" }

    /**
     * Pin an env, or clear the pin. Persisted, because a phone that only has
     * Termux should not have to be told so on every launch.
     *
     * The panel is restarted rather than switched under the page: it is a
     * process on the far side, and pointing the app at a different machine
     * while a session is open would leave the old one running there.
     */
    @JavascriptInterface
    fun setBackend(key: String) {
        activity.getSharedPreferences(PREFS, 0).edit().putString(KEY_BACKEND, key).apply()
        pool.execute { stopPanel(); ssh.close() }
    }

    /** The env actually reached, which is not always the one asked for. */
    @JavascriptInterface
    fun activeBackend(): String = ssh.activeBackend ?: ""

    /** Which envs this build knows about, as JSON, for the backend picker. */
    @JavascriptInterface
    fun backends(): String {
        val o = JSONObject()
        ssh.backendKeys().forEach { o.put(it, ssh.label(it)) }
        return o.toString()
    }

    private fun reply(reqId: String, ok: Boolean, payload: String) {
        // Through JSON rather than string concatenation: the payload is a
        // screenful of HTML with quotes, newlines and box-drawing characters
        // in it, and hand-escaping that into a JS literal is a bug waiting for
        // the first machine whose hostname contains an apostrophe.
        val js = "window.__wdScreen(${JSONObject.quote(reqId)}, $ok, ${JSONObject.quote(payload)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
