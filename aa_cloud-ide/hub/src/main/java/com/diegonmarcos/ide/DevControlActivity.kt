package com.diegonmarcos.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Dev Control — logcat viewer, copy/export, and a local dev API toggle.
 * Mirrors ConfigsActivity UI style (Ui.screen / Ui.header / Ui.appCard).
 */
class DevControlActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private var logText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        Ui.screen(this, root)

        // ── Header ────────────────────────────────────────────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val titleTv = TextView(this).apply {
            text = "Dev Control"
            textSize = 20f
            setTextColor(Ui.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val closeBtn = Button(this).apply {
            text = "✕ Close"
            textSize = 13f
            setTextColor(Ui.TEXT_DIM)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        headerRow.addView(titleTv)
        headerRow.addView(closeBtn)
        root.addView(headerRow)

        // ── Body (scrollable) ─────────────────────────────────────────────
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(24))
        }

        // Logcat label
        body.addView(TextView(this).apply {
            text = "Logcat  (last 2000 lines)"
            textSize = 12f
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, 0, 0, dp(6))
        })

        // Logcat viewer — monospace, selectable, dark bg
        logView = TextView(this).apply {
            textSize = 9f
            setTextColor(Ui.TEXT)
            setBackgroundColor(Ui.CARD)
            setTypeface(Typeface.MONOSPACE)
            isTextSelectable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = "Loading…"
        }
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(300))
            addView(logView)
        }
        body.addView(logScroll)

        // ── Action buttons row ────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        fun actionBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also {
                it.marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }
        btnRow.addView(actionBtn("Refresh") { captureLogcat(logScroll) })
        btnRow.addView(actionBtn("Copy") { copyLogcat() })
        btnRow.addView(actionBtn("Export") { exportLogcat() })
        body.addView(btnRow)

        // ── Dev API row ───────────────────────────────────────────────────
        body.addView(TextView(this).apply {
            text = "Dev API"
            textSize = 12f
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, dp(8), 0, dp(4))
        })

        val apiStatusTv = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.ACCENT_SOFT)
        }

        val apiEnabled = IdePrefs.devApiEnabled(this)
        if (apiEnabled) {
            apiStatusTv.text = "http://127.0.0.1:${DevApiServer.PORT}"
            DevApiServer.start()
        } else {
            apiStatusTv.text = "Off"
        }

        val apiToggle = CheckBox(this).apply {
            text = "Enable local dev API (127.0.0.1:${DevApiServer.PORT})"
            textSize = 13f
            setTextColor(Ui.TEXT)
            isChecked = apiEnabled
            setOnCheckedChangeListener { _, checked ->
                IdePrefs.setDevApiEnabled(this@DevControlActivity, checked)
                if (checked) {
                    DevApiServer.start()
                    apiStatusTv.text = "http://127.0.0.1:${DevApiServer.PORT}"
                } else {
                    DevApiServer.stop()
                    apiStatusTv.text = "Off"
                }
            }
        }
        body.addView(apiToggle)
        body.addView(apiStatusTv)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        scroll.addView(body)
        root.addView(scroll)
        setContentView(root)

        // Kick off initial logcat capture
        captureLogcat(logScroll)
    }

    private fun captureLogcat(scrollView: ScrollView) {
        logView.text = "Loading…"
        Thread({
            val text = runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "2000"))
                    .inputStream.bufferedReader().readText()
            }.getOrElse { "logcat read failed: $it\n" }
            runOnUiThread {
                logText = text
                logView.text = text
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }).also { it.isDaemon = true }.start()
    }

    private fun copyLogcat() {
        if (logText.isEmpty()) { Toast.makeText(this, "Nothing to copy yet", Toast.LENGTH_SHORT).show(); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cloud-ide-logcat", logText))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportLogcat() {
        if (logText.isEmpty()) { Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show(); return }
        val filename = "cloud-ide-logcat-${System.currentTimeMillis()}.txt"
        val saved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(logText.toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                filename
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                java.io.File(dir, filename).apply { writeText(logText) }; filename
            }
        }.getOrNull()
        if (saved != null) Toast.makeText(this, "Saved: $saved", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
