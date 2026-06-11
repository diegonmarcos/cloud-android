package com.diegonmarcos.ide

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The switcher. Renders one tile per fork (data-driven from ForkRegistry, itself
 * fed by build.json::forks) plus a code-server browser tile. Tapping a fork tile
 * launches that fork app; a fork that isn't installed or is blocked shows its
 * status instead. This is intentionally thin — the rich UX lives inside each
 * fork; the hub only routes.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        // Persistent wrapper-chrome consistency bar — up-nav to our ancestors
        // (for the hub: Cloud-SuperApp). Data-driven from contract.navigation.
        NavBar.buildBar(this, packageName)?.let { root.addView(it) }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        body.addView(TextView(this).apply {
            text = getString(R.string.hub_title)
            textSize = 22f
            setPadding(0, 0, 0, dp(4))
        })
        body.addView(TextView(this).apply {
            text = getString(R.string.hub_subtitle, BuildConfig.IPC_VERSION, BuildConfig.GIT_SHORT_SHA)
            textSize = 12f
            alpha = 0.6f
            setPadding(0, 0, 0, dp(24))
        })

        for (fork in ForkRegistry.forks) {
            body.addView(tileFor(fork))
        }

        // code-server is browser-only — a deep-link tile, not a fork.
        body.addView(codeServerTile())

        // Configs (Update + About) — same Configs-subitems pattern as SuperApp.
        body.addView(configsTile())

        root.addView(body)
        setContentView(root)
    }

    private fun configsTile(): TextView =
        baseTile().apply {
            text = getString(R.string.tile_configs)
            setOnClickListener { startActivity(Intent(this@MainActivity, ConfigsActivity::class.java)) }
        }

    private fun tileFor(fork: Fork): TextView {
        val installed = fork.isInstalled(this)
        val status = when {
            fork.blockedOn != null -> getString(R.string.tile_blocked, fork.blockedOn)
            installed -> getString(R.string.tile_open)
            else -> getString(R.string.tile_not_installed)
        }
        return baseTile().apply {
            text = "${fork.domain.replaceFirstChar { it.uppercase() }}\n$status"
            alpha = if (installed && fork.blockedOn == null) 1f else 0.5f
            setOnClickListener { onTileTapped(fork, installed) }
        }
    }

    private fun codeServerTile(): TextView =
        baseTile().apply {
            text = getString(R.string.tile_code_server)
            setOnClickListener {
                val url = Endpoints.codeServerUrl()
                if (url.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.tile_launch_failed, Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
        }

    private fun baseTile(): TextView = TextView(this).apply {
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun onTileTapped(fork: Fork, installed: Boolean) {
        when {
            fork.blockedOn != null ->
                Toast.makeText(this, getString(R.string.tile_blocked, fork.blockedOn), Toast.LENGTH_LONG).show()
            !installed ->
                Toast.makeText(this, getString(R.string.tile_not_installed_long, fork.appId), Toast.LENGTH_LONG).show()
            // Forks are icon-less under the one-icon model — launch by the
            // declared OPEN_FORK action (ForkLauncher), not a launcher intent.
            !ForkLauncher.launch(this, fork) ->
                Toast.makeText(this, getString(R.string.tile_launch_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
