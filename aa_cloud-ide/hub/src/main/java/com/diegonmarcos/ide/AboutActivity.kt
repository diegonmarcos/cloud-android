package com.diegonmarcos.ide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.ide.update.UpdateProgress
import com.diegonmarcos.ide.update.Updater
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Configs → About — the FULL Cloud-SuperApp About architecture (DevControl
 * "About" page), ported via its ea_cloud-comms sibling: same sections, same
 * helper framework (title / section / row / small / actionButton / fmtMillis /
 * sizeStr), same monospace-value style, long-press-to-copy on every row, and
 * "Copy All Infos" at the tail.
 *
 * Sections: About · Updater/GHCR · APK · Storage · IPC contract · Wrapper
 * bundle (the two self-contained apps) · Stack (languages LOC + frameworks +
 * build metrics — scanned at build time, never hardcoded) · Device · Memory ·
 * System load · Permissions · Updates (live progress). Every fact comes from
 * BuildConfig (baked from build.json/contract/data) or runtime PackageManager /
 * ActivityManager / proc — no hardcoded facts.
 */
class AboutActivity : AppCompatActivity() {

    /** Mirrors everything written via title/section/row/small so "Copy All
     *  Infos" can dump the entire page as text. */
    private var infoBuf = StringBuilder()

    private lateinit var updateProgress: ProgressBar
    private lateinit var updateStatus: TextView

    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            Toast.makeText(this,
                if (it) "Notifications: granted" else "Notifications: denied",
                Toast.LENGTH_SHORT).show()
            recreate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.about_title)
        infoBuf = StringBuilder()

        val ctx = this
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)

        column.addView(title(ctx, getString(R.string.about_heading)))

        // ── About ──────────────────────────────────────────────────────
        section(ctx, column, "About") {
            row(ctx, it, "Name",         BuildConfig.APPLICATION_ID)
            row(ctx, it, "Version",      BuildConfig.VERSION_NAME)
            row(ctx, it, "Version code", BuildConfig.VERSION_CODE.toString())
            row(ctx, it, "Git sha",      BuildConfig.GIT_SHORT_SHA)
            row(ctx, it, "Built (UTC)",  BuildConfig.BUILD_TIMESTAMP)
            row(ctx, it, "Build type",   BuildConfig.BUILD_TYPE)
            row(ctx, it, "Debuggable",   BuildConfig.DEBUG.toString())
        }

        // ── Updater / GHCR ─────────────────────────────────────────────
        section(ctx, column, "Updater") {
            row(ctx, it, "Registry",  BuildConfig.GHCR_REGISTRY)
            row(ctx, it, "Namespace", BuildConfig.GHCR_NAMESPACE)
            row(ctx, it, "Image",     BuildConfig.GHCR_IMAGE)
            row(ctx, it, "Tag",       BuildConfig.AUTO_UPDATE_TAG)
            row(ctx, it, "Full URL",
                "${BuildConfig.GHCR_REGISTRY}/${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}:${BuildConfig.AUTO_UPDATE_TAG}")
            row(ctx, it, "Media type",     BuildConfig.GHCR_MEDIA_TYPE)
            row(ctx, it, "Check interval", "${BuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h")
            row(ctx, it, "Enabled",        BuildConfig.AUTO_UPDATE_ENABLED.toString())
            row(ctx, it, "Install mode",   BuildConfig.AU_INSTALL_MODE)
            row(ctx, it, "Require unmetered", BuildConfig.AU_REQUIRE_UNMETERED.toString())
            row(ctx, it, "Require charging",  BuildConfig.AU_REQUIRE_CHARGING.toString())
        }

        // ── APK ────────────────────────────────────────────────────────
        section(ctx, column, "APK") {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            val path = info.applicationInfo?.sourceDir ?: "—"
            val size = runCatching { java.io.File(path).length() }.getOrDefault(0L)
            row(ctx, it, "Path",       path)
            row(ctx, it, "Size",       sizeStr(size))
            row(ctx, it, "Installed",  fmtMillis(info.firstInstallTime))
            row(ctx, it, "Updated",    fmtMillis(info.lastUpdateTime))
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val src = runCatching { packageManager.getInstallSourceInfo(packageName) }.getOrNull()
                row(ctx, it, "Installed by", src?.installingPackageName ?: "—")
            } else {
                @Suppress("DEPRECATION")
                row(ctx, it, "Installed by",
                    runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull() ?: "—")
            }
        }

        // ── Storage ────────────────────────────────────────────────────
        section(ctx, column, "Storage") {
            @Suppress("DEPRECATION")
            val pkg = packageManager.getPackageInfo(packageName, 0)
            val appInfo    = pkg.applicationInfo
            val apkBytes   = runCatching { java.io.File(appInfo?.sourceDir ?: "").length() }.getOrDefault(0L)
            val dataDir    = appInfo?.dataDir?.let { d -> java.io.File(d) } ?: dataDir
            val cacheBytes = dirSize(cacheDir)
            val dataBytes  = (dirSize(dataDir) - cacheBytes).coerceAtLeast(0L)
            val totalBytes = apkBytes + dataBytes + cacheBytes

            row(ctx, it, "Files dir",  filesDir.absolutePath)
            row(ctx, it, "Cache dir",  cacheDir.absolutePath)
            row(ctx, it, "Data root",  dataDir.absolutePath)
            row(ctx, it, "External",   getExternalFilesDir(null)?.absolutePath ?: "—")
            it.addView(small(ctx, "Breakdown — same buckets Android system settings shows:"))
            row(ctx, it, "Aplicación", sizeStr(apkBytes))
            row(ctx, it, "Datos",      sizeStr(dataBytes))
            row(ctx, it, "Caché",      sizeStr(cacheBytes))
            row(ctx, it, "Total",      sizeStr(totalBytes))
        }

        // ── IPC contract ───────────────────────────────────────────────
        section(ctx, column, "IPC contract") {
            row(ctx, it, "Contract",    "v${BuildConfig.IPC_VERSION}")
            row(ctx, it, "Authority",   BuildConfig.IPC_AUTHORITY)
            row(ctx, it, "Permission",  BuildConfig.IPC_PERMISSION)
            row(ctx, it, "Open action", BuildConfig.OPEN_FORK_ACTION)
            row(ctx, it, "Icon model",  BuildConfig.ICON_MODEL)
            it.addView(small(ctx, "Signature-gated — all constellation APKs share one signing key."))
        }

        // ── Wrapper bundle — the two self-contained apps ───────────────
        section(ctx, column, "Wrapper bundle") {
            it.addView(small(ctx, "The REAL upstream APKs ride INSIDE this APK (assets/forks/, sha256-pinned at build)."))
            row(ctx, it, "Cloud-IDE (hub)", "v${BuildConfig.VERSION_NAME}")
            row(ctx, it, "  image", "${BuildConfig.GHCR_NAMESPACE}/${BuildConfig.GHCR_IMAGE}")
            val forksJson = runCatching {
                JSONObject(String(Base64.decode(BuildConfig.FORKS_JSON_B64, Base64.DEFAULT)))
            }.getOrDefault(JSONObject())
            for (f in ForkRegistry.homeForks) {
                val installedVer = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(f.launchPackage, 0).versionName
                }.getOrNull()
                val state = when {
                    installedVer != null -> "v$installedVer · installed"
                    BundledForkInstaller.isBundled(ctx, f) -> "bundled · tap tile to install"
                    else -> "not bundled in this build"
                }
                row(ctx, it, f.displayName, state)
                forksJson.optJSONObject(f.domain)?.let { fj ->
                    val pin = fj.optString("pinned_tag").ifBlank { "—" }
                    row(ctx, it, "  license/runtime",
                        "${fj.optString("license").ifBlank { "—" }} · ${fj.optString("runtime").ifBlank { "—" }} · pin $pin")
                }
                row(ctx, it, "  bundled?", if (BundledForkInstaller.isBundled(ctx, f)) "yes" else "no")
                row(ctx, it, "  app id", f.launchPackage)
            }
        }

        // ── Stack ──────────────────────────────────────────────────────
        section(ctx, column, "Stack") {
            it.addView(small(ctx, "What's actually inside this APK — scanned by the build, never a hardcoded list."))
            val langs = runCatching {
                JSONObject(String(Base64.decode(BuildConfig.STACK_LANGUAGES_JSON_B64, Base64.DEFAULT)))
            }.getOrDefault(JSONObject())
            var totalFiles = 0; var totalLoc = 0
            val langList = mutableListOf<Triple<String, Int, Int>>()
            val keys = langs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = langs.optJSONObject(k) ?: continue
                val files = o.optInt("files"); val loc = o.optInt("loc")
                totalFiles += files; totalLoc += loc
                langList += Triple(k, files, loc)
            }
            row(ctx, it, "Total LOC",   "%,d lines".format(totalLoc))
            row(ctx, it, "Total files", totalFiles.toString())
            for ((lang, files, loc) in langList.sortedByDescending { tr -> tr.third }) {
                val pct = if (totalLoc > 0) " (%d%%)".format(loc * 100 / totalLoc) else ""
                row(ctx, it, lang, "%,d lines · %d file(s)%s".format(loc, files, pct))
            }

            it.addView(small(ctx, "Frameworks (Maven coordinates scanned from every *.gradle):"))
            val fw = runCatching {
                JSONArray(String(Base64.decode(BuildConfig.STACK_FRAMEWORKS_JSON_B64, Base64.DEFAULT)))
            }.getOrDefault(JSONArray())
            row(ctx, it, "Dep count", fw.length().toString())
            for (i in 0 until fw.length()) {
                val dep = fw.getString(i)
                val parts = dep.split(":")
                val head = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else dep
                val ver  = if (parts.size >= 3) parts[2] else "?"
                row(ctx, it, head, ver)
            }

            it.addView(small(ctx, "Build metrics (from GitHub Actions history):"))
            row(ctx, it, "Build avg time", fmtSecs(BuildConfig.STACK_BUILD_AVG_SECS) +
                if (BuildConfig.STACK_BUILD_SAMPLE > 0) " (n=${BuildConfig.STACK_BUILD_SAMPLE})" else "")
            row(ctx, it, "Build last",     fmtSecs(BuildConfig.STACK_BUILD_LAST_SECS))
            row(ctx, it, "Build SHA",      BuildConfig.GIT_SHORT_SHA)
        }

        // ── Device / stack ─────────────────────────────────────────────
        section(ctx, column, "Device / stack") {
            row(ctx, it, "Manufacturer", android.os.Build.MANUFACTURER)
            row(ctx, it, "Model",        android.os.Build.MODEL)
            row(ctx, it, "Brand",        android.os.Build.BRAND)
            row(ctx, it, "Device",       android.os.Build.DEVICE)
            row(ctx, it, "Hardware",     android.os.Build.HARDWARE)
            row(ctx, it, "Android",      "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            row(ctx, it, "ABIs",         android.os.Build.SUPPORTED_ABIS.joinToString(", "))
            row(ctx, it, "Locale",       java.util.Locale.getDefault().toLanguageTag())
        }

        // ── Memory ─────────────────────────────────────────────────────
        section(ctx, column, "Memory") {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            row(ctx, it, "System avail",      sizeStr(mi.availMem))
            row(ctx, it, "System total",      sizeStr(mi.totalMem))
            row(ctx, it, "Threshold",         sizeStr(mi.threshold))
            row(ctx, it, "System low-memory", mi.lowMemory.toString())
            row(ctx, it, "Heap class limit",  am?.memoryClass?.let { c -> "$c MB" } ?: "—")
            row(ctx, it, "Heap class (large)", am?.largeMemoryClass?.let { c -> "$c MB" } ?: "—")
            val rt = Runtime.getRuntime()
            row(ctx, it, "JVM heap used",
                "${sizeStr(rt.totalMemory() - rt.freeMemory())} / ${sizeStr(rt.maxMemory())}")
        }

        // ── System load ────────────────────────────────────────────────
        section(ctx, column, "System load") {
            row(ctx, it, "CPU", "${Runtime.getRuntime().availableProcessors()} cores · ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "—"}")
            readLoadAvg()?.let { la -> row(ctx, it, "Load (1·5·15m)", la) }
            readCpuBusyIdle()?.let { bi -> row(ctx, it, "Busy / idle", bi) }
            row(ctx, it, "Process uptime", readUptime())
        }

        // ── Permissions ────────────────────────────────────────────────
        section(ctx, column, "Permissions") {
            it.addView(small(ctx, "Manifest permissions — ✓ Granted · ✗ Denied. Normal/signature perms auto-grant at install; runtime perms need a prompt."))
            val perms = listOf(
                "INTERNET"           to "android.permission.INTERNET",
                "Network state"      to "android.permission.ACCESS_NETWORK_STATE",
                "Install packages"   to "android.permission.REQUEST_INSTALL_PACKAGES",
                "Post notifications" to "android.permission.POST_NOTIFICATIONS",
                "IPC (signature)"    to BuildConfig.IPC_PERMISSION,
            )
            for ((label, perm) in perms) {
                row(ctx, it, label, permissionState(perm))
            }
            // Display-over-other-apps (the wrapper bar) — special check, not
            // a runtime permission.
            row(ctx, it, "Overlay (nav bar)",
                if (NavOverlayService.hasPermission(ctx)) "✓ Granted" else "✗ Denied")
            if (!NavOverlayService.hasPermission(ctx)) {
                it.addView(actionButton(ctx, "Grant overlay (persistent nav bar)") {
                    NavOverlayService.requestPermission(ctx)
                })
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                it.addView(actionButton(ctx, "Request notifications") {
                    notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
                })
            } else {
                it.addView(small(ctx, "Pre-API 33 — notifications are granted by default."))
            }
            it.addView(actionButton(ctx, "Open System App Settings") { openAppSettings() })
        }

        // ── Updates ────────────────────────────────────────────────────
        section(ctx, column, "Updates") {
            updateProgress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; visibility = View.GONE
            }
            it.addView(updateProgress)
            updateStatus = TextView(ctx).apply {
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(6), 0, dp(8))
            }
            it.addView(updateStatus)
            it.addView(actionButton(ctx, getString(R.string.about_check_updates)) {
                Updater.checkNow(ctx)
                updateStatus.text = getString(R.string.about_update_started)
            })
        }

        // ── Diagnostics (download / push logs to OpenObserve, the Loki-equiv) ──
        section(ctx, column, "Diagnostics") {
            it.addView(small(ctx, "Capture this app's logcat + device info. Download saves a JSON bundle to Downloads; Send posts it to the cloud log store (OpenObserve)."))
            it.addView(actionButton(ctx, "Download logs") {
                Thread {
                    val name = "cloud-diag-${BuildConfig.APPLICATION_ID}-${BuildConfig.GIT_SHORT_SHA}.json"
                    val saved = DiagnosticsPush.downloadBundle(ctx, name, DiagnosticsPush.captureRecord(ctx))
                    runOnUiThread { Toast.makeText(ctx, if (saved != null) "Saved $saved to Downloads" else "Download failed", Toast.LENGTH_LONG).show() }
                }.start()
            })
            it.addView(actionButton(ctx, "Send logs → cloud") {
                Thread {
                    val code = DiagnosticsPush.pushToCloud(DiagnosticsPush.captureRecord(ctx))
                    val msg = when { code in 200..299 -> "Sent to cloud ($code)"; code == -2 -> "Cloud sink not configured (build.json diagnostics.log_sink_url)"; else -> "Send failed ($code)" }
                    runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                }.start()
            })
        }

        // ── Copy All Infos ─────────────────────────────────────────────
        column.addView(actionButton(ctx, "Copy All Infos") {
            val snapshot = infoBuf.toString()
            copy(ctx, snapshot)
            Toast.makeText(ctx,
                "Copied ${snapshot.length} chars (${snapshot.count { c -> c == '\n' }} lines)",
                Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        UpdateProgress.setListener { s -> runOnUiThread { renderProgress(s) } }
    }

    override fun onPause() {
        super.onPause()
        UpdateProgress.setListener(null)
    }

    private fun renderProgress(s: UpdateProgress.State) {
        if (!::updateProgress.isInitialized) return
        when (s) {
            is UpdateProgress.State.Idle -> {
                updateProgress.visibility = View.GONE; updateStatus.text = ""
            }
            is UpdateProgress.State.CheckingManifest -> {
                updateProgress.visibility = View.VISIBLE; updateProgress.isIndeterminate = true
                updateStatus.text = "Checking GHCR manifest…"
            }
            is UpdateProgress.State.Downloading -> {
                updateProgress.visibility = View.VISIBLE; updateProgress.isIndeterminate = false
                updateProgress.progress = s.percent
                updateStatus.text = "Downloading: ${s.percent}%"
            }
            is UpdateProgress.State.Installing -> {
                updateProgress.visibility = View.VISIBLE; updateProgress.isIndeterminate = true
                updateStatus.text = "Installing…"
            }
            is UpdateProgress.State.UpToDate -> {
                updateProgress.visibility = View.GONE
                updateStatus.text = "Up to date (${s.tag})."
            }
            is UpdateProgress.State.Done -> {
                updateProgress.visibility = View.GONE
                updateStatus.text = "Update complete."
            }
            is UpdateProgress.State.Failed -> {
                updateProgress.visibility = View.GONE
                updateStatus.text = "Failed: ${s.message}"
            }
        }
    }

    // ── helpers (ported from DevControlFragment via comms) ─────────────

    private fun section(ctx: Context, host: LinearLayout, head: String, body: (LinearLayout) -> Unit) {
        infoBuf.append("\n## ").append(head).append("\n")
        host.addView(sectionHeader(ctx, head))
        val grp = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        host.addView(grp)
        body(grp)
    }

    private fun row(ctx: Context, host: LinearLayout, key: String, value: String): TextView {
        infoBuf.append("  ").append(key).append(": ").append(value).append("\n")
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        rowView.addView(TextView(ctx).apply {
            text = key
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            layoutParams = LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val valueView = TextView(ctx).apply {
            text = value
            setTextColor(0xFFB794F4.toInt())
            typeface = Typeface.MONOSPACE
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnLongClickListener {
                copy(ctx, "$key: $value")
                Toast.makeText(ctx, "Copied $key", Toast.LENGTH_SHORT).show(); true
            }
        }
        rowView.addView(valueView)
        host.addView(rowView)
        return valueView
    }

    private fun title(ctx: Context, text: String) = TextView(ctx).apply {
        infoBuf.append("# ").append(text).append("\n")
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(12))
    }

    private fun sectionHeader(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFF7C3AED.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun small(ctx: Context, text: String) = TextView(ctx).apply {
        infoBuf.append("  ").append(text).append("\n")
        this.text = text
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF7C3AED.toInt())
        gravity = android.view.Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun copy(ctx: Context, v: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("about", v))
    }

    private fun permissionState(perm: String): String {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, perm) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) "✓ Granted" else "✗ Denied"
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null),
            ))
        }
    }

    private fun fmtMillis(ms: Long): String =
        DateFormat.getDateTimeInstance().format(Date(ms))

    private fun dirSize(dir: java.io.File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }
    }.getOrDefault(0L)

    private fun readLoadAvg(): String? = try {
        val parts = java.io.File("/proc/loadavg").readText().trim().split(' ')
        if (parts.size >= 3) "${parts[0]} · ${parts[1]} · ${parts[2]}" else null
    } catch (_: Throwable) { null }

    private fun readCpuBusyIdle(): String? {
        return try {
            val parts = java.io.File("/proc/uptime").readText().trim().split(' ').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val uptime = parts[0].toDouble(); val idle = parts[1].toDouble()
            val cores = Runtime.getRuntime().availableProcessors()
            val busy = (uptime * cores - idle).coerceAtLeast(0.0)
            val frac = if (busy + idle > 0) busy / (busy + idle) else 0.0
            "busy ${fmtSecs((busy).toLong())} · idle ${fmtSecs(idle.toLong())} · %.1f %%".format(frac * 100.0)
        } catch (_: Throwable) { null }
    }

    private fun readUptime(): String {
        val ms = (android.os.SystemClock.elapsedRealtime() - AppProcessUptime.startedAtElapsed).coerceAtLeast(0L)
        return fmtSecs(ms / 1000)
    }

    private fun fmtSecs(s: Long): String = when {
        s < 0    -> "—"
        s < 60   -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else     -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
    }

    private fun sizeStr(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1024          -> "%.1f KiB".format(bytes / 1024.0)
        else                   -> "$bytes B"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
