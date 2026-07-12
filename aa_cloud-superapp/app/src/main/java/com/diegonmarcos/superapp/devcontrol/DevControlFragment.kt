package com.diegonmarcos.superapp.devcontrol
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.battery.EnergyUsageDialog
import com.diegonmarcos.superapp.battery.EnergyLedger
import com.diegonmarcos.superapp.battery.BatterySessionStats
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup
import com.diegonmarcos.superapp.battery.BatteryChargerSpec
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.profile.ProfilePrefs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.updater.BuildConfig as UpdBuildConfig
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/**
 * About page — comprehensive runtime + build metadata for the user
 * to inspect. Long-press any monospace row to copy to clipboard.
 */
class DevControlFragment : Fragment() {

    /** Accumulator that mirrors everything written to the UI by
     *  [title] / [section] / [row] + the inline folder-tree block, so
     *  the "Copy All Infos" button at the bottom can dump the whole
     *  page to the clipboard as plain text. Reset at the start of
     *  every onCreateView so it stays in sync after a reattach. */
    private var infoBuf = StringBuilder()

    /** Rebuilds this fragment in place so every Permission row re-reads
     *  its current grant state. Cheaper than rendering a full diff. */
    private fun rebuildFragment() {
        parentFragmentManager.beginTransaction()
            .detach(this).commitNow()
        parentFragmentManager.beginTransaction()
            .attach(this).commitNow()
    }

    private fun ctxAny(): Context = requireContext()

