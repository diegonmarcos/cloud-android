package com.diegonmarcos.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.ide.update.Updater

/**
 * Configs — Update + About, equal to Cloud-SuperApp. "Check for updates" runs
 * the same WorkManager flow as the periodic auto-updater and shows the fullscreen
 * UpdateOverlay (UpdateProgress-driven). "About" opens AboutActivity — the FULL
 * SuperApp About architecture (12 sections, long-press-copy, Copy All Infos).
 * Visual language from Ui (dark surface, purple accent, rounded ripple cards).
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
        Ui.screen(this, root)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }

        body.addView(Ui.header(
            this,
            getString(R.string.configs_title),
            getString(
                R.string.cfg_autoupdate,
                if (BuildConfig.AUTO_UPDATE_ENABLED) "on" else "off",
                BuildConfig.AUTO_UPDATE_INTERVAL_HOURS,
                BuildConfig.AUTO_UPDATE_TAG,
            ),
        ))

        // ── Update ────────────────────────────────────────────────────────
        body.addView(Ui.appCard(
            this, "⟳",
            getString(R.string.cfg_check_updates),
            "${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}:${BuildConfig.AUTO_UPDATE_TAG}",
            enabled = true,
        ) { startUpdateCheck() })

        // ── Terminal backend switcher ─────────────────────────────────────
        val currentBackend = IdePrefs.terminalBackend(this)
        val backendTarget  = TerminalTargets.forBackend(currentBackend)
        body.addView(Ui.appCard(
            this, "⌨",
            getString(R.string.cfg_terminal_backend),
            getString(R.string.cfg_terminal_backend_sub, backendTarget.label,
                backendTarget.host, backendTarget.port),
            enabled = true,
        ) {
            val next = if (currentBackend == IdePrefs.BACKEND_TERMUX)
                IdePrefs.BACKEND_NIXONDROID else IdePrefs.BACKEND_TERMUX
            IdePrefs.setTerminalBackend(this, next)
            recreate()
        })

        // ── Terminal SSH key — copy to authorized_keys ────────────────────
        body.addView(Ui.appCard(
            this, "🔑",
            getString(R.string.cfg_terminal_ssh_key),
            getString(R.string.cfg_terminal_ssh_key_sub),
            enabled = true,
        ) {
            val pubKey = SshBackend(this).publicKeyOpenSsh()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cloud-ide-terminal pubkey", pubKey))
            Toast.makeText(this, getString(R.string.cfg_terminal_ssh_key_copied), Toast.LENGTH_LONG).show()
        })

        // ── About — the FULL SuperApp-architecture page ───────────────────
        body.addView(Ui.appCard(
            this, "ℹ",
            getString(R.string.cfg_about),
            "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA}",
            enabled = true,
        ) { startActivity(android.content.Intent(this, AboutActivity::class.java)) })

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

    private fun dp(v: Int): Int = Ui.dp(this, v)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
