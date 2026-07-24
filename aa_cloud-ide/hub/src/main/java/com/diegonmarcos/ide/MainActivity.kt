package com.diegonmarcos.ide

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The Cloud-IDE home — a thin WebView launcher for the my-konsole frontend
 * (assets/frontend/, seeded by build.sh::step_bundle_frontend). In v1 the
 * frontend talked to the local my-konsole engine over ws://127.0.0.1:7333
 * (transport.js). From v2 the hub injects a `window.Transport` shim backed by
 * [TerminalBridge] / [SshBackend] so the frontend SSHes directly into the
 * selected phone env (Termux / Nix-on-Droid) — no local engine process needed.
 *
 * The shim is injected via [WebViewCompat.addDocumentStartJavaScript] before any
 * page scripts execute. If the WebView version is too old to support that API the
 * shim is not injected and the frontend falls back to its built-in transport.js.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var ssh: SshBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ssh = SshBackend(this)
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true   // CRITICAL — frontend session-restore uses localStorage
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true   // file:// page opening a ws:// connection
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()       // keep navigation in-app
            webChromeClient = WebChromeClient()   // console logging
        }

        // Wire the bridge — exposes AndroidTerm to JS before loadUrl.
        val bridge = TerminalBridge(this, webView, ssh) { IdePrefs.terminalBackend(this) }
        webView.addJavascriptInterface(bridge, "AndroidTerm")

        // Inject the Transport shim at document-start so it runs before the
        // bundled transport.js (which already guards against clobbering an
        // existing window.Transport — see da_my-konsole guard).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, TRANSPORT_SHIM_JS, setOf("*"))
        }

        webView.loadUrl("file:///android_asset/frontend/index.html")

        // Floating ⚙ → native Configs (terminal backend switcher, SSH connection
        // editor, key pairing, setup steps). The WebView has no chrome, so this
        // is the only entry point into ConfigsActivity.
        val frame = android.widget.FrameLayout(this)
        frame.addView(webView)
        frame.addView(android.widget.TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(0xFFE9D8FD.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(dpx(12), dpx(8), dpx(12), dpx(8))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END,
            ).apply { val m = dpx(14); rightMargin = m; bottomMargin = m }
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, ConfigsActivity::class.java))
            }
        })
        setContentView(frame)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        ssh.disconnectAll()
        super.onDestroy()
    }

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Injected before page scripts via WebViewCompat.addDocumentStartJavaScript.
         *
         * Sets window.Transport backed by window.AndroidTerm (the TerminalBridge
         * JavascriptInterface). If AndroidTerm is not present (very old WebView
         * missing the DOCUMENT_START_SCRIPT feature), the shim is never injected
         * so the frontend falls back to its own transport.js.
         *
         * Callback protocol (Android → JS):
         *   window.__aptyData(id, dataStr)       — streamed pty output
         *   window.__aptyExit(id)                — pty channel closed
         *   window.__afsResult(rid, ok, jsonStr) — fs Promise resolved/rejected
         *
         * subs  = { id: { out: cb, exit: cb } }  — onPty / onPtyExit listeners
         * pend  = { rid: { resolve, reject } }    — in-flight fs Promises
         * rid   = incrementing request id counter
         *
         * fs(fn, arg) is the one-arg fs helper (listDir, readFile).
         * writeFile needs 3 args so it is wired inline.
         */
        const val TRANSPORT_SHIM_JS = """(function(){
  if (!window.AndroidTerm) return;
  var subs = {}, pend = {}, rid = 0;

  window.__aptyData = function(id, d) {
    var s = subs[id]; if (s && s.out) s.out(d);
  };
  window.__aptyExit = function(id) {
    var s = subs[id]; if (s && s.exit) s.exit();
  };
  window.__afsResult = function(r, ok, j) {
    var p = pend[r]; if (!p) return; delete pend[r];
    if (ok) p.resolve(j ? JSON.parse(j) : null); else p.reject(j || 'error');
  };

  function fs(fn, arg) {
    var r = ++rid;
    return new Promise(function(res, rej) {
      pend[r] = { resolve: res, reject: rej };
      AndroidTerm[fn](r, arg);
    });
  }

  window.Transport = {
    ptyStart: function(id, c, r, cwd) {
      AndroidTerm.ptyStart(id, c, r, cwd || '');
      return Promise.resolve();
    },
    ptyWrite:  function(id, d) { AndroidTerm.ptyWrite(id, d); return Promise.resolve(); },
    ptyResize: function(id, c, r) { AndroidTerm.ptyResize(id, c, r); },
    ptyKill:   function(id) { AndroidTerm.ptyKill(id); },
    onPty: function(id, cb) {
      var s = subs[id] || {}; s.out = cb; subs[id] = s;
      return function() { s.out = null; };
    },
    onPtyExit: function(id, cb) {
      var s = subs[id] || {}; s.exit = cb; subs[id] = s;
      return function() { s.exit = null; };
    },
    listDir:  function(p) { return fs('listDir', p); },
    readFile: function(p) { return fs('readFile', p); },
    writeFile: function(p, ct) {
      var r = ++rid;
      return new Promise(function(res, rej) {
        pend[r] = { resolve: res, reject: rej };
        AndroidTerm.writeFile(r, p, ct);
      });
    }
  };
})();"""
    }
}
