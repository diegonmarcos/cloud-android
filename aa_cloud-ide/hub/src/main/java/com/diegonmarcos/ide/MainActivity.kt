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
 * The Cloud-IDE home — a WRAPPER around exactly TWO self-contained apps:
 * Acode (editor) and Amaze File Manager (files). Their real upstream APKs ride
 * INSIDE this APK (assets/forks/, seeded by bundle-forks) and open their own
 * full UIs under our overlay nav bar (NavOverlayService).
 *
 *   tile not installed → installs the bundled APK (one system confirm) → ready
 *   tile installed     → launches the app + raises the wrapper bar
 *
 * Tiles are data-driven from ForkRegistry.homeForks (= forks with an `embedded`
 * block in build.json — exactly Acode + Amaze). Below them: a small Configs row
 * (Update + About, equal to Cloud-SuperApp). Nothing else on the home.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tilesHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        // In-app consistency bar (up-chip to Cloud-SuperApp).
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

        // THE two apps — and nothing else.
        tilesHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tilesHost)
        renderTiles()

        // Configs (Update + About) — small secondary row.
        body.addView(TextView(this).apply {
            text = getString(R.string.tile_configs)
            textSize = 13f
            alpha = 0.7f
            setPadding(dp(20), dp(18), dp(20), dp(12))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ConfigsActivity::class.java))
            }
        })

        root.addView(body)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        renderTiles()   // refresh installed-state after a PackageInstaller round-trip
        // The bar is PERSISTENT across all our apps — raise it (current = hub)
        // whenever the hub comes forward and the overlay grant exists.
        if (NavOverlayService.hasPermission(this)) {
            NavOverlayService.show(this, packageName)
        }
    }

    private fun renderTiles() {
        tilesHost.removeAllViews()
        for (fork in ForkRegistry.homeForks) {
            tilesHost.addView(tileFor(fork))
        }
    }

    private fun tileFor(fork: Fork): TextView {
        val installed = fork.isInstalled(this)
        val bundled = BundledForkInstaller.isBundled(this, fork)
        val status = when {
            installed -> getString(R.string.tile_open)
            bundled -> getString(R.string.tile_install_bundled)
            else -> getString(R.string.tile_missing_bundle)
        }
        return TextView(this).apply {
            text = "${fork.displayName}\n$status"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(26))
            isClickable = true
            alpha = if (installed || bundled) 1f else 0.5f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
            setOnClickListener { onTileTapped(fork, installed, bundled) }
        }
    }

    private fun onTileTapped(fork: Fork, installed: Boolean, bundled: Boolean) {
        when {
            installed -> {
                ensureOverlayPermission()
                if (!ForkLauncher.launch(this, fork)) {
                    Toast.makeText(this, getString(R.string.tile_launch_failed), Toast.LENGTH_SHORT).show()
                }
            }
            bundled -> {
                Toast.makeText(this, getString(R.string.tile_installing, fork.displayName), Toast.LENGTH_SHORT).show()
                BundledForkInstaller.install(this, fork)
            }
            else ->
                Toast.makeText(this, getString(R.string.tile_missing_bundle), Toast.LENGTH_LONG).show()
        }
    }

    /** One-time "Display over other apps" grant for the wrapper bar. The app
     *  still launches without it — the bar appears from the next launch on. */
    private fun ensureOverlayPermission() {
        if (!NavOverlayService.hasPermission(this)) {
            Toast.makeText(this, getString(R.string.overlay_grant_hint), Toast.LENGTH_LONG).show()
            NavOverlayService.requestPermission(this)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
