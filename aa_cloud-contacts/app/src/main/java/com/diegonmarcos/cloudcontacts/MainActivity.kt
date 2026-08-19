package com.diegonmarcos.cloudcontacts

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Contacts — thin WebView shell (cloud-news pattern).
 * The whole UI lives in assets/contacts.html (HTML/Tailwind); ContactsBridge
 * is the Kotlin↔JS seam. The activity owns only the two Android launchers the
 * bridge cannot host itself: the READ_CONTACTS runtime permission and the SAF
 * picker for LinkedIn/Instagram export files.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: ContactsBridge

    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bridge.onPermissionResult(granted)
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            bridge.onFilePicked(uri)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F14"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
        }
        bridge = ContactsBridge(
            activity = this,
            webView = webView,
            requestContactsPermission = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
            launchImportPicker = { importPicker.launch("*/*") },
        )
        webView.addJavascriptInterface(bridge, "Native")
        setContentView(webView)

        // SECURITY: only our bundled asset is ever loaded (file:///android_asset).
        webView.loadUrl("file:///android_asset/contacts.html")

        Updater.start(this)
    }
}
