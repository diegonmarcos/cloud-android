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
            setPadding(dp(24), dp(32), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.hub_title)
            textSize = 22f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.hub_subtitle, BuildConfig.IPC_VERSION, BuildConfig.GIT_SHORT_SHA)
            textSize = 12f
            alpha = 0.6f
            setPadding(0, 0, 0, dp(24))
        })

        for (fork in ForkRegistry.forks) {
            root.addView(tileFor(fork))
        }

        // code-server is browser-only — a deep-link tile, not a fork.
        root.addView(codeServerTile())

        setContentView(root)
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
            else -> {
                val launch = packageManager.getLaunchIntentForPackage(fork.appId)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                } else {
                    Toast.makeText(this, getString(R.string.tile_launch_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
