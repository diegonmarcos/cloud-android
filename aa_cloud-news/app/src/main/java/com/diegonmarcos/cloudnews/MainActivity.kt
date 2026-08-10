package com.diegonmarcos.cloudnews

import android.os.Bundle
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
