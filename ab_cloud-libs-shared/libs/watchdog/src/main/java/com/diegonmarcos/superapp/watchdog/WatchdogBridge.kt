package com.diegonmarcos.superapp.watchdog

import android.app.Activity
import android.util.Log
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

    /** The panel this app runs itself — the default, and the reason the UI
     *  no longer depends on a phone having a terminal installed. */
    private val local = WatchdogLocal(activity)

    /** Our Termux, bound directly. The replacement for ssh-to-loopback. */
    private val terminal = WatchdogTerminal(activity)

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

    @Volatile private var panel: WatchdogPanel? = null
    @Volatile private var reader: Thread? = null

    /** Where the running panel actually is, for the status bar. */
    @Volatile private var where: String = ""

    /**
     * Start (or restart) the panel at this device's grid.
     *
     * LOCAL FIRST. The app carries the panel binary, so the phone's own screen
     * needs no terminal, no sshd and no key on the device — it opens and it is
     * there. ssh used to be the only way to reach a panel, which meant a
     * refused connection left the app with nothing to draw and it opened on an
     * error message instead of a UI.
     *
     * ssh is still the way to a panel running as a different uid or on another
     * machine, so a pinned backend skips local entirely and a device this build
     * has no binary for falls through to it. Both ends speak the same protocol
     * to the same binary; only the pipe differs.
     */
    @JavascriptInterface
    fun start(cols: Int, rows: Int) {
        pool.execute {
            stopPanel()
            // "local" is a pin TO local, not away from it — picking this
            // device in the env picker must not send us down the ssh path.
            val pin = activity.getSharedPreferences(PREFS, 0).getString(KEY_BACKEND, null)
            val pinned = pin != null && pin != "local"
            val opened: Result<WatchdogPanel> = if (!pinned && local.available()) {
                where = "local"
                local.open(cols, rows).recoverCatching {
                    // A binary that will not start is worth saying out loud,
                    // but not worth being stuck on when ssh may still work.
                    where = backend()
                    ssh.open(backend(), cols, rows).getOrThrow()
                }
            } else {
                where = backend()
                ssh.open(backend(), cols, rows)
            }
            opened.fold(
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

    /**
     * Ask the machine for a snapshot and hand it to the page.
     *
     * The UI is already on screen when this runs — it shipped in the APK — so
     * this only ever fills it in. That is the whole point of the split: the
     * app opening and the machine answering are no longer the same event, and
     * the second one failing is a stale dashboard rather than no dashboard.
     */
    @JavascriptInterface
    fun refresh() = refresh("")

    /**
     * Measure a chosen machine.
     *
     * `alias` is the ssh-config Host of a mesh peer, or "" for this env itself.
     * The page's machine picker passes one; without this overload it was
     * DROPPED — a `@JavascriptInterface` is matched by arity, so `refresh(x)`
     * from JS found no method and the tap did nothing, while the app only ever
     * ran `snapshot` with no argument and so only ever measured the phone.
     * That is why no VM ever returned data.
     *
     * The env does the hop, because the phone reaches exactly one host and
     * every other machine is behind an ssh it makes. `snapshot <alias>` is how
     * the panel's own mesh already asks, with no terminal around it.
     */
    @JavascriptInterface
    fun refresh(alias: String) {
        val peer = alias.trim().takeIf { it.isNotEmpty() }
        pool.execute {
            // The terminal first. Binding to our own Termux has ONE thing that
            // can be wrong — whether it is installed — where ssh to loopback
            // had four, and reported all of them as the same ECONNREFUSED.
            // ssh stays behind it because it is the only way to another
            // machine, which is what it was always for.
            val panelBin = if (terminal.available()) terminal.toolPath(BIN_PANEL) else null
            val viaTerminal = if (panelBin != null) {
                // The sampler first. The panel only RENDERS a snapshot, so
                // asking for one before anything is publishing gets "the
                // sampler did not publish" on a phone where everything else
                // is right.
                terminal.toolPath(BIN_DAEMON)?.let { terminal.ensureDaemon(it) }
                // `snapshot <alias>` when a peer was picked: the env runs the
                // ssh hop to it, exactly as the ssh path below does.
                val args = if (peer != null) arrayOf("snapshot", peer) else arrayOf("snapshot")
                terminal.exec(panelBin, args, timeoutMs = 20_000)
                    .onFailure { Log.w(WatchdogTerminal.TAG, "terminal snapshot: ${it.message}") }
                    .getOrNull()
            } else {
                Log.w(WatchdogTerminal.TAG, "terminal: ${WatchdogTerminal.TERMUX_PKG} did not answer")
                null
            }

            if (viaTerminal != null) {
                val js = "window.__wdRender(${JSONObject.quote(viaTerminal)})"
                webView.post { webView.evaluateJavascript(js, null) }
                push("fresh", "terminal")
                return@execute
            }

            ssh.snapshot(backend(), peer).fold(
                onSuccess = { json ->
                    val js = "window.__wdRender(${JSONObject.quote(json)})"
                    webView.post { webView.evaluateJavascript(js, null) }
                    push("fresh", ssh.activeBackend ?: "")
                },
                onFailure = {
                    // Named here because the page cannot say it: the shipped
                    // shell defines __wdRender and nothing else, so an event
                    // pushed at it lands on an undefined function and the
                    // only symptom left is a dashboard that never fills in.
                    Log.w(WatchdogTerminal.TAG, "ssh snapshot (${backend()}${peer?.let { "→$it" } ?: ""}): ${it.message}")
                    push("stale", it.message ?: "unreachable")
                },
            )
        }
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
        pool.execute {
            stopPanel()
            // Unbound with the rest: a service binding outlives the activity
            // and keeps Termux alive for an app that has stopped looking.
            terminal.close()
        }
    }

    private fun stopPanel() {
        runCatching { panel?.close() }
        panel = null
        reader = null
    }

    companion object {
        const val PREFS = "watchdog"
        const val KEY_BACKEND = "backend"

        /** Resolved against the terminal's own toolsDir(), never a path we assume. */
        const val BIN_PANEL = "libmywatchdogtui.so"

        /** The sampler beside it — the panel draws what THIS publishes. */
        const val BIN_DAEMON = "libmywatchdog.so"
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
    fun activeBackend(): String = if (where == "local") "local" else (ssh.activeBackend ?: "")

    /** Which envs this build knows about, as JSON, for the backend picker. */
    @JavascriptInterface
    fun backends(): String {
        val o = JSONObject()
        // First, and only when it can actually run: this is the one entry that
        // needs nothing set up on the phone.
        if (local.available()) o.put("local", "This device")
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
