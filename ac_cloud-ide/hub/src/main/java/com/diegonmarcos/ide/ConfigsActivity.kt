package com.diegonmarcos.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

        // ── Terminal setup instructions (shown on the page) ───────────────
        body.addView(Ui.header(
            this,
            getString(R.string.cfg_terminal_setup_title),
            getString(R.string.cfg_terminal_setup_steps),
        ))

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

        // ── Terminal connection overrides ─────────────────────────────────
        val effectiveTarget = TerminalTargets.effectiveTarget(this, currentBackend)
        body.addView(Ui.appCard(
            this, "⚙",
            getString(R.string.cfg_terminal_conn),
            getString(R.string.cfg_terminal_conn_sub, effectiveTarget.label),
            enabled = true,
        ) { showTerminalConnDialog(currentBackend) })

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

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        scroll.addView(body)
        root.addView(scroll)
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

    private fun showTerminalConnDialog(backend: String) {
        val effective = TerminalTargets.effectiveTarget(this, backend)
        val ssh = SshBackend(this)

        // ── Dialog content view ───────────────────────────────────────────
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // Hint line
        container.addView(TextView(this).apply {
            text = "Connecting to ${effective.label} — SSH"
            textSize = 13f
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, 0, 0, dp(14))
        })

        // Host
        container.addView(TextView(this).apply {
            text = getString(R.string.term_conn_host)
            textSize = 12f
            setTextColor(Ui.TEXT_DIM)
        })
        val hostEdit = EditText(this).apply {
            setText(effective.host)
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, dp(4), 0, dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }
        container.addView(hostEdit)

        // Port
        container.addView(TextView(this).apply {
            text = getString(R.string.term_conn_port)
            textSize = 12f
            setTextColor(Ui.TEXT_DIM)
        })
        val portEdit = EditText(this).apply {
            setText(effective.port.toString())
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, dp(4), 0, dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }
        container.addView(portEdit)

        // User
        container.addView(TextView(this).apply {
            text = getString(R.string.term_conn_user)
            textSize = 12f
            setTextColor(Ui.TEXT_DIM)
        })
        val userEdit = EditText(this).apply {
            setText(effective.user)
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(0, dp(4), 0, dp(16))
            setBackgroundColor(Color.TRANSPARENT)
        }
        container.addView(userEdit)

        // Test button + status label
        val statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, dp(6), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val testBtn = Button(this).apply {
            text = getString(R.string.term_conn_test)
            setOnClickListener {
                val host = hostEdit.text.toString().trim()
                val portStr = portEdit.text.toString().trim()
                val user = userEdit.text.toString().trim()
                val port = portStr.toIntOrNull() ?: -1
                if (host.isBlank() || port <= 0 || user.isBlank()) {
                    statusView.setTextColor(Color.RED)
                    statusView.text = "Fill in valid host, port and user first"
                    return@setOnClickListener
                }
                val probe = TerminalTargets.Target(
                    key   = effective.key,
                    label = effective.label,
                    host  = host,
                    port  = port,
                    user  = user,
                )
                statusView.setTextColor(Ui.TEXT_DIM)
                statusView.text = getString(R.string.term_conn_testing)
                Thread {
                    val err = ssh.testConnection(probe)
                    runOnUiThread {
                        if (err == null) {
                            statusView.setTextColor(Color.GREEN)
                            statusView.text = getString(R.string.term_conn_ok)
                        } else {
                            statusView.setTextColor(Color.RED)
                            statusView.text = "✗ $err"
                        }
                    }
                }.also { it.isDaemon = true }.start()
            }
        }
        container.addView(testBtn)
        container.addView(statusView)

        // ── Build dialog ──────────────────────────────────────────────────
        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton(getString(R.string.term_conn_save)) { _, _ ->
                val host = hostEdit.text.toString().trim()
                val portStr = portEdit.text.toString().trim()
                val user = userEdit.text.toString().trim()
                val port = portStr.toIntOrNull()
                if (host.isBlank() || port == null || port <= 0 || user.isBlank()) {
                    Toast.makeText(this, "Invalid host, port or user — not saved", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                IdePrefs.setTerminalConn(this, backend, host, port, user)
                Toast.makeText(this, getString(R.string.term_conn_saved), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.term_conn_cancel), null)
            .show()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
