package com.diegonmarcos.ide

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * JavascriptInterface named "AndroidTerm" — bridges the my-konsole frontend's
 * `window.Transport` shim to [SshBackend].
 *
 * Threading model:
 *   - All @JavascriptInterface methods are called from a background WebView thread.
 *   - SSH work runs on [executor] (separate pool) so the WebView thread never blocks.
 *   - Callbacks to JS use [webView.post { webView.evaluateJavascript(...) }] —
 *     evaluateJavascript must be called on the UI thread; webView.post() delivers there.
 *
 * JS contract (what the Transport shim expects):
 *   - window.__aptyData(id, dataString)   — streamed pty output
 *   - window.__aptyExit(id)               — pty channel closed
 *   - window.__afsResult(rid, ok, json)   — fs call resolved/rejected
 *
 * @param backendKey lambda so the active backend is read fresh at call time
 *   (the user may change it in Configs while a session is open).
 */
class TerminalBridge(
    private val activity:   Activity,
    private val webView:    WebView,
    private val ssh:        SshBackend,
    private val backendKey: () -> String,
) {

    private val executor = Executors.newCachedThreadPool()

    /** Open the native Configs screen (terminal backend switcher, connection
     *  editor + test, SSH key, setup steps) — the frontend "Configs" button
     *  calls this so everything lives in one scrollable place. */
    @JavascriptInterface
    fun openConfigs() {
        activity.runOnUiThread {
            activity.startActivity(android.content.Intent(activity, ConfigsActivity::class.java))
        }
    }

    /** Open the native browser overlay for [url] — bypasses X-Frame-Options. */
    @JavascriptInterface
    fun browserOpen(url: String) {
        activity.runOnUiThread { (activity as? MainActivity)?.openBrowser(url) }
    }

    // ── PTY ───────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun ptyStart(id: String, cols: Int, rows: Int, cwd: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                ssh.openShell(
                    target  = target,
                    id      = id,
                    cols    = cols,
                    rows    = rows,
                    cwd     = cwd,
                    onData  = { data -> emitRaw("window.__aptyData(${q(id)},${q(data)})") },
                    onExit  = { emitRaw("window.__aptyExit(${q(id)})") },
                )
            } catch (e: Exception) {
                // Surface the error inline in the terminal — red ANSI error line.
                val msg = "\r\n[31m⚠ SSH to ${target.label} " +
                    "(${target.host}:${target.port}) failed: ${e.message}[0m\r\n"
                emitRaw("window.__aptyData(${q(id)},${q(msg)})")
            }
        }
    }

    @JavascriptInterface
    fun ptyWrite(id: String, data: String) {
        executor.submit { ssh.write(id, data) }
    }

    @JavascriptInterface
    fun ptyResize(id: String, cols: Int, rows: Int) {
        executor.submit { ssh.resize(id, cols, rows) }
    }

    @JavascriptInterface
    fun ptyKill(id: String) {
        executor.submit { ssh.kill(id) }
    }

    // ── File system ───────────────────────────────────────────────────────────

    @JavascriptInterface
    fun listDir(rid: Int, path: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                val entries = ssh.listDir(target, path)
                val arr = JSONArray()
                entries.forEach { (name, isDir) ->
                    arr.put(JSONObject().apply {
                        put("name",   name)
                        put("is_dir", isDir)
                    })
                }
                // Shim does JSON.parse on the json arg → pass the serialised array.
                emitRaw("window.__afsResult($rid,true,${JSONObject.quote(arr.toString())})")
            } catch (e: Exception) {
                emitRaw("window.__afsResult($rid,false,${q(e.message ?: "listDir failed")})")
            }
        }
    }

    @JavascriptInterface
    fun readFile(rid: Int, path: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                val text = ssh.readFile(target, path)
                // Shim does JSON.parse(j) → must pass JSONObject.quote(text) so
                // JSON.parse yields the raw string (not a nested object).
                emitRaw("window.__afsResult($rid,true,${JSONObject.quote(text)})")
            } catch (e: Exception) {
                emitRaw("window.__afsResult($rid,false,${q(e.message ?: "readFile failed")})")
            }
        }
    }

    @JavascriptInterface
    fun writeFile(rid: Int, path: String, content: String) {
        executor.submit {
            val target = TerminalTargets.effectiveTarget(activity, backendKey())
            try {
                ssh.writeFile(target, path, content)
                emitRaw("window.__afsResult($rid,true,null)")
            } catch (e: Exception) {
                emitRaw("window.__afsResult($rid,false,${q(e.message ?: "writeFile failed")})")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** JSON-quote a string so it is safe as a JS argument. */
    private fun q(s: String): String = JSONObject.quote(s)

    /** Evaluate arbitrary JS on the UI thread via the WebView. */
    private fun emitRaw(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
