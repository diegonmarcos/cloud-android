package com.diegonmarcos.ide

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.ide.update.Updater

/**
 * Configs — Update + About, equal to Cloud-SuperApp. "Check for updates" runs
 * the same WorkManager flow as the periodic auto-updater and shows the fullscreen
 * UpdateOverlay (UpdateProgress-driven). "About" opens the SystemInfoPopup
 * (the dark-glass system-info bubble). Data-driven; no per-item hardcoding.
 */
class ConfigsActivity : AppCompatActivity() {

    private var overlayShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.configs_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        NavBar.buildBar(this, packageName)?.let { root.addView(it) }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }

        // ── Update ────────────────────────────────────────────────────────
        body.addView(item(getString(R.string.cfg_check_updates)) { startUpdateCheck() })
        body.addView(TextView(this).apply {
            text = getString(
                R.string.cfg_autoupdate,
                if (BuildConfig.AUTO_UPDATE_ENABLED) "on" else "off",
                BuildConfig.AUTO_UPDATE_INTERVAL_HOURS,
                BuildConfig.AUTO_UPDATE_TAG,
            )
            textSize = 12f; alpha = 0.6f; setPadding(dp(20), 0, dp(20), dp(16))
        })

        // ── About ─────────────────────────────────────────────────────────
        lateinit var aboutRow: TextView
        aboutRow = item(getString(R.string.cfg_about)) { SystemInfoPopup.show(this, aboutRow) }
        body.addView(aboutRow)

        root.addView(body)
        setContentView(root)
    }

    private fun startUpdateCheck() {
        if (!overlayShown) {
            overlayShown = true
            UpdateProgress.update(UpdateProgress.State.CheckingManifest)
            UpdateOverlay.newInstance().show(supportFragmentManager, UpdateOverlay.TAG)
        }
        Updater.checkNow(this)
    }

    override fun onResume() {
        super.onResume()
        // Reset the gate when the overlay is gone so a second tap re-opens it.
        if (supportFragmentManager.findFragmentByTag(UpdateOverlay.TAG) == null) overlayShown = false
    }

    private fun item(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
