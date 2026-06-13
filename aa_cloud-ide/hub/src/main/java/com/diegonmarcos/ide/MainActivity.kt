package com.diegonmarcos.ide

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The Cloud-IDE home — a WRAPPER around exactly TWO self-contained apps:
 * Acode (editor) and Amaze File Manager (files). Their real upstream APKs ride
 * INSIDE this APK (assets/forks/, seeded by bundle-forks) and open their own
 * full UIs under our persistent overlay nav bar (NavOverlayService).
 *
 *   card not installed → installs the bundled APK (one system confirm) → ready
 *   card installed     → launches the app + raises the wrapper bar
 *
 * Cards are data-driven from ForkRegistry.homeForks (= forks with an `embedded`
 * block in build.json — exactly Acode + Amaze). Visual language from Ui (dark
 * surface, purple accent, rounded ripple cards).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tilesHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        Ui.screen(this, root)

        // In-app consistency bar (up-chip to Cloud-SuperApp).
        NavBar.buildBar(this, packageName)?.let { root.addView(it) }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(body)

        body.addView(Ui.header(
            this,
            getString(R.string.hub_title),
            getString(R.string.hub_subtitle, BuildConfig.IPC_VERSION, BuildConfig.GIT_SHORT_SHA),
        ))

        // THE two apps — and nothing else.
        tilesHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tilesHost)
        renderTiles()

        // Configs (Update + About) — small secondary row.
        body.addView(Ui.rowCard(this, getString(R.string.tile_configs)) {
            startActivity(Intent(this, ConfigsActivity::class.java))
        })

        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        renderTiles()   // refresh installed-state after a PackageInstaller round-trip
        // The wrapper bar is OFF by default (Cloud-SuperApp owns the single
        // cross-app bar). Only raise it when the user explicitly enabled it in
        // Configs (IdePrefs.overlayNavBar, default false) AND granted overlay.
        if (IdePrefs.overlayNavBar(this) && NavOverlayService.hasPermission(this)) {
            NavOverlayService.show(this, packageName)
        }
    }

    private fun renderTiles() {
        tilesHost.removeAllViews()
        for (fork in ForkRegistry.homeForks) {
            val installed = fork.isInstalled(this)
            val child = installed && fork.isChildInstall(this)
            val bundled = BundledForkInstaller.isBundled(this, fork)
            val status = when {
                child -> getString(R.string.tile_open_child)
                installed -> getString(
                    R.string.tile_open_foreign, fork.installerOf(this) ?: "unknown")
                bundled -> getString(R.string.tile_install_bundled)
                else -> getString(R.string.tile_missing_bundle)
            }
            // Long-press on a FOREIGN phone copy: replace it with OUR bundled
            // child APK (PackageInstaller update; signature conflicts surface
            // via the receiver instead of failing silently).
            val replace: (() -> Unit)? = if (installed && !child && bundled) {
                {
                    Toast.makeText(this,
                        getString(R.string.tile_installing, fork.displayName), Toast.LENGTH_SHORT).show()
                    BundledForkInstaller.install(this, fork)
                }
            } else null
            tilesHost.addView(Ui.appCard(
                this,
                Ui.glyphFor(fork.displayName),
                fork.displayName,
                status,
                enabled = installed || bundled,
                onLongPress = replace,
            ) { onTileTapped(fork, installed, bundled) })
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

    private fun dp(v: Int): Int = Ui.dp(this, v)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
