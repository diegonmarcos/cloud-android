package com.diegonmarcos.ide

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.ide.update.Updater

/**
 * Configs — the hub's settings surface. Same Configs-subitems pattern as
 * Cloud-SuperApp: an **Update** feature (manual "Check for updates" driving the
 * same WorkManager flow as the periodic auto-updater, with live progress) and an
 * **About** entry. Thin + data-driven; no per-item hardcoded config.
 */
class ConfigsActivity : AppCompatActivity() {

    private lateinit var updateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.configs_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        // ── Update subitem ───────────────────────────────────────────────
        root.addView(item(getString(R.string.cfg_check_updates)) { Updater.checkNow(this) })
        updateStatus = TextView(this).apply {
            textSize = 12f; alpha = 0.6f
            setPadding(dp(20), 0, dp(20), dp(16))
        }
        root.addView(updateStatus)

        // ── About subitem ────────────────────────────────────────────────
        root.addView(item(getString(R.string.cfg_about)) {
            startActivity(Intent(this, AboutActivity::class.java))
        })

        // ── Auto-update status line (data-driven) ────────────────────────
        root.addView(TextView(this).apply {
            text = getString(
                R.string.cfg_autoupdate,
                if (BuildConfig.AUTO_UPDATE_ENABLED) "on" else "off",
                BuildConfig.AUTO_UPDATE_INTERVAL_HOURS,
                BuildConfig.AUTO_UPDATE_TAG,
            )
            textSize = 12f; alpha = 0.6f
            setPadding(dp(20), dp(24), dp(20), 0)
        })

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        UpdateProgress.setListener { state -> runOnUiThread { renderUpdate(state) } }
    }

    override fun onStop() {
        super.onStop()
        UpdateProgress.setListener(null)
    }

    private fun renderUpdate(state: UpdateProgress.State) {
        updateStatus.text = when (state) {
            is UpdateProgress.State.Idle -> ""
            is UpdateProgress.State.CheckingManifest -> getString(R.string.upd_checking)
            is UpdateProgress.State.UpToDate -> getString(R.string.upd_uptodate, state.tag)
            is UpdateProgress.State.Downloading -> getString(R.string.upd_downloading, state.percent)
            is UpdateProgress.State.Installing -> getString(R.string.upd_installing)
            is UpdateProgress.State.Done -> getString(R.string.upd_done)
            is UpdateProgress.State.Failed -> getString(R.string.upd_failed, state.message)
        }
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
