package com.diegonmarcos.superapp.configs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.Fleet
import kotlin.concurrent.thread

/**
 * Constellation AppStore — Configs → Constellation. superapp is the fleet
 * manager: install / update / uninstall / open EVERY constellation APK, with
 * full per-app data (installed version+sha, remote GHCR digest, status).
 * Fleet list is data-driven from BuildConfig.CONSTELLATION_FLEET_B64
 * (data/constellation-fleet.json, auto-scanned from each app's build.json).
 * All mechanism lives in the R-free [Fleet] engine; this is just the UI.
 */
class ConstellationFragment : Fragment() {

    private val apps by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(col)

        col.addView(title(ctx, "Constellation AppStore"))
        col.addView(small(ctx, "${apps.size} apps · superapp is the fleet manager (install · update · uninstall · open)"))

        // ── global controls ──────────────────────────────────────────────
        col.addView(actionButton(ctx, "Check all now") { refresh(ctx, col) })
        col.addView(rowButton(ctx) {
            it.text = "Auto-update: " + (if (AutoUpdatePrefs.silent(ctx)) "ON" else "OFF")
            it.setOnClickListener { _ ->
                AutoUpdatePrefs.setSilent(ctx, !AutoUpdatePrefs.silent(ctx))
                Toast.makeText(ctx, "Auto-update " + (if (AutoUpdatePrefs.silent(ctx)) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
                refresh(ctx, col)
            }
        })
        col.addView(rowButton(ctx) {
            it.text = if (AutoUpdatePrefs.canInstallSilently(ctx)) "✓ Install unknown apps granted" else "Grant: Install unknown apps"
            it.setOnClickListener { _ -> openUnknownAppSources(ctx) }
        })

        rebuild(ctx, col)
        return scroll
    }

    // Re-render the per-app section (below the fixed header rows) + kick checks.
    private val appViews = LinkedHashMap<String, TextView>()
    private var listHost: LinearLayout? = null

    private fun rebuild(ctx: Context, col: LinearLayout) {
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listHost = host
        appViews.clear()
        for (app in apps) {
            host.addView(sectionHeader(ctx, app.label + (if (app.blocked) "  ·  blocked" else "")))
            val status = row(ctx, "checking…")
            appViews[app.id] = status
            host.addView(status)
            host.addView(small(ctx, app.pkg + "  ·  " + app.image))
            host.addView(appButtons(ctx, app, col))
        }
        col.addView(host)
        refresh(ctx, col)
    }

    private fun refresh(ctx: Context, col: LinearLayout) {
        for (app in apps) appViews[app.id]?.text = "checking…"
        thread(name = "fleet-check") {
            for (app in apps) {
                val label = describe(Fleet.status(ctx, app))
                appViews[app.id]?.let { tv -> tv.post { tv.text = label } }
            }
        }
    }

    private fun describe(s: Fleet.State): String = when (s) {
        is Fleet.State.Installed        -> "✓ installed  v${s.versionName} (${s.versionCode})  sha ${s.sha12}"
        is Fleet.State.UpdateAvailable  -> "⬆ update available  (installed v${s.versionName ?: "—"} → ${s.remoteDigest12})"
        Fleet.State.Missing             -> "◯ not installed"
        Fleet.State.Blocked             -> "⛔ blocked (not published)"
        is Fleet.State.Error            -> "⚠ ${s.message}"
    }

    private fun appButtons(ctx: Context, app: Fleet.App, col: LinearLayout): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(actionButton(ctx, "Open") { openApp(ctx, app.pkg) })
        if (!app.blocked)
            box.addView(actionButton(ctx, "Install / Update") { act(ctx, app, col) })
        box.addView(actionButton(ctx, "Uninstall") {
            runCatching { Fleet.uninstall(ctx, app.pkg) }
                .onFailure { Toast.makeText(ctx, "Uninstall: ${it.message}", Toast.LENGTH_LONG).show() }
        })
        return box
    }

    private fun act(ctx: Context, app: Fleet.App, col: LinearLayout) {
        Toast.makeText(ctx, "Installing ${app.label}…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-install-${app.id}") {
            try {
                Fleet.install(ctx, app)
            } catch (t: Throwable) {
                val m = t.message ?: t.toString()
                view?.post { Toast.makeText(ctx, "${app.label}: $m", Toast.LENGTH_LONG).show() }
            }
            appViews[app.id]?.let { tv -> tv.post { tv.text = describe(Fleet.status(ctx, app)) } }
        }
    }

    private fun openApp(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        else Toast.makeText(ctx, "Not installed", Toast.LENGTH_SHORT).show()
    }

    private fun openUnknownAppSources(ctx: Context) {
        val self = android.net.Uri.fromParts("package", ctx.packageName, null)
        val scoped = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, self)
        if (scoped.resolveActivity(ctx.packageManager) != null) { startActivity(scoped); return }
        val list = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        if (list.resolveActivity(ctx.packageManager) != null) { startActivity(list); return }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self))
    }

    // ── tiny view helpers (self-contained; matches DevControl visual style) ──
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 20f; setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)); setTextColor(0xFFFFFFFF.toInt())
    }
    private fun sectionHeader(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 15f; setPadding(0, dp(ctx, 12), 0, dp(ctx, 2)); setTextColor(0xFFB794F4.toInt())
    }
    private fun small(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 11f; setTextColor(0x99FFFFFF.toInt()); typeface = android.graphics.Typeface.MONOSPACE
    }
    private fun row(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 13f; setPadding(0, dp(ctx, 2), 0, dp(ctx, 2)); setTextColor(0xFFFFFFFF.toInt())
    }
    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12f
        setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF7C3AED.toInt())
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 4)); layoutParams = lp
        isClickable = true; setOnClickListener { onClick() }
    }
    private fun rowButton(ctx: Context, build: (TextView) -> Unit) = TextView(ctx).apply {
        gravity = Gravity.CENTER; textSize = 12f
        setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2A2A33.toInt())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 4)); layoutParams = lp
        build(this)
    }
}