    /** Deep-link to the OS's per-app settings page — the user can then
     *  drill down to Battery via the visible row. Android doesn't expose
     *  a public "open battery details for this app" intent (the
     *  Settings.ACTION_BATTERY_* surface is for saver / optimisation
     *  settings only), so the app-details deeplink is the closest public
     *  affordance across every API level. */
    private fun openBatteryDetails() {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", requireContext().packageName, null),
                )
            )
        }
    }

    /** Open Developer options (where Wireless Debugging lives) so the user can
     *  enable it and read the pairing/connect ports for the self-contained
     *  embedded-adb channel (libs:shizuku-adb-debug-tools). There is no public
     *  direct action for the Wireless-Debugging sub-screen, so we jump to
     *  Developer options; falls back to the top-level Settings if an OEM
     *  hides/locks the dev-settings action. */
    private fun openWirelessDebuggingSettings() {
        val dev = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )
        if (dev.resolveActivity(requireContext().packageManager) != null) {
            runCatching { startActivity(dev) }
        } else {
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    /** Read per-app foreground + background time from UsageStatsManager.
     *  Returns (foregroundMs, backgroundMs); both -1 if the user hasn't
     *  granted PACKAGE_USAGE_STATS. Window = last 7 days. */
    private fun readUsageStats(ctx: Context): Pair<Long, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return -1L to -1L
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val stats = runCatching {
            usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_WEEKLY,
                weekAgo, now,
            )
        }.getOrNull() ?: return -1L to -1L
        if (stats.isEmpty()) return -1L to -1L
        val me = stats.firstOrNull { it.packageName == ctx.packageName }
            ?: return 0L to 0L
        val fg = me.totalTimeInForeground
        // backgroundTime() is only on API 29+; older versions report 0.
        val bg = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val m = me.javaClass.getMethod("getTotalTimeForegroundServiceUsed")
                (m.invoke(me) as? Long) ?: 0L
            }.getOrDefault(0L)
        } else 0L
        return fg to bg
    }

    private fun fmtDuration(ms: Long): String {
        if (ms < 0) return "—"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d s".format(m, sec)
            else  -> "%d s".format(sec)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        infoBuf = StringBuilder()
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)

        column.addView(title(ctx, "About Cloud SuperApp"))

        // ══ CLOUD macro section — who/what this device is in the cloud
        //    mesh: the owner profile + repos + consolidated config, and
        //    the live WireGuard/mesh reachability.
        column.addView(macroHeader(ctx, "☁  CLOUD"))

        section(ctx, column, "Profile") {
            val prof = ProfilePrefs(ctxAny())
            row(ctx, it, "Name",     prof.name.ifBlank { BuildConfig.UI_PROFILE_NAME })
            row(ctx, it, "Email",    prof.email.ifBlank { BuildConfig.UI_PROFILE_EMAIL })
            row(ctx, it, "Company",  prof.company.ifBlank { BuildConfig.UI_PROFILE_COMPANY })
            row(ctx, it, "Location", prof.location.ifBlank { BuildConfig.UI_PROFILE_LOCATION })
            val site = prof.website.ifBlank { BuildConfig.UI_PROFILE_WEBSITE }
            if (site.isNotBlank()) row(ctx, it, "Website", site)
            // Repos — data-driven from build.json::ui.profile_default.repos.
            it.addView(small(ctx, "Repos:"))
            for ((label, url) in parseProfileRepos()) row(ctx, it, label, url)
            // The consolidated cloud-data config that feeds the mesh.
            it.addView(small(ctx, "Config source:"))
            row(ctx, it, "Consolidated", BuildConfig.UI_CONSOLIDATED_CONFIG.ifBlank { "—" })
        }

        section(ctx, column, "VPN / WireGuard / Mesh") { renderVpnMesh(ctx, it) }

        // ══ PHONE macro section — everything about THIS Android device:
        //    the app build, perms, battery, memory, network, etc.
        column.addView(macroHeader(ctx, "📱  PHONE"))

        section(ctx, column, "App") {
            row(ctx, it, "Name",         BuildConfig.APPLICATION_ID)
            row(ctx, it, "Version",      BuildConfig.VERSION_NAME)
            row(ctx, it, "Version code", BuildConfig.VERSION_CODE.toString())
            row(ctx, it, "Git sha",      BuildConfig.GIT_SHORT_SHA)
            row(ctx, it, "Built (UTC)",  BuildConfig.BUILD_TIMESTAMP)
            row(ctx, it, "Build type",   BuildConfig.BUILD_TYPE)
            row(ctx, it, "Debuggable",   BuildConfig.DEBUG.toString())
        }

        section(ctx, column, "Release / GHCR") {
            row(ctx, it, "Registry",  UpdBuildConfig.GHCR_REGISTRY)
            row(ctx, it, "Namespace", UpdBuildConfig.GHCR_NAMESPACE)
            row(ctx, it, "Image",     UpdBuildConfig.GHCR_IMAGE)
            row(ctx, it, "Tag",       UpdBuildConfig.AUTO_UPDATE_TAG)
            row(ctx, it, "Full URL",
                "${UpdBuildConfig.GHCR_REGISTRY}/${UpdBuildConfig.GHCR_NAMESPACE}/${UpdBuildConfig.GHCR_IMAGE}:${UpdBuildConfig.AUTO_UPDATE_TAG}")
            row(ctx, it, "Check interval", "${UpdBuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h")
        }

        section(ctx, column, "APK") {
            val pm = requireContext().packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(requireContext().packageName, 0)
            // PackageInfo.applicationInfo is @Nullable as of API 35.
            // Fall back to "—" so the dev-control row still renders.
            val path = info.applicationInfo?.sourceDir ?: "—"
            val size = runCatching { File(path).length() }.getOrDefault(0L)
            row(ctx, it, "Path",       path)
            row(ctx, it, "Size",       sizeStr(size))
            row(ctx, it, "Installed",  fmtMillis(info.firstInstallTime))
            row(ctx, it, "Updated",    fmtMillis(info.lastUpdateTime))
        }

        section(ctx, column, "Signing") {
            val pm = requireContext().packageManager
            val sigFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            val sigPkg = runCatching { pm.getPackageInfo(requireContext().packageName, sigFlags) }.getOrNull()
            val sigBytes = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    sigPkg?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                else @Suppress("DEPRECATION") sigPkg?.signatures?.firstOrNull()?.toByteArray()
            }.getOrNull()
            if (sigBytes != null) {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(sigBytes.inputStream()) as X509Certificate
                val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString(":") { "%02X".format(it) }
                row(ctx, it, "Subject",     cert.subjectDN.name)
                row(ctx, it, "SHA-256",     sha256)
                row(ctx, it, "Valid until", fmtMillis(cert.notAfter.time))
            } else {
                row(ctx, it, "Cert", "—")
            }
        }

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

        section(ctx, column, "Storage") {
            val ctxAny = requireContext()
            val pm = ctxAny.packageManager
            @Suppress("DEPRECATION")
            val pkg = pm.getPackageInfo(ctxAny.packageName, 0)
            // PackageInfo.applicationInfo is @Nullable as of API 35;
            // null-guard once, then degrade gracefully per field —
            // APK size to 0 (visible "—" in the row), dataDir to
            // Context.getDataDir() which is always non-null.
            val appInfo   = pkg.applicationInfo
            val apkBytes  = runCatching { File(appInfo?.sourceDir ?: "").length() }.getOrDefault(0L)
            // "Datos" = the app's private data root — includes filesDir,
            // databases, shared_prefs, and everything else the app
            // persists outside of cacheDir.
            val dataDir   = appInfo?.dataDir?.let { File(it) } ?: ctxAny.dataDir
            val cacheDir  = ctxAny.cacheDir
            val cacheBytes = dirSize(cacheDir)
            // dataDir contains cacheDir; subtract to get pure "data".
            val dataBytes  = (dirSize(dataDir) - cacheBytes).coerceAtLeast(0L)
            val totalBytes = apkBytes + dataBytes + cacheBytes

            // Paths first.
            row(ctx, it, "Files dir",   ctxAny.filesDir.absolutePath)
            row(ctx, it, "Cache dir",   cacheDir.absolutePath)
            row(ctx, it, "Data root",   dataDir.absolutePath)
            row(ctx, it, "External",    ctxAny.getExternalFilesDir(null)?.absolutePath ?: "—")
            row(ctx, it, "Trace log",   sizeStr(File(ctxAny.getExternalFilesDir(null), "trace/trace.log").length()))
            it.addView(small(ctx, "Breakdown — same buckets Android system settings shows:"))
            row(ctx, it, "Aplicación",  sizeStr(apkBytes))
            row(ctx, it, "Datos",       sizeStr(dataBytes))
            row(ctx, it, "Caché",       sizeStr(cacheBytes))
            row(ctx, it, "Total",       sizeStr(totalBytes))
        }

        // Permissions section moved to PermissionsFragment (config/perms tab).

        section(ctx, column, "Battery & Usage") {
            // Everything in this block is what the app CAN read about
            // itself without privileged permissions. Per-app battery mAh
            // + screen-on / background time split / wakelocks count etc.
            // live in BatteryStatsManager which is system-only. The
            // "Open battery usage details" button below jumps to the
            // OS screen the user pasted from for the full picture.
            val ctxAny = requireContext()
            val pkg = ctxAny.packageManager.getPackageInfo(ctxAny.packageName, 0)
            val myUid = ctxAny.applicationInfo.uid

            // App-side crash count — files in CrashLogger's private dir
            // (getExternalFilesDir/crashes/crash-<ts>.txt).
            val crashDir = File(ctxAny.getExternalFilesDir(null), "crashes")
            val crashes  = crashDir.listFiles { f -> f.name.startsWith("crash-") }?.size ?: 0
            val mostRecent = crashDir.listFiles { f -> f.name.startsWith("crash-") }
                ?.maxByOrNull { it.lastModified() }
            row(ctx, it, "Crash count",  crashes.toString())
            row(ctx, it, "Last crash",   mostRecent?.let { fmtMillis(it.lastModified()) } ?: "—")
            row(ctx, it, "Crashes dir",  crashDir.absolutePath)

            // Process uptime since this Application's onCreate.
            val uptimeMs = android.os.SystemClock.elapsedRealtime() -
                AppProcessUptime.startedAtElapsed
            row(ctx, it, "Process uptime", fmtDuration(uptimeMs))
            row(ctx, it, "First install",  fmtMillis(pkg.firstInstallTime))
            row(ctx, it, "Last update",    fmtMillis(pkg.lastUpdateTime))

            // Network bytes RX/TX since boot (TrafficStats is per-UID;
            // resets on reboot). Captures all sockets this UID has used.
            val rx = android.net.TrafficStats.getUidRxBytes(myUid)
            val tx = android.net.TrafficStats.getUidTxBytes(myUid)
            val rxMobile = android.net.TrafficStats.getMobileRxBytes()
            val txMobile = android.net.TrafficStats.getMobileTxBytes()
            row(ctx, it, "Net RX (all)",    sizeStr(if (rx < 0) 0L else rx))
            row(ctx, it, "Net TX (all)",    sizeStr(if (tx < 0) 0L else tx))
            row(ctx, it, "Mobile RX (UID)", if (rxMobile < 0) "—" else sizeStr(rxMobile))
            row(ctx, it, "Mobile TX (UID)", if (txMobile < 0) "—" else sizeStr(txMobile))

            // Per-app foreground / background screen time — needs
            // PACKAGE_USAGE_STATS (Settings.ACTION_USAGE_ACCESS_SETTINGS).
            val (fgMs, bgMs) = readUsageStats(ctxAny)
            row(ctx, it, "Screen-on",   if (fgMs < 0) "Needs Usage Access" else fmtDuration(fgMs))
            row(ctx, it, "Background",  if (bgMs < 0) "Needs Usage Access" else fmtDuration(bgMs))
            row(ctx, it, "Total used",  if (fgMs < 0 || bgMs < 0) "—" else fmtDuration(fgMs + bgMs))

            // ── Since-last-charge battery analytics ───────────────
            // Read current battery level + charging status, persist
            // the "last unplug" anchor in SharedPreferences, derive
            // the three user-requested rows.
            // Battery-session math (anchor lifecycle + rate + ETA) lives
            // in BatterySessionStats — same source the status-strip
            // BatteryEstimatePopup reads from when the user taps the
            // icon. Single calc, two surfaces.
            val bs = com.diegonmarcos.superapp.battery.BatterySessionStats.read(ctxAny)
            // Row labels auto-flip on charging state. Discharging: "Since
            // last charge / % battery/min consumed / Estimated battery last
            // / ETA battery drained". Charging: same shape, charge-flavoured
            // wording + ETA-to-full math. Same underlying snapshot fields,
            // unified formatters in BatterySessionStats decide the wording.
            val labelSince = if (bs.isCharging) "Since plugged in"        else "Since last charge"
            val labelRate  = if (bs.isCharging) "% battery/h gained"      else "% battery/h consumed"
            // Honest rename — this is BATTERY storage (V × I into the
            // cell), NOT the charger input. Android doesn't expose the
            // charger live input via public API; the "Charger input"
            // row below shows the dumpsys-reported max negotiated spec.
            val labelPower = if (bs.isCharging) "Battery storage (live)" else "Battery drain (live)"
            val labelEta   = if (bs.isCharging) "Estimated time to full"  else "Estimated battery last"
            val labelWall  = if (bs.isCharging) "ETA full charge"         else "ETA battery drained"
            row(ctx, it, labelSince,
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtSinceAnchor(bs))
            row(ctx, it, labelRate,
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtRateUnified(bs))
            row(ctx, it, labelPower,
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtPowerRow(bs))
            if (bs.isCharging) {
                row(ctx, it, "Charger input",
                    com.diegonmarcos.superapp.battery.BatterySessionStats.fmtChargerSpec(bs))
                val phone = com.diegonmarcos.superapp.battery.BatterySessionStats.fmtPhoneConsumption(bs)
                if (phone.isNotEmpty()) {
                    row(ctx, it, "Phone consumption", "$phone  (charger − battery)")
                }
            }
            row(ctx, it, labelEta,
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtEtaDuration(bs))
            row(ctx, it, labelWall,
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtEtaWallClock(bs))
            // BatteryManager / sticky-intent surface — works on hardened
            // Samsung where /sys/class/power_supply/* is SELinux-blocked.
            row(ctx, it, "Battery temp",
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtBatteryTemp(bs))
            row(ctx, it, "Cycle count",
                com.diegonmarcos.superapp.battery.BatterySessionStats.fmtCycleCount(bs))
            // Debug rows so the user can see WHICH path the power
            // figure came from + the anchor provenance.
            row(ctx, it, "Power source",      bs.powerWSource)
            row(ctx, it, "Unplug anchor src", bs.unplugAnchorSource)
            row(ctx, it, "Plug anchor src",   bs.plugAnchorSource)
            it.addView(small(ctx, "Battery-stats internals (mAh per-component, wakelocks, wakeups) are system-only. Grant Usage Access + Set Battery No Optimization shortcuts live in the Permissions section above."))
            // Deep BatteryManager / sticky-intent dump — merged in from
            // the former separate "Battery (deep)" section (one Battery
            // section, not two).
            it.addView(small(ctx, "— Deep (BatteryManager + sticky intent) —"))
            renderBatteryDeep(ctx, it)
            // AccuBattery-style energy page — device state→draw
            // attribution, per-foreground-app draw, and our own subsystem
            // ledger (what INSIDE the app costs). Backed by the
            // EnergyWatchdog Tier-1 sampler + EnergyLedger.
            it.addView(actionButton(ctx, "Battery Usage Details", GRAY) {
                runCatching {
                    com.diegonmarcos.superapp.battery.EnergyUsageDialog()
                        .show(parentFragmentManager, com.diegonmarcos.superapp.battery.EnergyUsageDialog.TAG)
                }
            })
        }

        section(ctx, column, "Firewall") {
            // No-root per-app firewall (local VpnService, :libs:firewall).
            // Info rows read WITHOUT any privileged permission via
            // ConnectivityManager / Settings. The gray button opens the control
            // screen (master toggle + per-app preset picker). The firestack
            // merge (per-app filtering + WireGuard in one tunnel) is staged at
            // libs/firewall/phase3-firestack/ (upstreams.firestack).
            val fw = com.diegonmarcos.superapp.firewall.FirewallInfo.read(ctx)
            row(ctx, it, "State", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtState(fw))
            row(ctx, it, "Apps with rules", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtBlocked(fw))
            row(ctx, it, "Active transport", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtTransport(fw))
            row(ctx, it, "System VPN", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtVpn(fw))
            row(ctx, it, "Private DNS", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtPrivateDns(fw))
            it.addView(small(ctx, "Single VPN slot — per-app rules apply while on; firestack merge (WG-unified) is staged."))
            it.addView(actionButton(ctx, "Firewall Details", GRAY) {
                runCatching {
                    com.diegonmarcos.superapp.firewall.FirewallDialog()
                        .show(parentFragmentManager, com.diegonmarcos.superapp.firewall.FirewallDialog.TAG)
                }
            })
        }

        section(ctx, column, "Memory & CPU Usage") {
            // All metrics are process-attributable + readable WITHOUT any
            // privileged permission. JVM heap from Runtime; PSS from
            // Debug.MemoryInfo (Android's accounting for shared-page
            // proportional set size); RSS from /proc/self/statm (raw
            // kernel view). System totals come from ActivityManager.
            // CPU comes from /proc/self/stat (utime + stime in jiffies)
            // normalised against process wall-time for an avg %.
            val ctxAny = requireContext()
            val rt = Runtime.getRuntime()

            val heapMax   = rt.maxMemory()
            val heapTotal = rt.totalMemory()
            val heapFree  = rt.freeMemory()
            val heapUsed  = heapTotal - heapFree
            val heapPct   = if (heapMax > 0) (heapUsed * 100 / heapMax).toInt() else -1
            row(ctx, it, "JVM heap used",
                "${sizeStr(heapUsed)} / ${sizeStr(heapMax)}" +
                    (if (heapPct >= 0) " ($heapPct%)" else ""))
            row(ctx, it, "JVM heap total", sizeStr(heapTotal))
            row(ctx, it, "JVM heap free",  sizeStr(heapFree))

            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            row(ctx, it, "PSS total",     sizeStr(mi.totalPss.toLong()        * 1024L))
            row(ctx, it, "PSS dalvik",    sizeStr(mi.dalvikPss.toLong()       * 1024L))
            row(ctx, it, "PSS native",    sizeStr(mi.nativePss.toLong()       * 1024L))
            row(ctx, it, "PSS other",     sizeStr(mi.otherPss.toLong()        * 1024L))
            row(ctx, it, "Private dirty", sizeStr(mi.totalPrivateDirty.toLong() * 1024L))

            // RSS via /proc/self/statm field 2 (resident pages).
            val pageSize = runCatching {
                android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            }.getOrDefault(4096L)
            val rssBytes = runCatching {
                val parts = File("/proc/self/statm").readText().trim().split(' ')
                parts.getOrNull(1)?.toLongOrNull()?.let { it * pageSize } ?: -1L
            }.getOrDefault(-1L)
            row(ctx, it, "RSS", if (rssBytes < 0) "—" else sizeStr(rssBytes))

            val am = ctxAny.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            val sysMi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(sysMi)
            row(ctx, it, "System avail",      sizeStr(sysMi.availMem))
            row(ctx, it, "System total",      sizeStr(sysMi.totalMem))
            row(ctx, it, "System low-memory", sysMi.lowMemory.toString())
            row(ctx, it, "Heap class limit",  am?.memoryClass?.let { "$it MB" } ?: "—")
            row(ctx, it, "Heap class (large)",
                am?.largeMemoryClass?.let { "$it MB" } ?: "—")

            // CPU: utime (field 14) + stime (field 15) in jiffies. The
            // comm field is parenthesised and may contain spaces — strip
            // it before tokenising so field indices stay aligned.
            val statRaw = runCatching { File("/proc/self/stat").readText() }.getOrNull()
            val fields = statRaw?.let { raw ->
                val open  = raw.indexOf('(')
                val close = raw.lastIndexOf(')')
                if (open < 0 || close < 0 || close <= open) null
                else (raw.substring(0, open) + raw.substring(close + 1))
                    .trim().split(Regex("\\s+"))
            }
            val tck   = runCatching {
                android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
            }.getOrDefault(100L)
            val utime = fields?.getOrNull(13)?.toLongOrNull() ?: -1L
            val stime = fields?.getOrNull(14)?.toLongOrNull() ?: -1L
            val cpuJif = if (utime < 0 || stime < 0) -1L else utime + stime

            val uptimeMs = android.os.SystemClock.elapsedRealtime() -
                AppProcessUptime.startedAtElapsed
            val uptimeSec = uptimeMs / 1000.0

            row(ctx, it, "CPU user-time",
                if (utime < 0 || tck <= 0) "—"
                else "%.2fs".format(utime.toDouble() / tck))
            row(ctx, it, "CPU sys-time",
                if (stime < 0 || tck <= 0) "—"
                else "%.2fs".format(stime.toDouble() / tck))
            row(ctx, it, "CPU total",
                if (cpuJif < 0 || tck <= 0) "—"
                else "%.2fs".format(cpuJif.toDouble() / tck))

            val cores = rt.availableProcessors()
            val avgPctSingle = if (cpuJif < 0 || uptimeSec <= 0 || tck <= 0) -1.0
                else (cpuJif.toDouble() / tck / uptimeSec) * 100.0
            val avgPctPerCore = if (avgPctSingle < 0 || cores <= 0) -1.0
                else avgPctSingle / cores
            row(ctx, it, "CPU avg % (1-thread)",
                if (avgPctSingle < 0) "—" else "%.2f%%".format(avgPctSingle))
            row(ctx, it, "CPU avg % (per-core)",
                if (avgPctPerCore < 0) "—" else "%.2f%%".format(avgPctPerCore))
            row(ctx, it, "Cores online", cores.toString())

            // ── Phone storage ─────────────────────────────────────
            // StatFs against the data partition is the right anchor —
            // that's where the app, its caches, and DB live; "phone
            // storage" colloquially means user-data partition free space.
            // External / SD is separate but reported next to it so the
            // user has both numbers in one place.
            val dataDir = android.os.Environment.getDataDirectory().absolutePath
            val (dataFree, dataTotal) = statFsBytes(dataDir)
            val dataPct = if (dataTotal > 0) ((dataTotal - dataFree) * 100 / dataTotal).toInt() else -1
            row(ctx, it, "Data free",
                "${sizeStr(dataFree)} / ${sizeStr(dataTotal)}" +
                    (if (dataPct >= 0) " (${100 - dataPct}% free)" else ""))

            val extDir = ctxAny.getExternalFilesDir(null)?.absolutePath
            if (extDir != null) {
                val (extFree, extTotal) = statFsBytes(extDir)
                row(ctx, it, "External free",
                    "${sizeStr(extFree)} / ${sizeStr(extTotal)}")
            }

            // ── Phone swap (zRAM / disk) ──────────────────────────
            // /proc/meminfo SwapTotal + SwapFree are kB, space-padded.
            // Some kernels disable swap entirely → SwapTotal:0; report
            // "disabled" so the row isn't misread as "everything's used".
            val (swapTotal, swapFree) = readSwapBytes()
            val swapUsed = if (swapTotal < 0 || swapFree < 0) -1L else swapTotal - swapFree
            row(ctx, it, "Swap total",
                if (swapTotal < 0) "—"
                else if (swapTotal == 0L) "disabled"
                else sizeStr(swapTotal))
            row(ctx, it, "Swap free",
                if (swapFree < 0 || swapTotal == 0L) "—" else sizeStr(swapFree))
            row(ctx, it, "Swap used",
                if (swapUsed < 0 || swapTotal == 0L) "—" else sizeStr(swapUsed))

            it.addView(small(ctx, "PSS = process-attributable RSS after sharing. JVM heap is the growable section of the Dalvik PSS bucket. CPU avg % is utime+stime / wall-time × 100 — 1-thread (100% = one core saturated) or per-core (100% = ALL cores saturated). Sustained > 50% with the screen off may indicate a background work leak. Storage = data partition (where the app + caches live). Swap is typically zRAM on Android — counts AGAINST physical RAM but appears as virtual."))
        }

        // (VPN / WireGuard / Mesh moved up under the "Cloud" macro header,
        //  rendered via renderVpnMesh — see the reorg right after the title.)

        // IPC Contract — the cross-app intent/IPC surface Cloud SuperApp
        // exposes/consumes (the constellation hub ↔ ea_cloud-comms /
        // ea_cloud-ide contract). Always rendered, even when nothing is
        // declared yet, so the surface is discoverable.
        section(ctx, column, "IPC Contract") {
            val entries = collectIpcContract(requireContext())
            if (entries.isEmpty()) {
                it.addView(small(ctx, "No IPC contract declared yet — no exported/consumed cross-app intents, services, or providers beyond the framework defaults."))
            } else {
                for ((k, v) in entries) row(ctx, it, k, v)
            }
        }

        section(ctx, column, "Network") {
            val cm = ctxAny().getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val active = cm?.activeNetwork
            val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            val link = active?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

            val transports = mutableListOf<String>()
            caps?.let { c ->
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))      transports.add("Wi-Fi")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))  transports.add("Cellular")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))  transports.add("Ethernet")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))       transports.add("VPN")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("Bluetooth")
            }
            row(ctx, it, "Transport",     if (transports.isEmpty()) "—" else transports.joinToString(", "))
            row(ctx, it, "Validated",     caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: "—")
            row(ctx, it, "Captive portal", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)?.toString() ?: "—")
            row(ctx, it, "Metered",       cm?.isActiveNetworkMetered?.toString() ?: "—")
            caps?.let { c ->
                row(ctx, it, "Link down",     "${c.linkDownstreamBandwidthKbps} kbps")
                row(ctx, it, "Link up",       "${c.linkUpstreamBandwidthKbps} kbps")
            }
            link?.let { lp ->
                row(ctx, it, "Iface",  lp.interfaceName ?: "—")
                row(ctx, it, "MTU",    lp.mtu.toString())
                row(ctx, it, "DNS",    lp.dnsServers.joinToString(", ") { it.hostAddress ?: "" }.ifBlank { "—" })
                val v4 = lp.linkAddresses.mapNotNull { la -> la.address.hostAddress }.filter { ":" !in it }
                val v6 = lp.linkAddresses.mapNotNull { la -> la.address.hostAddress }.filter { ":" in it }
                row(ctx, it, "IPv4",   v4.joinToString(", ").ifBlank { "—" })
                row(ctx, it, "IPv6",   v6.joinToString(", ").ifBlank { "—" })
                val gw = lp.routes.firstOrNull { r -> r.isDefaultRoute }?.gateway?.hostAddress
                row(ctx, it, "Default gw", gw ?: "—")
            }
        }

        section(ctx, column, "Wi-Fi") {
            // SSID/BSSID require ACCESS_FINE_LOCATION on Android 10+
            // AND a connected Wi-Fi network; we already declare both.
            // If location isn't granted yet, SSID comes back as
            // "<unknown ssid>" — surface that as the user-facing hint.
            val wm = ctxAny().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm == null || !wm.isWifiEnabled) {
                it.addView(small(ctx, "Wi-Fi is off."))
            } else {
                @Suppress("DEPRECATION")
                val info = runCatching { wm.connectionInfo }.getOrNull()
                if (info == null) {
                    it.addView(small(ctx, "No Wi-Fi connection info."))
                } else {
                    val ssid = info.ssid?.trim('"').orEmpty()
                    row(ctx, it, "SSID",       if (ssid.isBlank() || ssid == "<unknown ssid>") "Needs location permission" else ssid)
                    row(ctx, it, "BSSID",      info.bssid ?: "—")
                    row(ctx, it, "RSSI",       "${info.rssi} dBm")
                    row(ctx, it, "Link speed", "${info.linkSpeed} Mbps")
                    row(ctx, it, "Tx speed",   "${info.txLinkSpeedMbps} Mbps")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        row(ctx, it, "Rx speed", "${info.rxLinkSpeedMbps} Mbps")
                    }
                    row(ctx, it, "Frequency",  "${info.frequency} MHz")
                    row(ctx, it, "Hidden SSID", info.hiddenSSID.toString())
                }
            }
        }

        section(ctx, column, "Sections (from build.json)") {
            val all = Sections.all()
            row(ctx, it, "Total",       all.size.toString())
            row(ctx, it, "Bottom-nav",  all.count { s -> s.bottomNav }.toString())
            row(ctx, it, "Aggregators", all.count { s -> s.isAggregator }.toString())
            row(ctx, it, "Default mode", Sections.defaultMode())
            row(ctx, it, "Default section", Sections.defaultSectionId())
        }

        section(ctx, column, "Dev Control - Http (API) & SSH (Termux)") {
            val prefs = DevControlPrefs(requireContext())
            val running = DevControlServer.isRunning()
            val bound   = DevControlServer.boundHost()
            val loopback = DevControlServer.isLoopbackOnly()
            row(ctx, it, "Endpoint", "http://${bound ?: "127.0.0.1"}:${prefs.port}")
            row(ctx, it, "Status",   if (running) "Running" else "Stopped")
            row(ctx, it, "Bound to", bound ?: "—")
            row(ctx, it, "Bind scope", when {
                !running       -> "—"
                loopback       -> "✓ Loopback only (127.0.0.1) — unreachable from LAN"
                else           -> "✗ Reachable from LAN — bind addr ${bound ?: "?"}"
            })
            row(ctx, it, "Token",    prefs.token)
            it.addView(small(ctx, "Bearer token — long-press to copy. Endpoints follow /api/{group}/{op} (e.g. /api/system/info, /api/diagnostics/logcat, /api/tracker/counts). Full catalog: GET /api/docs."))

            // ── SSH (Termux & co.) — detect installed terminal emulators and
            //    whether each is running an sshd we can reach on localhost.
            //    Installed-check is synchronous (PackageManager); the port
            //    probe is async (no network on the main thread).
            it.addView(small(ctx, "SSH servers on this device — terminal apps + whether their sshd is listening on localhost (probes 127.0.0.1 across common ports)."))
            for (term in SSH_TERMINALS) {
                val installed = isPackageInstalled(ctx, term.pkg)
                val portList = term.ports.joinToString(",")
                val valueView = row(ctx, it, term.label,
                    if (!installed) "not installed" else "installed · probing :$portList…")
                if (installed) viewLifecycleOwner.lifecycleScope.launch {
                    val open = withContext(Dispatchers.IO) { term.ports.filter { p -> portOpen("127.0.0.1", p) } }
                    if (open.isNotEmpty()) {
                        valueView.text = "installed · sshd ✓ up :${open.joinToString(",")}"
                        valueView.setTextColor(0xFF8BE9A0.toInt())
                    } else {
                        valueView.text = "installed · sshd ✗ down (tried :$portList)"
                        valueView.setTextColor(0xFFFFB199.toInt())
                    }
                }
            }

            // Toggle Switch: persists pref + start/stop the server live.
            it.addView(android.widget.Switch(ctx).apply {
                text = "API enabled"
                isChecked = prefs.enabled
                val pad = dp(6); setPadding(pad, pad, pad, pad)
                setOnCheckedChangeListener { _, checked ->
                    prefs.enabled = checked
                    if (checked) {
                        DevControlServer.start(requireContext().applicationContext)
                    } else {
                        DevControlServer.stop()
                    }
                    // Re-render so Status/Bound/scope reflect the new state.
                    parentFragmentManager.beginTransaction().detach(this@DevControlFragment).commitNow()
                    parentFragmentManager.beginTransaction().attach(this@DevControlFragment).commitNow()
                }
            })

            // Regenerate the bearer token. Rotates the cached value AND
            // restarts the server so the new token is bound to the
            // accept loop instead of the stale one captured at start().
            it.addView(actionButton(ctx, "Regenerate API token", GRAY) {
                val fresh = prefs.resetToken()
                // Bounce the server so the in-memory token (captured at
                // start) is replaced by the fresh value.
                if (DevControlServer.isRunning()) {
                    DevControlServer.stop()
                    DevControlServer.start(requireContext().applicationContext)
                }
                Toast.makeText(requireContext(),
                    "New token: ${fresh.take(8)}…", Toast.LENGTH_SHORT).show()
                // Re-render so the new token + restarted server status
                // populate immediately.
                parentFragmentManager.beginTransaction().detach(this@DevControlFragment).commitNow()
                parentFragmentManager.beginTransaction().attach(this@DevControlFragment).commitNow()
            })
            // Self-contained ADB (libs:shizuku-adb-debug-tools): jump to
            // Developer options to flip Wireless Debugging ON, then read the
            // pairing/connect ports for /api/adb/pair + /api/adb/connect. The
            // OS toggle can't be flipped by an app (no API) — this is a
            // deep-link. Lives here next to the HTTP API it feeds.
            it.addView(small(ctx, "Self-contained ADB — enable Wireless Debugging, then pair via /api/adb/pair + /api/adb/connect:"))
            it.addView(actionButton(ctx, "Open Wireless Debugging", GRAY) { openWirelessDebuggingSettings() })

            // ── Self-contained ADB how-to (embedded adb client, no Shizuku
            //    app, no PC; works with WireGuard ON). Long-press any row to
            //    copy. Documented here next to the button that enables it.
            val adbPort = DevControlPrefs(requireContext()).port
            val adbTok  = prefs.token
            it.addView(small(ctx, "Self-contained ADB (embedded libadb — no Shizuku app, no PC, works with WireGuard ON). Steps:"))
            it.addView(small(ctx, "1) Tap 'Open Wireless Debugging' → turn it ON. 2) 'Pair device with pairing code' → note the 6-digit code + that dialog's port (=pairport). 3) Main screen → the IP:port there is the connectport."))
            it.addView(small(ctx, "⚠ HOST GOTCHA: use your Wi-Fi LAN IP (e.g. 192.168.x.x), NOT the 10.x the dialog shows — that 10.x is the WireGuard tun0 and gets ECONNREFUSED. Find the real wlan0 IP with /api/adb/netinfo."))
            row(ctx, it, "0 NetInfo",  "curl -H 'Authorization: Bearer $adbTok' http://127.0.0.1:$adbPort/api/adb/netinfo   # find wlan0 IPv4")
            row(ctx, it, "1 Pair",     "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/pair?host=<wlan-ip>&port=<pairport>&code=<6digits>'")
            row(ctx, it, "2 Connect",  "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/connect?host=<wlan-ip>&port=<connectport>'")
            row(ctx, it, "3 Status",   "curl -H 'Authorization: Bearer $adbTok' http://127.0.0.1:$adbPort/api/adb/status   # embedded-adb ready=true")
            row(ctx, it, "4 Charger",  "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/diagnostics?bundle=charger'")
            row(ctx, it, "Bundles",    "charger | battery | usb | thermal | pd   (build.json::shizuku_diagnostics)")
            row(ctx, it, "Exec",       "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/exec?cmd=dumpsys%20battery'")
            it.addView(small(ctx, "Pairing/connect bind the socket to the Wi-Fi Network so it bypasses wg0. Pairing is once per boot (Android law; only root removes it). Pair port lives only while the pairing dialog is open — keep it open until step 1 returns ok."))
        }

        section(ctx, column, "Curl shortcuts") {
            val port = DevControlPrefs(requireContext()).port
            val tok  = DevControlPrefs(requireContext()).token
            // Endpoint catalog moved to /api/{group}/{op} layout in
            // commit fb2bc62; the flat aliases still resolve but the
            // documented form is the grouped one. Source of truth for
            // the full list is GET /api/docs (machine-readable JSON,
            // generated from the same Spec list the routing uses so it
            // can't drift). These shortcuts cover the most-used probes.
            row(ctx, it, "Docs",     "curl http://127.0.0.1:$port/api/docs")
            row(ctx, it, "Logcat",   "curl http://127.0.0.1:$port/api/diagnostics/logcat?n=500")
            row(ctx, it, "Trace",    "curl http://127.0.0.1:$port/api/diagnostics/trace")
            row(ctx, it, "Crashes",  "curl http://127.0.0.1:$port/api/diagnostics/crashes")
            row(ctx, it, "Bundle",   "curl http://127.0.0.1:$port/api/diagnostics/bundle")
            row(ctx, it, "Download logs", "curl http://127.0.0.1:$port/api/diagnostics/download")
            row(ctx, it, "Send logs → cloud", "curl http://127.0.0.1:$port/api/diagnostics/push")
            row(ctx, it, "Info",     "curl http://127.0.0.1:$port/api/system/info")
            row(ctx, it, "State",    "curl -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/api/state")
            row(ctx, it, "Haptic",   "curl -XPOST -H 'Authorization: Bearer $tok' 'http://127.0.0.1:$port/api/haptic?preset=gemini_stream'")
            row(ctx, it, "Update",   "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/api/system/update")
            row(ctx, it, "Restart",  "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/api/system/restart")
            row(ctx, it, "Tracker",  "curl http://127.0.0.1:$port/api/tracker/counts")
        }

        section(ctx, column, "SoC / CPU") {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                row(ctx, it, "SoC mfr",   android.os.Build.SOC_MANUFACTURER)
                row(ctx, it, "SoC model", android.os.Build.SOC_MODEL)
            }
            row(ctx, it, "Bootloader", android.os.Build.BOOTLOADER)
            @Suppress("DEPRECATION")
            row(ctx, it, "Radio",      android.os.Build.getRadioVersion() ?: "—")
            row(ctx, it, "Board",      android.os.Build.BOARD)
            row(ctx, it, "Fingerprint", android.os.Build.FINGERPRINT)
            row(ctx, it, "CPU cores",  Runtime.getRuntime().availableProcessors().toString())
            val cpuModel = runCatching {
                File("/proc/cpuinfo").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Hardware") || it.contains("model name") }
                        ?.substringAfter(':')?.trim()
                }
            }.getOrNull()
            row(ctx, it, "CPU model",  cpuModel ?: "—")
            val curFreq = runCatching {
                File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toLong()
            }.getOrNull()
            row(ctx, it, "Cur freq",   curFreq?.let { "%d MHz".format(it / 1000) } ?: "—")
            val gov = runCatching {
                File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim()
            }.getOrNull()
            row(ctx, it, "Governor",   gov ?: "—")
        }

        section(ctx, column, "Thermal zones") {
            val zones = runCatching {
                File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                    ?.sortedBy { it.name }
            }.getOrNull()
            if (zones.isNullOrEmpty()) {
                it.addView(small(ctx, "No thermal zones readable (kernel restricts /sys/class/thermal on most modern devices)."))
            } else {
                for (z in zones) {
                    val type = runCatching { File(z, "type").readText().trim() }.getOrDefault(z.name)
                    val tempMilliC = runCatching { File(z, "temp").readText().trim().toLong() }.getOrDefault(0L)
                    row(ctx, it, z.name, "$type — %.1f °C".format(tempMilliC / 1000.0))
                }
            }
        }

        // SYSFS-PROC — the no-perm kernel-telemetry dump. Every field
        // here comes from a world-readable /sys or /proc file (the
        // same path AccuBattery, Termux's top, and friends use). No
        // DUMP, no QUERY_ALL_PACKAGES, no signature perm required.
        // The reader (SysfsProc.kt) returns (key, formatted-value)
        // pairs per subsystem; we paint sub-group headers via small()
        // so the section is one big scrollable table the user can
        // long-press individual rows to copy.
        section(ctx, column, "SYSFS-PROC") {
            it.addView(small(ctx, "Raw kernel-side telemetry — no runtime permission needed. World-readable /sys/class/* and /proc/* paths. Use this as the audit surface; promote interesting rows to dedicated UI elsewhere."))

            it.addView(small(ctx, "── /sys/class/power_supply/battery/"))
            for ((k, v) in SysfsProc.battery()) row(ctx, it, k, v)

            it.addView(small(ctx, "── /sys/class/power_supply/{usb,ac,wireless,main}/"))
            val chargerRows = SysfsProc.chargers()
            if (chargerRows.isEmpty()) it.addView(small(ctx, "(no charger nodes readable)"))
            for ((k, v) in chargerRows) row(ctx, it, k, v)

            it.addView(small(ctx, "── /proc/loadavg + /proc/uptime  (CPU load in seconds)"))
            for ((k, v) in SysfsProc.cpuLoadRows()) row(ctx, it, k, v)

            it.addView(small(ctx, "── /proc/stat  (cumulative jiffies → seconds, ALL cores)"))
            for ((k, v) in SysfsProc.procStatRows()) row(ctx, it, k, v)

            it.addView(small(ctx, "── /sys/devices/system/cpu/cpu*/cpufreq/"))
            for ((k, v) in SysfsProc.cpuFreqs()) row(ctx, it, k, v)

            it.addView(small(ctx, "── /sys/class/thermal/thermal_zone*/"))
            val thermalRows = SysfsProc.thermal()
            if (thermalRows.isEmpty()) it.addView(small(ctx, "(no zones readable — vendor restriction)"))
            for ((k, v) in thermalRows) row(ctx, it, k, v)

            it.addView(small(ctx, "── /proc/meminfo  (selected)"))
            for ((k, v) in SysfsProc.memInfo()) row(ctx, it, k, v)

            it.addView(small(ctx, "── /sys/class/net/*/statistics/"))
            for ((k, v) in SysfsProc.network()) row(ctx, it, k, v)

            it.addView(small(ctx, "── /proc/diskstats"))
            val diskRows = SysfsProc.diskstats()
            if (diskRows.isEmpty()) it.addView(small(ctx, "(no diskstats readable)"))
            for ((k, v) in diskRows) row(ctx, it, k, v)

            it.addView(small(ctx, "── /proc/self/  (this app's own kernel-side stats)"))
            for ((k, v) in SysfsProc.selfProc()) row(ctx, it, k, v)
        }

        section(ctx, column, "Kernel / OS") {
            row(ctx, it, "Kernel",   System.getProperty("os.version") ?: "—")
            row(ctx, it, "Security patch", android.os.Build.VERSION.SECURITY_PATCH)
            row(ctx, it, "Codename", android.os.Build.VERSION.CODENAME)
            row(ctx, it, "Incremental", android.os.Build.VERSION.INCREMENTAL)
            row(ctx, it, "/proc/version", runCatching { File("/proc/version").readText().trim() }.getOrDefault("—"))
            row(ctx, it, "VM",       "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
            row(ctx, it, "VM heap",  "${sizeStr(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())} / ${sizeStr(Runtime.getRuntime().maxMemory())}")
            val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize()
            row(ctx, it, "Native heap", sizeStr(nativeHeap))
        }

        section(ctx, column, "Memory") {
            val am = ctxAny().getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            row(ctx, it, "Total RAM",  sizeStr(mi.totalMem))
            row(ctx, it, "Available",  sizeStr(mi.availMem))
            row(ctx, it, "Threshold",  sizeStr(mi.threshold))
            row(ctx, it, "Low memory", mi.lowMemory.toString())
            val pids = intArrayOf(android.os.Process.myPid())
            val procInfo = runCatching { am?.getProcessMemoryInfo(pids) }.getOrNull()?.firstOrNull()
            if (procInfo != null) {
                row(ctx, it, "App PSS",  sizeStr(procInfo.totalPss * 1024L))
                row(ctx, it, "Java heap", sizeStr(procInfo.getMemoryStat("summary.java-heap")?.toLongOrNull()?.times(1024L) ?: 0L))
                row(ctx, it, "Native heap (proc)", sizeStr(procInfo.getMemoryStat("summary.native-heap")?.toLongOrNull()?.times(1024L) ?: 0L))
            }
            val fdCount = runCatching {
                File("/proc/self/fd").listFiles()?.size ?: 0
            }.getOrDefault(0)
            row(ctx, it, "Open FDs", fdCount.toString())
            row(ctx, it, "Threads",  Thread.activeCount().toString())
        }

        section(ctx, column, "Display") {
            val wm = ctxAny().getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val dm = resources.displayMetrics
            row(ctx, it, "Resolution", "${dm.widthPixels} × ${dm.heightPixels}")
            row(ctx, it, "Density",    "${dm.density}x · ${dm.densityDpi} dpi")
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            row(ctx, it, "Refresh",    display?.refreshRate?.let { "%.1f Hz".format(it) } ?: "—")
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val modes = display?.supportedModes?.map { "%.0f Hz @ %dx%d".format(it.refreshRate, it.physicalWidth, it.physicalHeight) }
                row(ctx, it, "Modes", modes?.joinToString(", ") ?: "—")
            }
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                row(ctx, it, "HDR",       display?.hdrCapabilities?.supportedHdrTypes?.joinToString(",") ?: "—")
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                row(ctx, it, "Wide gamut", display?.isWideColorGamut?.toString() ?: "—")
            }
            val cfg = resources.configuration
            val dark = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            row(ctx, it, "Dark mode", dark.toString())
            row(ctx, it, "Font scale", "${cfg.fontScale}x")
        }

        section(ctx, column, "APK provenance") {
            val pm = ctxAny().packageManager
            val pkg = ctxAny().packageName
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val src = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull()
                row(ctx, it, "Installed by", src?.installingPackageName ?: "—")
                row(ctx, it, "Initiated by", src?.initiatingPackageName ?: "—")
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    row(ctx, it, "Update owner", src?.updateOwnerPackageName ?: "—")
            } else {
                @Suppress("DEPRECATION")
                val installer = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
                row(ctx, it, "Installed by", installer ?: "—")
            }
            // Signing cert SHA-256 — proves "this APK was signed by my keystore"
            val sigInfo = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                } else null
            }.getOrNull()
            val sigs = sigInfo?.signingCertificateHistory ?: sigInfo?.apkContentsSigners
            if (sigs.isNullOrEmpty()) {
                row(ctx, it, "Cert SHA-256", "—")
            } else {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val hex = md.digest(sigs[0].toByteArray()).joinToString(":") { "%02X".format(it) }
                row(ctx, it, "Cert SHA-256", hex)
            }
            // Split APKs (base + per-density + per-ABI).
            // PackageInfo.applicationInfo is @Nullable as of API 35;
            // safe-call every access and degrade to "—" / 0 if the
            // platform decides not to hand us an ApplicationInfo.
            @Suppress("DEPRECATION")
            val appInfo = pm.getPackageInfo(pkg, 0).applicationInfo
            val splits = appInfo?.splitSourceDirs?.size ?: 0
            row(ctx, it, "Splits",      "$splits split APK(s)")
            row(ctx, it, "Native lib dir", appInfo?.nativeLibraryDir ?: "—")
            val nativeLibs = runCatching { File(appInfo?.nativeLibraryDir ?: "").listFiles()?.map { it.name } }.getOrNull()
            row(ctx, it, "Native libs", nativeLibs?.joinToString(", ") ?: "—")
        }

        section(ctx, column, "Sensors") {
            val sm = ctxAny().getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            val all = sm?.getSensorList(android.hardware.Sensor.TYPE_ALL) ?: emptyList()
            row(ctx, it, "Count", all.size.toString())
            for ((idx, s) in all.withIndex()) {
                row(ctx, it, "Sensor ${idx + 1}", "${s.name} — ${s.vendor}")
            }
        }

        section(ctx, column, "Security posture") {
            val cr = ctxAny().contentResolver
            val devMode = runCatching {
                android.provider.Settings.Global.getInt(cr,
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
            }.getOrDefault(false)
            val adbEnabled = runCatching {
                android.provider.Settings.Global.getInt(cr,
                    android.provider.Settings.Global.ADB_ENABLED) == 1
            }.getOrDefault(false)
            row(ctx, it, "Dev options", devMode.toString())
            row(ctx, it, "USB ADB",     adbEnabled.toString())
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val adbWifi = runCatching {
                    android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled") == 1
                }.getOrDefault(false)
                row(ctx, it, "Wireless ADB", adbWifi.toString())
            }
            val km = ctxAny().getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            row(ctx, it, "Device secure",  (km?.isDeviceSecure ?: false).toString())
            row(ctx, it, "Keyguard locked", (km?.isKeyguardLocked ?: false).toString())
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val bm = ctxAny().getSystemService(Context.BIOMETRIC_SERVICE)
                    as? android.hardware.biometrics.BiometricManager
                val biometric = runCatching {
                    @Suppress("DEPRECATION")
                    bm?.canAuthenticate() ?: -1
                }.getOrDefault(-1)
                row(ctx, it, "Biometric ready", when (biometric) {
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS              -> "Yes"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED  -> "No (not enrolled)"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE    -> "No (no hardware)"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
                    else -> "Unknown"
                })
            }
        }

        section(ctx, column, "Stack") {
            // Languages — decode the base64 JSON blob the build emitted.
            // Format: {"Kotlin":{"files":N,"loc":M}, …}
            it.addView(small(ctx, "What's actually inside this APK — scanned by the build, never a hardcoded list."))
            val langB64 = BuildConfig.UI_STACK_LANGUAGES_JSON_B64
            val langJson = runCatching { String(android.util.Base64.decode(langB64, android.util.Base64.DEFAULT)) }.getOrDefault("{}")
            val langs = runCatching {
                val o = org.json.JSONObject(langJson)
                val list = mutableListOf<Triple<String, Int, Int>>()
                val it2 = o.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    val v = o.optJSONObject(k) ?: continue
                    list += Triple(k, v.optInt("files", 0), v.optInt("loc", 0))
                }
                list.sortedByDescending { tr -> tr.third }
            }.getOrDefault(emptyList())
            val totalLoc = langs.sumOf { tr -> tr.third }
            val totalFiles = langs.sumOf { tr -> tr.second }
            row(ctx, it, "Total LOC",   "%,d lines".format(totalLoc))
            row(ctx, it, "Total files", totalFiles.toString())
            for ((lang, files, loc) in langs) {
                val pct = if (totalLoc > 0) " (%d%%)".format(loc * 100 / totalLoc) else ""
                row(ctx, it, lang, "%,d lines · %d file(s)%s".format(loc, files, pct))
            }

            // Frameworks — sorted unique Maven coordinates that the
            // gradle scripts depend on. Internal :libs:* are skipped.
            it.addView(small(ctx, "Frameworks (Maven coordinates from every *.gradle in the repo):"))
            val fwB64 = BuildConfig.UI_STACK_FRAMEWORKS_JSON_B64
            val fwJson = runCatching { String(android.util.Base64.decode(fwB64, android.util.Base64.DEFAULT)) }.getOrDefault("[]")
            val frameworks = runCatching {
                val arr = org.json.JSONArray(fwJson)
                (0 until arr.length()).map { i -> arr.getString(i) }
            }.getOrDefault(emptyList())
            row(ctx, it, "Dep count", frameworks.size.toString())
            for (dep in frameworks) {
                // Strip the version off the front for terseness; show
                // "group:artifact" with version on a second row.
                val parts = dep.split(":")
                val head = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else dep
                val ver  = if (parts.size >= 3) parts[2] else "?"
                row(ctx, it, head, ver)
            }

            // Local internal modules — derived from build.json::modules.
            it.addView(small(ctx, "Internal modules (build.json::modules, project(':libs:*')):"))
            // Modules are part of buildJson::modules but we don't have it
            // here — list nativeLibraryDir contents (per-ABI native libs)
            // instead, which is its own useful "what's in here" line.
            val pm2 = ctxAny().packageManager
            @Suppress("DEPRECATION")
            // PackageInfo.applicationInfo is @Nullable as of API 35.
            val ai = pm2.getPackageInfo(ctxAny().packageName, 0).applicationInfo
            val splits = ai?.splitSourceDirs
            val baseApk = ai?.sourceDir?.let { File(it) }
            row(ctx, it, "Base APK",
                if (baseApk != null) "${baseApk.name} · ${sizeStr(baseApk.length())}" else "—")
            splits?.forEach { s ->
                row(ctx, it, "Split", File(s).name + " · " + sizeStr(File(s).length()))
            }

            // Build-time metrics. Avg/last numbers come from `gh run
            // list` at gradle config time — full wall-clock from queue
            // to "completed" of the GHA `ship-cloud-superapp.yml`
            // workflow, sampling the most-recent N successful runs.
            it.addView(small(ctx, "Build metrics (from GitHub Actions history):"))
            fun fmtSecs(s: Long): String = when {
                s < 0     -> "—"
                s < 60    -> "${s}s"
                s < 3600  -> "%dm %02ds".format(s / 60, s % 60)
                else      -> "%dh %02dm".format(s / 3600, (s % 3600) / 60)
            }
            row(ctx, it, "Build avg time", fmtSecs(BuildConfig.STACK_BUILD_AVG_SECS) +
                if (BuildConfig.STACK_BUILD_SAMPLE > 0) " (n=${BuildConfig.STACK_BUILD_SAMPLE})" else "")
            row(ctx, it, "Build last",     fmtSecs(BuildConfig.STACK_BUILD_LAST_SECS))
            row(ctx, it, "Gradle config phase", "%d ms".format(BuildConfig.STACK_GRADLE_CONFIG_MS))
            row(ctx, it, "Build SHA",      BuildConfig.GIT_SHORT_SHA)

            // Three trees SIDE-BY-SIDE — Sitemap | Folders | AST. Accordion:
            // tapping one button OPENS its scroller and CLOSES the other two
            // (only one box visible at a time). LONG-PRESS — on the button OR
            // anywhere in the open box — copies that tree's full text to the
            // clipboard. (Folders also feeds the page-copy buffer.)
            val box = it
            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(4))
            }
            fun dec(b64: String) = runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }.getOrDefault("—")
            // Each tree is a list of (column-header, text) columns. Sitemap and
            // AST are single-column; Folders is three columns — L2 / L3 / L4 —
            // each a depth-bounded view of the same ea_* scan.
            val trees = listOf(
                Triple("Sitemap", listOf("" to dec(BuildConfig.UI_ASM_TREE_B64)), false),
                Triple("Folders", listOf(
                    "L3" to dec(BuildConfig.UI_STACK_FOLDER_TREE_L3_B64),
                    "L4" to dec(BuildConfig.UI_STACK_FOLDER_TREE_L4_B64),
                    "L5" to dec(BuildConfig.UI_STACK_FOLDER_TREE_B64),
                ), true),
                Triple("AST", listOf("" to dec(BuildConfig.UI_AST_TREE_B64)), false),
            )
            val buttons = mutableListOf<TextView>()
            val scrollers = mutableListOf<View>()
            val labels = mutableListOf<String>()
            fun copyTree(label: String, str: String) {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(label, str))
                android.widget.Toast.makeText(ctx, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Collapse every tree, reset every button caret. `openIdx` < 0 = all closed.
            fun setOpen(openIdx: Int) {
                for (i in scrollers.indices) {
                    val show = i == openIdx
                    scrollers[i].visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                    buttons[i].text = (if (show) "▾ " else "▸ ") + labels[i]
                }
            }
            for ((idx, t) in trees.withIndex()) {
                val (label, cols, intoBuf) = t
                if (intoBuf) infoBuf.append("\n```\n").append(cols.last().second).append("\n```\n")
                labels += label
                val tsize = if (cols.size > 1) 7f else 9f
                // One horizontal row of weighted columns. Single-column trees
                // fill the width; Folders splits into 3 equal columns. Each
                // column scrolls horizontally on its own and LONG-PRESS-copies.
                val colRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    visibility = android.view.View.GONE
                }
                for ((sub, txt) in cols) {
                    val colName = if (sub.isBlank()) label else "$label · $sub"
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                        ).apply { rightMargin = if (cols.size > 1) dp(3) else 0 }
                    }
                    if (sub.isNotBlank()) cell.addView(TextView(ctx).apply {
                        text = sub
                        setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
                        typeface = Typeface.DEFAULT_BOLD
                        textSize = 10f
                        setPadding(dp(4), dp(4), dp(4), dp(2))
                    })
                    cell.addView(android.widget.HorizontalScrollView(ctx).apply {
                        // No setTextIsSelectable — it would hijack the long-press
                        // into a text-selection gesture. Long-press copies instead.
                        addView(TextView(ctx).apply {
                            text = txt
                            setTextColor(0xFFE9D8FD.toInt())
                            typeface = Typeface.MONOSPACE
                            textSize = tsize
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            setBackgroundColor(0x33000000)
                            isLongClickable = true
                            setOnLongClickListener { copyTree(colName, txt); true }
                        })
                    })
                    colRow.addView(cell)
                }
                scrollers += colRow
                val btnCopy = if (cols.size == 1) cols[0].second
                    else cols.joinToString("\n\n") { "## ${it.first}\n${it.second}" }
                val btn = TextView(ctx).apply {
                    text = "▸ $label"
                    gravity = android.view.Gravity.CENTER
                    setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    setBackgroundColor(0x22FFFFFF)
                    isClickable = true; isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { rightMargin = dp(4) }
                    setOnClickListener {
                        // Accordion: open this one only if it was closed; tapping
                        // the open one closes everything.
                        val wasOpen = scrollers[idx].visibility == android.view.View.VISIBLE
                        setOpen(if (wasOpen) -1 else idx)
                    }
                    setOnLongClickListener { copyTree(label, btnCopy); true }
                }
                buttons += btn
                btnRow.addView(btn)
            }
            box.addView(btnRow)
            scrollers.forEach { box.addView(it) }
        }

        section(ctx, column, "Locale & time") {
            val locales = if (android.os.Build.VERSION.SDK_INT >= 24)
                (0 until resources.configuration.locales.size()).joinToString(", ") {
                    resources.configuration.locales[it].toLanguageTag()
                }
            else
                @Suppress("DEPRECATION") resources.configuration.locale.toLanguageTag()
            row(ctx, it, "Locales",  locales)
            val tz = TimeZone.getDefault()
            row(ctx, it, "Timezone", "${tz.id} (${tz.displayName})")
            val offMin = tz.rawOffset / 60000
            row(ctx, it, "UTC offset", "%+03d:%02d".format(offMin / 60, kotlin.math.abs(offMin) % 60))
            row(ctx, it, "In DST",   tz.inDaylightTime(Date()).toString())
            val autoTime = runCatching {
                android.provider.Settings.Global.getInt(ctxAny().contentResolver,
                    android.provider.Settings.Global.AUTO_TIME) == 1
            }.getOrDefault(false)
            row(ctx, it, "Auto time", autoTime.toString())
            // System uptime gap = boot wall-clock.
            val bootWall = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            row(ctx, it, "Booted",   fmtMillis(bootWall))
            row(ctx, it, "System uptime", fmtDuration(android.os.SystemClock.elapsedRealtime()))
        }

        // Tail-anchor: snapshot the entire About page to the clipboard.
        // The accumulator [infoBuf] is filled during render by every
        // title / section / row helper + the inline folder-tree block,
        // so this captures whatever was actually drawn — no parallel
        // data collection to keep in sync.
        column.addView(actionButton(ctx, "Copy All Infos") {
            val snapshot = infoBuf.toString()
            copy(ctx, snapshot)
            Toast.makeText(ctx,
                "Copied ${snapshot.length} chars (${snapshot.count { it == '\n' }} lines)",
                Toast.LENGTH_SHORT).show()
        })

        return scroll
    }

    // ── helpers ──────────────────────────────────────────────────────

    // Known on-device terminal emulators that can run an sshd. The sshd port is
    // user-configurable, so each probes a list of common candidates (8022 is the
    // Termux/nix default; 8024 and 22 are common manual choices).
    private data class SshTerminal(val label: String, val pkg: String, val ports: List<Int>)
    private val SSH_TERMINALS = listOf(
        SshTerminal("Termux", "com.termux", listOf(8022, 8024, 22)),
        SshTerminal("Nix-on-droid", "com.termux.nix", listOf(8022, 8024, 22)),
        SshTerminal("Termux (F-Droid)", "com.termux.fdroid", listOf(8022, 8024, 22)),
        SshTerminal("UserLAnd", "tech.ula", listOf(2022, 8022, 22)),
    )

    /** Installed? Needs a <queries> entry in the manifest on API 30+ (added). */
    private fun isPackageInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    /** Is something listening on host:port? Short-timeout TCP connect.
     *  MUST run off the main thread (Android blocks network on the UI thread). */
    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 350); true }
    }.getOrDefault(false)

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
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(ctx).apply {
            text = key
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        // Capture the value TextView so callers that need to update it
        // later (e.g. async Health Connect lookup) can rebind .text
        // without rebuilding the row. Plain callers ignore the return.
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
        row.addView(valueView)
        host.addView(row)
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
        this.text = text
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun actionButton(ctx: Context, label: String, bg: Int = 0xFF7C3AED.toInt(), onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(bg)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        layoutParams = lp
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Horizontal row of equal-width action buttons. Each button gets
     *  weight=1 so 5 buttons across share the row evenly. Multi-line
     *  text allowed (maxLines = 3) so the "Set Battery No Optimization"
     *  label can wrap without overflowing the chip. Padding tightened
     *  vs the single-button variant so 5 tall labels still fit
     *  comfortably on narrow screens. */
    private fun actionButtonRow(ctx: Context, vararg buttons: Pair<String, () -> Unit>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            layoutParams = lp
        }
        val gap = dp(4)
        for ((idx, pair) in buttons.withIndex()) {
            val (label, onClick) = pair
            val btn = TextView(ctx).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF7C3AED.toInt())
                gravity = android.view.Gravity.CENTER
                textSize = 12f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                maxLines = 3
                minHeight = dp(64)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (idx > 0) lp.leftMargin = gap
                layoutParams = lp
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
            row.addView(btn)
        }
        return row
    }

    private fun copy(ctx: Context, v: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("about", v))
    }

    /** Deep BatteryManager + sticky-intent dump — rendered inline inside
     *  the "Battery & Usage" section (merged from the former standalone
     *  "Battery (deep)" section so there's a single Battery section). */
    private fun renderBatteryDeep(ctx: Context, host: LinearLayout) {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val battery = runCatching {
            ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level   = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,  -1) ?: -1
        val scale   = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,  -1) ?: -1
        val status  = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val tech    = battery?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "—"
        val tempDeci = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val mvolts  = battery?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val health  = battery?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val pct = if (level >= 0 && scale > 0) "%d %%".format(level * 100 / scale) else "—"
        val statusStr = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING    -> "Charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL        -> "Full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "—"
        }
        val pluggedStr = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC       -> "AC"
            android.os.BatteryManager.BATTERY_PLUGGED_USB      -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Unplugged"
            else -> "—"
        }
        val healthStr = when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD              -> "Good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT          -> "Overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD              -> "Dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE      -> "Over voltage"
            android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            android.os.BatteryManager.BATTERY_HEALTH_COLD              -> "Cold"
            else -> "—"
        }
        row(ctx, host, "Level",       pct)
        row(ctx, host, "Status",      statusStr)
        row(ctx, host, "Power source", pluggedStr)
        row(ctx, host, "Health",      healthStr)
        row(ctx, host, "Technology",  tech)
        row(ctx, host, "Temperature", if (tempDeci >= 0) "%.1f °C".format(tempDeci / 10.0) else "—")
        row(ctx, host, "Voltage",     if (mvolts >= 0) "%d mV".format(mvolts) else "—")

        if (bm != null) {
            val curNowMicro = runCatching { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
            val curAvgMicro = runCatching { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }.getOrDefault(0)
            val chargeCounterUah = runCatching { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(0)
            val energyCounterNwh = runCatching { bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }.getOrDefault(0L)
            row(ctx, host, "Current (now)", if (curNowMicro != Int.MIN_VALUE) "%d mA".format(curNowMicro / 1000) else "—")
            row(ctx, host, "Current (avg)", if (curAvgMicro != Int.MIN_VALUE) "%d mA".format(curAvgMicro / 1000) else "—")
            row(ctx, host, "Charge counter", if (chargeCounterUah > 0) "%d mAh".format(chargeCounterUah / 1000) else "—")
            row(ctx, host, "Energy counter", if (energyCounterNwh > 0) "%d µWh".format(energyCounterNwh) else "—")
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val cycles = runCatching { bm.getIntProperty(7) }.getOrDefault(-1)
                row(ctx, host, "Cycle count", if (cycles >= 0) cycles.toString() else "—")
            }
            val remainingMs = runCatching { bm.computeChargeTimeRemaining() }.getOrDefault(-1L)
            if (remainingMs > 0) row(ctx, host, "Time to full", fmtDuration(remainingMs))
        }
    }

    /** Big macro-section divider ("☁ CLOUD", "📱 PHONE") splitting the
     *  About page into the cloud-identity half and the phone-device half. */
    private fun macroHeader(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setBackgroundColor(0x22B794F4.toInt())
        val p = dp(8)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20) }
        layoutParams = lp
        setPadding(p, dp(12), p, dp(10))
    }

    /** Repo {label,url} list for the Profile section — data-driven from
     *  build.json::ui.profile_default.repos (UI_PROFILE_REPOS_B64). */
    private fun parseProfileRepos(): List<Pair<String, String>> = runCatching {
        val json = String(android.util.Base64.decode(BuildConfig.UI_PROFILE_REPOS_B64, android.util.Base64.NO_WRAP))
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("label").ifBlank { "repo" } to o.optString("url")
        }
    }.getOrDefault(emptyList())

    /** VPN / WireGuard / Mesh body — the WG interface state PLUS a live
     *  reachability ping of EVERY declared mesh member (Sections.mesh()
     *  nodes, baked from the consolidated cloud-data config), not just
     *  the configured WG peers. Live rx/tx + handshake attach to a node
     *  when its wg-IP matches a configured peer's tunnel and the tunnel
     *  is UP. Rendered inside a section() under the Cloud macro header. */
    private fun renderVpnMesh(ctx: Context, host: LinearLayout) {
        val wgPrefs = WgState.prefs(ctx)
        val backend = runCatching { WgState.backend(ctx) }.getOrNull()
        val tunnel  = WgState.tunnel
        val state   = runCatching { backend?.getState(tunnel) }.getOrNull()
        row(ctx, host, "Tunnel name", wgPrefs.tunnelName.ifBlank { "—" })
        row(ctx, host, "State",       state?.name ?: "—")
        row(ctx, host, "Backend",     "libwg-go ${runCatching { backend?.version }.getOrNull() ?: "—"}")
        row(ctx, host, "Always-on",   runCatching { backend?.isAlwaysOn?.toString() }.getOrNull() ?: "—")
        row(ctx, host, "Lockdown",    runCatching { backend?.isLockdownEnabled?.toString() }.getOrNull() ?: "—")
        row(ctx, host, "Configured peers", wgPrefs.peers().size.toString())

        // Live per-peer stats keyed by pubkey (only when tunnel UP), then
        // re-keyed by the peer's mesh IP so we can attach to mesh nodes.
        val statsByKey = HashMap<String, com.wireguard.android.backend.Statistics.PeerStats?>()
        if (state == Tunnel.State.UP) {
            runCatching { backend?.getStatistics(tunnel) }.getOrNull()?.let { stats ->
                for (key in stats.peers()) {
                    val k64 = runCatching { key.toBase64() }.getOrDefault("")
                    if (k64.isNotEmpty()) statsByKey[k64] = stats.peer(key)
                }
            }
        }
        val statsByMeshIp = HashMap<String, com.wireguard.android.backend.Statistics.PeerStats>()
        for (p in wgPrefs.peers()) {
            val ip = meshIpOf(p) ?: continue
            statsByKey[p.publicKey.trim()]?.let { statsByMeshIp[ip] = it }
        }

        // FULL mesh — every declared member from the baked consolidated config.
        val nodes = runCatching { com.diegonmarcos.superapp.launcher.Sections.mesh().nodes }.getOrDefault(emptyList())
        host.addView(small(ctx, "Full mesh — ${nodes.size} declared members (consolidated config). Live wg0 reachability ping:"))
        val pingTargets = ArrayList<Pair<String, TextView>>()
        for (n in nodes) {
            val name = "${n.alias.ifBlank { n.name }} · ${n.role}"
            host.addView(small(ctx, "— $name —"))
            row(ctx, host, "$name · wg ip", n.wgIp.ifBlank { "—" })
            if (n.publicIp.isNotBlank()) row(ctx, host, "$name · public ip", n.publicIp)
            val provReg = listOf(n.provider, n.region).filter { it.isNotBlank() }.joinToString(" · ")
            if (provReg.isNotBlank()) row(ctx, host, "$name · provider", provReg)
            statsByMeshIp[n.wgIp]?.let { ps ->
                val hsAge = if (ps.latestHandshakeEpochMillis > 0)
                    fmtDuration(System.currentTimeMillis() - ps.latestHandshakeEpochMillis) + " ago" else "never"
                row(ctx, host, "$name · rx/tx",     "${sizeStr(ps.rxBytes)} / ${sizeStr(ps.txBytes)}")
                row(ctx, host, "$name · handshake", hsAge)
            }
            if (n.wgIp.isNotBlank()) {
                val pingRow = row(ctx, host, "$name · ping ${n.wgIp}", "pinging…")
                pingTargets.add(n.wgIp to pingRow)
            }
        }
        if (nodes.isEmpty()) host.addView(small(ctx, "No mesh members in the baked consolidated config (data/mesh.json)."))

        if (pingTargets.isNotEmpty()) {
            Thread {
                for ((ip, view) in pingTargets) {
                    val res = pingHost(ip, 1500)
                    view.post { view.text = res }
                }
            }.start()
        }
        host.addView(actionButton(ctx, "Re-ping all mesh members") { rebuildFragment() })
    }

    /** The mesh IP to ping for a WG peer — prefer a /32 allowed-IP,
     *  then any non-network IPv4 allowed-IP, finally the endpoint host.
     *  Null when nothing pingable can be derived. */
    private fun meshIpOf(p: WireGuardPrefs.PeerData): String? {
        val cidrs = p.allowedIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val ipv4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        cidrs.firstOrNull { it.endsWith("/32") }?.substringBefore('/')?.let { return it }
        cidrs.map { it.substringBefore('/') }
            .firstOrNull { it.matches(ipv4) && it != "0.0.0.0" && !it.endsWith(".0") }
            ?.let { return it }
        return p.endpoint.substringBefore(':').trim().ifBlank { null }
    }

    /** Reachability probe for [ip] — ICMP echo where allowed, else a TCP
     *  echo handshake (InetAddress.isReachable's fallback). Returns a
     *  ✓ latency / ✗ timeout string. Call OFF the main thread. */
    private fun pingHost(ip: String, timeoutMs: Int): String = try {
        val addr = java.net.InetAddress.getByName(ip)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val ok = addr.isReachable(timeoutMs)
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        if (ok) "✓ reachable · ${dt} ms" else "✗ no answer (${timeoutMs} ms)"
    } catch (e: Exception) {
        "✗ ${e.message ?: "unreachable"}"
    }

    /** Cross-app IPC surface this app exposes/consumes — the manifest's
     *  exported activities/services/providers/receivers with intent
     *  filters (the contract other apps in the constellation can bind
     *  to). Empty list → nothing declared (the section shows a note). */
    private fun collectIpcContract(ctx: Context): List<Pair<String, String>> = runCatching {
        val pm = ctx.packageManager
        val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
            android.content.pm.PackageManager.GET_SERVICES or
            android.content.pm.PackageManager.GET_PROVIDERS or
            android.content.pm.PackageManager.GET_RECEIVERS
        val pi = pm.getPackageInfo(ctx.packageName, flags)
        val out = ArrayList<Pair<String, String>>()
        fun shortName(n: String) = n.removePrefix(ctx.packageName).removePrefix(".")
        pi.activities?.filter { it.exported }?.forEach { out.add("Activity (exported)" to shortName(it.name)) }
        pi.services?.filter { it.exported }?.forEach { out.add("Service (exported)" to shortName(it.name)) }
        pi.providers?.filter { it.exported }?.forEach { out.add("Provider (exported)" to (it.authority ?: shortName(it.name))) }
        pi.receivers?.filter { it.exported }?.forEach { out.add("Receiver (exported)" to shortName(it.name)) }
        out
    }.getOrDefault(emptyList())

    private fun dirSize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun sizeStr(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1024          -> "%.1f KiB".format(bytes / 1024.0)
        else                   -> "$bytes B"
    }

    /** (free, total) bytes for the filesystem hosting [path]. Returns
     *  (-1, -1) when StatFs throws — happens on early-API or unmounted
     *  external paths. */
    private fun statFsBytes(path: String): Pair<Long, Long> = runCatching {
        val s = android.os.StatFs(path)
        s.availableBytes to s.totalBytes
    }.getOrDefault(-1L to -1L)

    /** (swapTotalBytes, swapFreeBytes) from /proc/meminfo. -1L on parse
     *  failure; 0L SwapTotal means the kernel has no swap configured
     *  (caller should render "disabled"). Values in /proc/meminfo are
     *  in kB — multiplied to bytes here so callers stay in sizeStr units. */
    private fun readSwapBytes(): Pair<Long, Long> = runCatching {
        val text = File("/proc/meminfo").readText()
        fun kb(field: String): Long {
            val m = Regex("(?m)^${field}:\\s+(\\d+)\\s+kB").find(text)
                ?: return -1L
            return m.groupValues[1].toLong() * 1024L
        }
        kb("SwapTotal") to kb("SwapFree")
    }.getOrDefault(-1L to -1L)

    private fun fmtMillis(ms: Long): String =
        DateFormat.getDateTimeInstance().format(Date(ms))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance() = DevControlFragment()

        // Neutral gray for utility buttons (Regenerate token, Open Wireless Debugging).
        private val GRAY = 0xFF4B5563.toInt()
    }
}
