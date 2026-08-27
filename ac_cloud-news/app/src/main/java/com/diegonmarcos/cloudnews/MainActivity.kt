package com.diegonmarcos.cloudnews

import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.updater.Updater

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)
        webView.webViewClient = WebViewClient()
        // Without this the entire UI is a black box: the whole app is one
        // WebView, so a JS error or a diagnostic console.log went nowhere and
        // the devtools logcat endpoint could only ever show the Kotlin side.
        // Anything the page logs now lands in logcat under "NewsWeb", which
        // means /api/diagnostics/logcat can see it from another machine.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val where = "${m.sourceId()?.substringAfterLast('/') ?: "?"}:${m.lineNumber()}"
                val line = "[$where] ${m.message()}"
                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e("NewsWeb", line)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w("NewsWeb", line)
                    else -> Log.i("NewsWeb", line)
                }
                return true
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // SECURITY: attaching a JavascriptInterface is only safe because this
        // WebView loads a LOCAL asset we ship (file:///android_asset/news.html)
        // and never navigates to remote content. Any remote page loaded here
        // would gain the same Java-callable surface as our own UI. Article
        // links are therefore handed to the system browser via
        // NewsBridge.openExternal(), never opened in this WebView.
        webView.addJavascriptInterface(NewsBridge(this), "NewsBridge")
        webView.loadUrl("file:///android_asset/news.html")
        Updater.start(this)
    }
}
