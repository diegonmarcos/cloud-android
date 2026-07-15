package com.diegonmarcos.superapp.configs

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
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
 * manager: install / update / uninstall / open every constellation APK.
 *
 * Each app's status is fetched on its OWN thread (concurrently), so one slow or
 * unreachable image never blocks the others — the previous single-thread loop
 * was why Dialer showed no status and unpublished Chat looked "stuck". Fleet
 * list is data-driven from BuildConfig.CONSTELLATION_FLEET_B64.
 */
class ConstellationFragment : Fragment() {

    private val apps by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }
    private val statusViews = HashMap<String, TextView>()
    private val actionRows = HashMap<String, LinearLayout>()
    private val installBtns = HashMap<String, TextView>()
    private lateinit var headerControls: LinearLayout

    // amber, green, grey, red, orange, blue
    private val cUp = 0xFF48BB78.toInt(); private val cUpd = 0xFFED8936.toInt()
    private val cMiss = 0xFF63B3ED.toInt(); private val cBlk = 0xFFF56565.toInt()
    private val cErr = 0xFFECC94B.toInt(); private val cDim = 0x99FFFFFF.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 14); setPadding(p, p, p, p)
        }
        scroll.addView(col)

        col.addView(title(ctx, "Constellation AppStore"))
        col.addView(caption(ctx, "${apps.size} apps · superapp is the fleet manager"))

        headerControls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(headerControls)
        renderHeader(ctx)

        for (app in apps) col.addView(appCard(ctx, app))

        checkAll(ctx)
        return scroll
    }

    // ── header: Update-all / Check-all + auto-update toggle + grant ──────────
    private fun renderHeader(ctx: Context) {
        headerControls.removeAllViews()
        // Row 1 — the two batch actions, kept apart so they read as distinct:
        //   Update all → only apps ALREADY installed that have a newer image.
        //   Install all → only apps not yet on the device.
        headerControls.addView(buttonRow(ctx,
            btn(ctx, "⬆  Update all", 0xFF7C3AED.toInt()) { updateAll(ctx) },
            btn(ctx, "⬇  Install all", 0xFF2B6CB0.toInt()) { installMissing(ctx) },
        ))
        // Row 2 — refresh statuses (full width).
        headerControls.addView(buttonRow(ctx,
            btn(ctx, "↻  Check all", 0xFF2A2A33.toInt()) { checkAll(ctx) },
        ))
        val autoOn = AutoUpdatePrefs.enabled(ctx)
        headerControls.addView(buttonRow(ctx,
            btn(ctx, "Auto-update: " + (if (autoOn) "ON" else "OFF"),
                if (autoOn) 0xFF2F855A.toInt() else 0xFF4A4A55.toInt()) {
                AutoUpdatePrefs.setEnabled(ctx, !autoOn)
                // Reconcile the periodic workers immediately: start() schedules
                // when enabled, cancels when disabled (both re-check the pref).
                com.diegonmarcos.superapp.updater.Updater.start(ctx)
                ConstellationWorker.start(ctx)
                Toast.makeText(ctx, "Auto-update " + (if (!autoOn) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
                renderHeader(ctx)
            },
            btn(ctx, if (AutoUpdatePrefs.canInstallSilently(ctx)) "✓ Install perm" else "Grant install",
                if (AutoUpdatePrefs.canInstallSilently(ctx)) 0xFF2A2A33.toInt() else 0xFF7C3AED.toInt()) {
                openUnknownAppSources(ctx)
            },
        ))
        headerControls.addView(caption(ctx,
            if (AutoUpdatePrefs.canInstallSilently(ctx)) "Silent installs enabled."
            else "Grant 'Install unknown apps' for no-tap updates."))
    }

    // ── one card per app ─────────────────────────────────────────────────────
    private fun appCard(ctx: Context, app: Fleet.App): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C24.toInt())
            val ph = dp(ctx, 12); val pv = dp(ctx, 8)
            setPadding(ph, pv, ph, pv)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 4)); layoutParams = lp
        }

        // ── title row: label (left) + link chips (right) ─────────────────
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(ctx).apply {
            text = app.label; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun linkChip(label: String, url: String) = TextView(ctx).apply {
            text = label; textSize = 11f; setTextColor(cMiss)
            setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2)); isClickable = true
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        if (app.releaseUrl.isNotEmpty()) titleRow.addView(linkChip("APK↗", app.releaseUrl))
        if (app.repoUrl.isNotEmpty())    titleRow.addView(linkChip("GH↗",  app.repoUrl))
        if (app.ghcrPage.isNotEmpty())   titleRow.addView(linkChip("PKG↗", app.ghcrPage))
        card.addView(titleRow)

        // ── pkg · image (mono, compact) ───────────────────────────────────
        card.addView(mono(ctx, app.pkg + "  ·  " + app.image))

        // ── status ────────────────────────────────────────────────────────
        val status = TextView(ctx).apply {
            textSize = 12f; setTextColor(cDim); text = "checking…"
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 2))
        }
        statusViews[app.id] = status
        card.addView(status)

        // ── action buttons (Open · Install/Update · Uninstall) ────────────
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actionRows[app.id] = actions
        actions.addView(btn(ctx, "Open", 0xFF2A2A33.toInt()) { openApp(ctx, Fleet.installedId(ctx, app) ?: app.pkg) })
        if (!app.blocked) {
            val installBtn = btn(ctx, "Install / Update", 0xFF7C3AED.toInt()) { install(ctx, app) }
            installBtns[app.id] = installBtn
            actions.addView(installBtn)
        }
        actions.addView(btn(ctx, "Uninstall", 0xFF4A4A55.toInt()) {
            runCatching { Fleet.uninstall(ctx, Fleet.installedId(ctx, app) ?: app.pkg) }
                .onFailure { Toast.makeText(ctx, "Uninstall: ${it.message}", Toast.LENGTH_LONG).show() }
        })
        card.addView(actions)
        return card
    }

    // ── concurrent status — one thread per app, independent + non-blocking ───
    private fun checkAll(ctx: Context) {
        for (app in apps) {
            statusViews[app.id]?.let { tv -> tv.post { tv.text = "checking…"; tv.setTextColor(cDim) } }
            thread(name = "fleet-check-${app.id}") {
                val st = Fleet.status(ctx, app)
                statusViews[app.id]?.let { tv -> tv.post { paint(tv, st, app.id) } }
            }
        }
    }

    private fun paint(tv: TextView, s: Fleet.State, appId: String) {
        val installed = s is Fleet.State.Installed
        installBtns[appId]?.let {
            it.setBackgroundColor(if (installed) 0xFF4A4A55.toInt() else 0xFF7C3AED.toInt())
            it.isClickable = !installed
        }
        when (s) {
            is Fleet.State.Installed       -> { tv.setTextColor(cUp);  tv.text = "✓ up to date  ·  v${s.versionName} (${s.versionCode})  ·  sha ${s.sha12}" }
            is Fleet.State.UpdateAvailable -> { tv.setTextColor(cUpd); tv.text = "⬆ update available  ·  installed v${s.versionName ?: "—"} → ${s.remoteDigest12}" }
            Fleet.State.Missing            -> { tv.setTextColor(cMiss); tv.text = "◯ not installed  ·  tap Install" }
            Fleet.State.Blocked            -> { tv.setTextColor(cBlk); tv.text = "⛔ not published yet" }
            is Fleet.State.Error           -> { tv.setTextColor(cErr); tv.text = "⚠ ${s.message}" }
        }
    }

    private fun install(ctx: Context, app: Fleet.App) {
        Toast.makeText(ctx, "Installing ${app.label}…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-install-${app.id}") {
            com.diegonmarcos.superapp.updater.UpdateProgress.beginDownload()
            try { Fleet.install(ctx, app) }
            catch (t: Throwable) { view?.post { Toast.makeText(ctx, "${app.label}: ${t.message}", Toast.LENGTH_LONG).show() } }
            statusViews[app.id]?.let { tv -> val st = Fleet.status(ctx, app); tv.post { paint(tv, st, app.id) } }
        }
    }

    // "Update all" — only apps ALREADY installed that have a newer image.
    private fun updateAll(ctx: Context) {
        Toast.makeText(ctx, "Updating installed apps…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-update-all") {
            val n = Fleet.installAll(ctx, apps, Fleet.Mode.UPDATES)
            view?.post {
                Toast.makeText(ctx, if (n == 0) "Everything up to date" else "$n update(s) queued",
                    Toast.LENGTH_LONG).show()
            }
            checkAll(ctx)
        }
    }

    // "Install all" — only apps not yet on the device.
    private fun installMissing(ctx: Context) {
        Toast.makeText(ctx, "Installing missing apps…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-install-all") {
            val n = Fleet.installAll(ctx, apps, Fleet.Mode.MISSING)
            view?.post {
                Toast.makeText(ctx, if (n == 0) "All apps already installed" else "$n install(s) queued",
                    Toast.LENGTH_LONG).show()
            }
            checkAll(ctx)
        }
    }

    private fun openApp(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else Toast.makeText(ctx, "Not installed", Toast.LENGTH_SHORT).show()
    }

    private fun openUnknownAppSources(ctx: Context) {
        val self = Uri.fromParts("package", ctx.packageName, null)
        val scoped = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, self)
        if (scoped.resolveActivity(ctx.packageManager) != null) { startActivity(scoped); return }
        val list = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        if (list.resolveActivity(ctx.packageManager) != null) { startActivity(list); return }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self))
    }

    // ── view helpers ─────────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
    }
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun mono(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 11f; setTextColor(cDim); typeface = Typeface.MONOSPACE
    }
    private fun btn(ctx: Context, label: String, bg: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7))
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(bg)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(ctx, 3), dp(ctx, 4), dp(ctx, 3), dp(ctx, 2)); layoutParams = lp
        isClickable = true; setOnClickListener { onClick() }
    }
    private fun buttonRow(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; for (v in views) addView(v)
    }
}
