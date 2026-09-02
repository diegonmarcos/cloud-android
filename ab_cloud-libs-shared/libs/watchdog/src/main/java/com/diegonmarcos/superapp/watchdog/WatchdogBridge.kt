package com.diegonmarcos.superapp.watchdog

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * The `AndroidWatchdog` object the page sees. It knows two verbs: "I want
 * this machine" and "here is what arrived" — and nothing about ssh, keys,
 * terminals or sampling, because none of that happens in this process.
 *
 *   page → refresh(alias)   writes the want; the env's loop picks it up
 *   env  → content write    lands in [BridgeProvider], which calls [arrived]
 *   here → __wdRender(json) the page repaints from the envelope
 *
 * Everything else the old interface had (start, key, tick, resize, backends,
 * setBackend, publicKey) is kept as a no-op so a page built against it never
 * throws on a missing method.
 */
class WatchdogBridge(private val activity: Activity, private val webView: WebView) {

    init {
        Store.onArrive = { alias -> render(alias) }
    }

    /** Whatever is on disk for the wanted machine, now — the app opens on the
     *  last envelope rather than on nothing. */
    fun renderLast() {
        val want = Store.wanted(activity)
        if (!render(want) && want != "local") render("local")
        event("wait", "asking the env for $want — ${Store.log(activity).lastOrNull() ?: "no contact yet"}")
    }

    private fun render(alias: String): Boolean {
        val f = Store.file(activity, alias)
        if (!f.isFile || f.length() < 2) return false
        val js = "window.__wdRender(${JSONObject.quote(f.readText())})"
        webView.post { webView.evaluateJavascript(js, null) }
        event("fresh", "$alias ${f.length() / 1024} KB")
        return true
    }

    @JavascriptInterface fun refresh() = refresh("")

    @JavascriptInterface
    fun refresh(alias: String) {
        val a = alias.trim().ifEmpty { "local" }
        Store.want(activity, a)
        // Show what we already have for it while the env measures again.
        if (!render(a)) event("wait", "asked the env for $a")
    }

    /** The app's own log, for the page's diagnostics — the same lines
     *  `content query …/log` returns to the env. */
    @JavascriptInterface fun log(): String = Store.log(activity).joinToString("\n")

    @JavascriptInterface fun start(cols: Int, rows: Int) {}
    @JavascriptInterface fun key(name: String) {}
    @JavascriptInterface fun tick() {}
    @JavascriptInterface fun resize(cols: Int, rows: Int) {}
    @JavascriptInterface fun stop() {}
    @JavascriptInterface fun publicKey(): String = ""
    @JavascriptInterface fun setBackend(key: String) {}
    @JavascriptInterface fun activeBackend(): String = "nix-on-droid"
    @JavascriptInterface fun backends(): String = """{"nix-on-droid":"Nix-on-Droid"}"""

    private fun event(kind: String, payload: String) {
        val js = "window.__wdEvent && window.__wdEvent(${JSONObject.quote(kind)}, ${JSONObject.quote(payload)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
