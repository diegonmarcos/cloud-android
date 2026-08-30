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

    private val pool = Executors.newSingleThreadExecutor()

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

    /** The authorized_keys line for the setup screen. */
    @JavascriptInterface
    fun publicKey(): String = runCatching { ssh.publicKeyLine() }.getOrElse { "" }

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
