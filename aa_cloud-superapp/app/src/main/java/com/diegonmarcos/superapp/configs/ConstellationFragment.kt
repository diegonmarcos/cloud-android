package com.diegonmarcos.superapp.configs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
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
import com.diegonmarcos.superapp.adbdebug.PackageVerifier
import com.diegonmarcos.superapp.adbdebug.ShellAccess
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

    // Declared in libs:core's manifest at protectionLevel="signature" and merged
    // into every constellation app. Kept as one constant so the UI and any future
    // ContentProvider guard name the same string.
    private val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }
    // Tabs are a VIEW over the one fleet list — kind comes from each app's
    // build.json::release.kind via data/regen.sh, never a hardcoded list here.
    private val apps by lazy { fleet.filter { it.kind != "lib" } }
    private val libs by lazy { fleet.filter { it.kind == "lib" } }

    private val statusViews = HashMap<String, TextView>()
    // The collapsed row shows a one-line summary; the full status line lives in
    // the detail pane, so both need painting from the same state.
    private val fullStatusViews = HashMap<String, TextView>()
    private val dots = HashMap<String, TextView>()
    private val quickBtns = HashMap<String, TextView>()
    // Last known state per app, kept so the filter chips can re-slice the list
    // WITHOUT re-hitting the network - re-checking 24 libs to hide 21 of them
    // would make filtering slower than scrolling.
    private val states = HashMap<String, Fleet.State>()
    private val expanded = HashSet<String>()
    private var filter = 0
    private lateinit var summaryView: TextView
    private lateinit var listHost: LinearLayout
    private val filterChips = ArrayList<TextView>()
    private val actionRows = HashMap<String, LinearLayout>()
    private val installBtns = HashMap<String, TextView>()
    private lateinit var headerControls: LinearLayout
    private lateinit var body: LinearLayout
    private val tabBtns = ArrayList<TextView>()
    private var tab = 0
    // Which per-app permission pane is open, keyed by package (0 = Android, 1 = Cloud).
    private val permTab = HashMap<String, Int>()

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
        col.addView(caption(ctx, "${apps.size} apps · ${libs.size} libs · superapp is the fleet manager"))

        col.addView(tabBar(ctx))
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        renderTab(ctx)
        return scroll
    }

    // ── tabs: Apps | Libs | Perms ────────────────────────────────────────────
    private fun tabBar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(ctx, 8)); layoutParams = lp
        }
        tabBtns.clear()
        listOf("Apps", "Libs", "Perms").forEachIndexed { i, label ->
            val t = TextView(ctx).apply {
                text = label; gravity = Gravity.CENTER; textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(ctx, 8), dp(ctx, 9), dp(ctx, 8), dp(ctx, 9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener { if (tab != i) { tab = i; filter = 0; paintTabs(); renderTab(ctx) } }
            }
            tabBtns.add(t); bar.addView(t)
        }
        paintTabs()
        return bar
    }

    private fun paintTabs() = tabBtns.forEachIndexed { i, t ->
        t.setBackgroundColor(if (i == tab) 0xFF7C3AED.toInt() else 0xFF2A2A33.toInt())
        t.setTextColor(if (i == tab) 0xFFFFFFFF.toInt() else cDim)
    }

    private fun renderTab(ctx: Context) {
        body.removeAllViews()
        statusViews.clear(); actionRows.clear(); installBtns.clear()
        fullStatusViews.clear(); dots.clear(); quickBtns.clear(); filterChips.clear()
        when (tab) {
            0 -> renderFleet(ctx, apps, "Full constellation apps — install, update, open, remove.")
            1 -> renderFleet(ctx, libs,
                "Companion APKs that ship engines behind AIDL bound services instead of UI. " +
                "Updated independently of the apps that bind them; an app degrades gracefully when its lib is absent.")
            else -> renderPerms(ctx)
        }
    }

    private fun renderFleet(ctx: Context, list: List<Fleet.App>, blurb: String) {
        body.addView(caption(ctx, blurb))
        headerControls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(headerControls)
        renderHeader(ctx)
        if (list.isEmpty()) { body.addView(caption(ctx, "Nothing here yet.")); return }
        summaryView = TextView(ctx).apply {
            textSize = 12f; setTextColor(cDim); setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }
        body.addView(summaryView)
        body.addView(filterBar(ctx, list))
        listHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost)
        renderList(ctx, list)
        checkAll(ctx, list)
    }

    /** Filter chips. With two dozen libs the answer to "too much scrolling" is
     *  to stop scrolling: pick the slice you came for. Counts come from the
     *  cached [states], so a chip is instant and never re-checks. */
    private fun filterBar(ctx: Context, list: List<Fleet.App>): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(ctx, 6)) }
        }
        filterChips.clear()
        listOf("All", "⬆ Updates", "◯ Missing", "✓ Installed").forEachIndexed { i, label ->
            val c = TextView(ctx).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(if (i == 0) 0 else dp(ctx, 4), 0, 0, 0) }
                isClickable = true
                setOnClickListener { if (filter != i) { filter = i; paintFilter(); renderList(ctx, list) } }
            }
            filterChips.add(c); bar.addView(c)
        }
        paintFilter()
        return bar
    }

    private fun paintFilter() = filterChips.forEachIndexed { i, c ->
        c.setBackgroundColor(if (i == filter) 0xFF7C3AED.toInt() else 0xFF2A2A33.toInt())
    }

    /** True when [app] belongs in the current filter. An app whose state has not
     *  landed yet only shows under "All" - guessing would flicker it in and out. */
    private fun inFilter(app: Fleet.App): Boolean = when (filter) {
        1 -> states[app.id] is Fleet.State.UpdateAvailable
        2 -> states[app.id] is Fleet.State.Missing
        3 -> states[app.id] is Fleet.State.Installed
        else -> true
    }

    private fun renderList(ctx: Context, list: List<Fleet.App>) {
        listHost.removeAllViews()
        statusViews.clear(); actionRows.clear(); installBtns.clear()
        fullStatusViews.clear(); dots.clear(); quickBtns.clear()
        val shown = list.filter { inFilter(it) }
        if (shown.isEmpty()) { listHost.addView(caption(ctx, "Nothing in this filter.")); return }
        for (app in shown) listHost.addView(fleetRow(ctx, app))
        // Repaint from cache so a filtered rebuild shows real state immediately
        // instead of 24 rows saying "checking..." for a list already checked.
        for (app in shown) states[app.id]?.let { paint(app.id, it) }
        updateSummary(list)
    }

    private fun updateSummary(list: List<Fleet.App>) {
        if (!::summaryView.isInitialized) return
        val known = list.mapNotNull { states[it.id] }
        val upd = known.count { it is Fleet.State.UpdateAvailable }
        val miss = known.count { it is Fleet.State.Missing }
        val bytes = known.sumOf { it.bytes }
        summaryView.text = buildString {
            append("${list.size} total")
            if (known.size < list.size) append("  ·  ${list.size - known.size} checking")
            if (upd > 0) append("  ·  $upd update${if (upd == 1) "" else "s"}")
            if (miss > 0) append("  ·  $miss missing")
            if (bytes > 0) append("  ·  ${human(bytes)}")
        }
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

        // Row 3 - the OTHER dialog. "Install unknown apps" above is per-installer
        // and one-time; this is Play Protect's per-INSTALL scan prompt, which no
        // installer can opt out of from inside its own process. Writing the
        // verifier settings needs WRITE_SECURE_SETTINGS, so it goes through the
        // shell channel (Shizuku / embedded adb). Reading is unprivileged, so the
        // label is always the device's real state even with no channel present.
        val scan = PackageVerifier.state(ctx)
        headerControls.addView(buttonRow(ctx,
            btn(ctx, "Play Protect scan: " + (if (scan.on) "ON" else "OFF"),
                if (scan.on) 0xFF4A4A55.toInt() else 0xFF2F855A.toInt()) {
                Toast.makeText(ctx, "Asking the shell channel...", Toast.LENGTH_SHORT).show()
                // setScanning binds Shizuku, which blocks - never on the main thread.
                thread(name = "play-protect-toggle") {
                    val want = !scan.on
                    fun apply(): PackageVerifier.Result = PackageVerifier.setScanning(ctx, want)
                    fun report(r: PackageVerifier.Result) = headerControls.post {
                        Toast.makeText(ctx,
                            if (r.ok) r.state.describe() + " - via " + r.channel else r.output,
                            Toast.LENGTH_LONG).show()
                        renderHeader(ctx)
                    }
                    val first = apply()
                    if (first.channel != "none") { report(first); return@thread }
                    // No channel yet: START the flow that grants one instead of
                    // telling the user to go find it. If the grant lands without
                    // another screen (the Shizuku prompt), finish the toggle
                    // ourselves - the tap that got us here already said what to do.
                    val msg = ShellAccess.ensure(ctx) {
                        thread(name = "play-protect-retry") { report(apply()) }
                    }
                    headerControls.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                }
            },
        ))
        headerControls.addView(caption(ctx,
            if (!scan.on) "No install-scan prompt - fleet installs go straight through."
            else "Play Protect prompts on every install. Turning it off needs Shizuku " +
                 "or the embedded adb channel; it is device-wide and survives uninstall."))
    }

    // ── one COLLAPSED row per app; the full card is one tap away ─────────────
    // Seven lines per entry x 24 libs was twelve screens of scrolling. The row
    // keeps what you scan by (state, name, version, size) and defers what you
    // only ever inspect (package, image, sha, links, permissions) into a detail
    // pane. Nothing is dropped - it moves behind the chevron.
    private fun fleetRow(ctx: Context, app: Fleet.App): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C24.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(ctx, 2), 0, dp(ctx, 2)); layoutParams = lp
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
            isClickable = true
        }
        val dot = TextView(ctx).apply {
            text = "·"; textSize = 13f; setTextColor(cDim); setPadding(0, 0, dp(ctx, 8), 0)
        }
        val name = TextView(ctx).apply {
            text = app.label; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val meta = TextView(ctx).apply { text = "checking…"; textSize = 11f; setTextColor(cDim); maxLines = 1 }
        // The per-app action, on the collapsed row on purpose: updating ONE app
        // is the common case, and making it expand-then-tap would cost two taps
        // for the thing people do most. Hidden when the app is up to date, so
        // the column only ever shows actionable rows.
        val quick = TextView(ctx).apply {
            textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(ctx, 11), dp(ctx, 4), dp(ctx, 11), dp(ctx, 4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(ctx, 8), 0, 0, 0) }
            setOnClickListener { install(ctx, app) }
        }
        val chev = TextView(ctx).apply {
            text = if (expanded.contains(app.id)) "⌄" else "›"
            textSize = 15f; setTextColor(cDim); setPadding(dp(ctx, 10), 0, 0, 0)
        }
        head.addView(dot); head.addView(name); head.addView(meta); head.addView(quick); head.addView(chev)

        val detail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 10))
            visibility = if (expanded.contains(app.id)) View.VISIBLE else View.GONE
        }
        detailBody(ctx, app, detail)
        head.setOnClickListener {
            val open = !expanded.contains(app.id)
            if (open) expanded.add(app.id) else expanded.remove(app.id)
            detail.visibility = if (open) View.VISIBLE else View.GONE
            chev.text = if (open) "⌄" else "›"
        }

        dots[app.id] = dot; statusViews[app.id] = meta; quickBtns[app.id] = quick
        card.addView(head); card.addView(detail)
        return card
    }

    /** Everything the old always-visible card carried, now behind the chevron. */
    private fun detailBody(ctx: Context, app: Fleet.App, into: LinearLayout) {
        fun linkChip(label: String, url: String) = TextView(ctx).apply {
            text = label; textSize = 11f; setTextColor(cMiss)
            setPadding(0, dp(ctx, 2), dp(ctx, 10), dp(ctx, 2)); isClickable = true
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        val links = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (app.releaseUrl.isNotEmpty()) links.addView(linkChip("APK↗", app.releaseUrl))
        if (app.repoUrl.isNotEmpty())    links.addView(linkChip("GH↗",  app.repoUrl))
        if (app.ghcrPage.isNotEmpty())   links.addView(linkChip("PKG↗", app.ghcrPage))
        if (links.childCount > 0) into.addView(links)

        into.addView(mono(ctx, app.pkg + "  ·  " + app.image))

        val status = TextView(ctx).apply {
            textSize = 12f; setTextColor(cDim); text = "checking…"
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 4))
        }
        fullStatusViews[app.id] = status
        into.addView(status)

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
        into.addView(actions)
    }
    /** The fleet slice the visible tab operates on (Perms falls back to apps). */
    private fun current(): List<Fleet.App> = if (tab == 1) libs else apps

    // ── concurrent status — one thread per app, independent + non-blocking ───
    private fun checkAll(ctx: Context, list: List<Fleet.App> = current()) {
        for (app in list) {
            statusViews[app.id]?.let { tv -> tv.post { tv.text = "checking…"; tv.setTextColor(cDim) } }
            thread(name = "fleet-check-${app.id}") {
                val st = Fleet.status(ctx, app)
                // Post on `body`, not on the row: a filtered-out app still has a
                // state worth recording, and it has no row to post to.
                body.post { paint(app.id, st); updateSummary(list) }
            }
        }
    }

    /** One state -> dot, collapsed meta line, quick button, full status line.
     *  Tolerates absent views: the app may be filtered out of the visible list
     *  while its check thread is still in flight. */
    private fun paint(appId: String, s: Fleet.State) {
        states[appId] = s
        val (glyph, color) = when (s) {
            is Fleet.State.Installed       -> "✓" to cUp
            is Fleet.State.UpdateAvailable -> "⬆" to cUpd
            is Fleet.State.Missing         -> "◯" to cMiss
            is Fleet.State.Blocked         -> "⛔" to cBlk
            is Fleet.State.Error           -> "⚠" to cErr
        }
        val size = if (s.bytes > 0) human(s.bytes) else ""
        dots[appId]?.let { it.text = glyph; it.setTextColor(color) }

        // Collapsed line: only what you scan by. Version and size stay; the sha
        // and the remote digest are inspection, so they live in the detail.
        statusViews[appId]?.let { tv ->
            tv.setTextColor(color)
            tv.text = listOf(when (s) {
                is Fleet.State.Installed       -> "v${s.versionName}"
                is Fleet.State.UpdateAvailable -> "v${s.versionName ?: "—"} → new"
                is Fleet.State.Missing         -> "not installed"
                is Fleet.State.Blocked         -> "not published"
                is Fleet.State.Error           -> s.message
            }, size).filter { it.isNotEmpty() }.joinToString("  ·  ")
        }

        fullStatusViews[appId]?.let { tv ->
            tv.setTextColor(color)
            val sz = if (size.isEmpty()) "" else "  ·  $size"
            tv.text = when (s) {
                is Fleet.State.Installed       -> "✓ up to date  ·  v${s.versionName} (${s.versionCode})  ·  sha ${s.sha12}$sz"
                is Fleet.State.UpdateAvailable -> "⬆ update available  ·  installed v${s.versionName ?: "—"} → ${s.remoteDigest12}$sz"
                is Fleet.State.Missing         -> "◯ not installed  ·  tap Install$sz"
                is Fleet.State.Blocked         -> "⛔ not published yet"
                is Fleet.State.Error           -> "⚠ ${s.message}"
            }
        }

        quickBtns[appId]?.let { b ->
            when (s) {
                is Fleet.State.UpdateAvailable -> { b.visibility = View.VISIBLE; b.text = "⬆"; b.setBackgroundColor(cUpd) }
                is Fleet.State.Missing         -> { b.visibility = View.VISIBLE; b.text = "⬇"; b.setBackgroundColor(0xFF2B6CB0.toInt()) }
                else                           -> b.visibility = View.GONE
            }
        }

        val installed = s is Fleet.State.Installed
        installBtns[appId]?.let {
            it.setBackgroundColor(if (installed) 0xFF4A4A55.toInt() else 0xFF7C3AED.toInt())
            it.isClickable = !installed
        }
    }

    /** Bytes as MB/KB. Decimal MB, matching what GitHub and the Play Store show
     *  for the same APK - a binary-MiB figure here would read as a mismatch. */
    private fun human(b: Long): String =
        if (b >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", b / 1_000_000.0)
        else String.format(java.util.Locale.US, "%.0f KB", b / 1000.0)

    private fun install(ctx: Context, app: Fleet.App) {
        Toast.makeText(ctx, "Installing ${app.label}…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-install-${app.id}") {
            com.diegonmarcos.superapp.updater.UpdateProgress.beginDownload()
            try { Fleet.install(ctx, app) }
            catch (t: Throwable) { view?.post { Toast.makeText(ctx, "${app.label}: ${t.message}", Toast.LENGTH_LONG).show() } }
            val st = Fleet.status(ctx, app)
            body.post { paint(app.id, st); updateSummary(current()) }
        }
    }

    // "Update all" — only apps ALREADY installed that have a newer image.
    private fun updateAll(ctx: Context) {
        Toast.makeText(ctx, "Updating installed apps…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-update-all") {
            val n = Fleet.installAll(ctx, current(), Fleet.Mode.UPDATES)
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
            val n = Fleet.installAll(ctx, current(), Fleet.Mode.MISSING)
            view?.post {
                Toast.makeText(ctx, if (n == 0) "All apps already installed" else "$n install(s) queued",
                    Toast.LENGTH_LONG).show()
            }
            checkAll(ctx)
        }
    }

    // ── Perms tab ────────────────────────────────────────────────────────────
    // The constellation is a trusted environment because every APK is signed
    // with the SAME key. That is what makes signature-level permissions usable
    // between our apps: an app exposes a ContentProvider guarded by a
    // `signature` permission, and only same-key packages can bind/read it.
    //
    // Each app card carries two panes:
    //
    //   Android Perms — the platform's own runtime grants (camera, location,
    //     contacts…). Listed read-only, because only the system UI may change
    //     them; "System settings ↗" hands off to exactly that screen.
    //
    //   Cloud Perms — CONSTELLATION_DATA, declared in libs:core (shared by
    //     reference into every app, so it merges into all their manifests) at
    //     protectionLevel="signature". Android grants it at install to every
    //     APK carrying our key and refuses it to everyone else, so "all apps
    //     talk freely to each other" is the DEFAULT, enforced by the OS.
    //
    // Neither pane renders a toggle, and that is the point: the Android grants
    // aren't ours to flip, and the Cloud grant is already on by construction.
    // A switch here could only misreport state it doesn't control.
    private fun renderPerms(ctx: Context) {
        body.addView(caption(ctx,
            "One signing key across the constellation = signature-level trust. Each app " +
            "opens on two panes: Android Perms (the OS's own runtime grants — read-only " +
            "here, the system screen owns them) and Cloud Perms (our constellation " +
            "permission, granted automatically to every app carrying the Cloud key, so " +
            "they talk freely to each other by default)."))

        val me = ctx.packageName
        for (app in fleet) {
            if (app.pkg == me) continue
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1C1C24.toInt())
                val ph = dp(ctx, 12); val pv = dp(ctx, 8)
                setPadding(ph, pv, ph, pv)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 4)); layoutParams = lp
            }
            val pkg = Fleet.installedId(ctx, app)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(ctx).apply {
                text = app.label + (if (app.kind == "lib") "  ·  lib" else "")
                textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(row)
            card.addView(mono(ctx, pkg ?: app.pkg))

            val trust = TextView(ctx).apply {
                textSize = 12f; setPadding(0, dp(ctx, 3), 0, dp(ctx, 2))
            }
            when {
                pkg == null -> { trust.setTextColor(cMiss); trust.text = "◯ not installed" }
                sameSignature(ctx, pkg) -> { trust.setTextColor(cUp); trust.text = "🔑 same key  ·  eligible for signature-level data access" }
                else -> { trust.setTextColor(cBlk); trust.text = "⚠ different signature  ·  NOT eligible — reinstall from our release" }
            }
            card.addView(trust)

            if (pkg != null) {
                // Per-app sub-tabs: these are two genuinely different systems, so
                // they get separate panes instead of one mixed list. Android Perms
                // = the OS's own runtime grants, which only the system UI can
                // change. Cloud Perms = our constellation permission, which needs
                // no control at all because it is granted by signature. Keyed by
                // package so each card remembers which pane was open.
                val sub = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val tabs = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
                }
                val chips = ArrayList<TextView>()
                fun paint() {
                    val sel = permTab[pkg] ?: 0
                    chips.forEachIndexed { i, c ->
                        c.setBackgroundColor(if (i == sel) 0xFF7C3AED.toInt() else 0xFF2A2A33.toInt())
                    }
                    sub.removeAllViews()
                    if (sel == 0) renderAndroidPerms(ctx, sub, pkg) else renderCloudPerms(ctx, sub, pkg)
                }
                listOf("Android Perms", "Cloud Perms").forEachIndexed { i, label ->
                    val c = TextView(ctx).apply {
                        text = label
                        textSize = 12f; gravity = Gravity.CENTER
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { setMargins(if (i == 0) 0 else dp(ctx, 4), 0, 0, 0) }
                        setOnClickListener { permTab[pkg] = i; paint() }
                    }
                    chips.add(c); tabs.addView(c)
                }
                card.addView(tabs)
                card.addView(sub)
                paint()
            }
            body.addView(card)
        }
    }

    /** Android's own runtime permissions for [pkg]. Read-only by design: only
     *  the system UI may change these, so we list what the package requests and
     *  whether it currently holds it, then hand off to the system screen. */
    private fun renderAndroidPerms(ctx: Context, into: LinearLayout, pkg: String) {
        val pm = ctx.packageManager
        val requested = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
            // Our constellation permission lives in the other pane; here we show
            // the platform's own, which is what the system screen can act on.
            .filter { it.startsWith("android.permission.") }
            .sorted()

        if (requested.isEmpty()) {
            into.addView(caption(ctx, "Requests no Android permissions."))
        } else {
            for (p in requested) {
                val granted = pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
                into.addView(TextView(ctx).apply {
                    text = (if (granted) "✓  " else "·  ") + p.removePrefix("android.permission.")
                    textSize = 11f
                    setTextColor(if (granted) cUp else cMiss)
                    setPadding(0, dp(ctx, 1), 0, dp(ctx, 1))
                })
            }
        }
        into.addView(buttonRow(ctx, btn(ctx, "System settings ↗", 0xFF2A2A33.toInt()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", pkg, null)))
            }.onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
        }))
    }

    /** The constellation's own permission. There is deliberately no switch here:
     *  CONSTELLATION_DATA is protectionLevel="signature", so Android grants it at
     *  install time to every APK carrying our signing key and refuses it to every
     *  other APK. "All apps talk freely to each other" is therefore the DEFAULT
     *  state, enforced by the OS itself — a toggle could only lie about it.
     *  Declared once in libs:core, which every app now shares by reference, so it
     *  manifest-merges into all of them. */
    private fun renderCloudPerms(ctx: Context, into: LinearLayout, pkg: String) {
        val pm = ctx.packageManager
        val holds = pm.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED
        val weHold = pm.checkPermission(CONSTELLATION_PERM, ctx.packageName) == PackageManager.PERMISSION_GRANTED

        into.addView(TextView(ctx).apply {
            text = if (holds) "✓  Cloud data access — granted"
                   else "✕  Cloud data access — not granted"
            textSize = 13f
            setTextColor(if (holds) cUp else cBlk)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        })
        into.addView(mono(ctx, CONSTELLATION_PERM))
        into.addView(caption(ctx, when {
            holds && weHold ->
                "Two-way: this app and SuperApp can each read the other's constellation data. " +
                "Granted automatically at install because both carry the Cloud signing key — " +
                "no prompt, and no outside APK can obtain it."
            holds ->
                "This app holds it but SuperApp does not — reinstall SuperApp from our release."
            sameSignature(ctx, pkg) ->
                "Same signing key, but this build predates the constellation permission. " +
                "Update it from the Apps tab; the grant lands on reinstall."
            else ->
                "Signed with a different key, so Android refuses this permission. " +
                "Reinstall from our release to bring it into the constellation."
        }))
    }

    /** True when [pkg] is signed with the same key as us — the whole basis of
     *  `signature`-level permissions inside the constellation. */
    @Suppress("DEPRECATION")
    private fun sameSignature(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

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
