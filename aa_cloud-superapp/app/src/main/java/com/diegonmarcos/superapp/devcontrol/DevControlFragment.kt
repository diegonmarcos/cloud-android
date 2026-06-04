package com.diegonmarcos.superapp.devcontrol

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
import com.diegonmarcos.superapp.AppProcessUptime
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.updater.BuildConfig as UpdBuildConfig
import com.diegonmarcos.superapp.PermAskTracker
import com.diegonmarcos.superapp.Sections
import com.diegonmarcos.superapp.WgState
import com.wireguard.android.backend.Tunnel
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/**
 * About page — comprehensive runtime + build metadata for the user
 * to inspect. Long-press any monospace row to copy to clipboard.
 */
class DevControlFragment : Fragment() {

    /** Standard permission request — wired to the Notifications button
     *  in the Permissions section. Re-renders the fragment on result so
     *  the row's ✓ / ✗ updates without a manual refresh. */
    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            Toast.makeText(requireContext(),
                if (it) "Notifications: granted" else "Notifications: denied",
                Toast.LENGTH_SHORT).show()
            rebuildFragment()
        }

    /** Bulk request — wired to the "Request All Permissions" button. */
    private val allPermsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.count { it.value }
            val denied  = result.size - granted
            Toast.makeText(requireContext(),
                "Permissions: $granted granted, $denied denied", Toast.LENGTH_SHORT).show()
            rebuildFragment()
        }

    /** Rebuilds this fragment in place so every Permission row re-reads
     *  its current grant state. Cheaper than rendering a full diff. */
    private fun rebuildFragment() {
        parentFragmentManager.beginTransaction()
            .detach(this).commitNow()
        parentFragmentManager.beginTransaction()
            .attach(this).commitNow()
    }

    private fun ctxAny(): Context = requireContext()

    private fun requestNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Track that we asked so the "Denied (don't ask)" vs "Not
            // requested" disambiguation works on future renders.
            PermAskTracker(requireContext()).markAsked("android.permission.POST_NOTIFICATIONS")
            notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
        } else {
            Toast.makeText(requireContext(),
                "Pre-API 33 — notifications granted by default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAllPermissions(perms: Array<String>) {
        PermAskTracker(requireContext()).markAskedAll(perms.toList())
        allPermsLauncher.launch(perms)
    }

    /**
     * Resolve the user-facing state for a single permission. Beyond
     * the GRANTED/DENIED binary that `checkSelfPermission` returns,
     * Android distinguishes — at the UX level — between:
     *   • ⏳ Ask each time      (denied, but `shouldShowRationale=true`)
     *   • ✗ Denied (don't ask) (denied, no rationale, asked before)
     *   • ◯ Not requested      (denied, no rationale, never asked)
     * Disambiguation uses [PermAskTracker] since Android exposes no
     * API to tell "never asked" apart from "permanently denied".
     */
    private fun permissionState(perm: String): String {
        val ctx = ctxAny()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return "✓ Granted"
        val act = activity ?: return "✗ Denied"
        val rationale = runCatching {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
        }.getOrDefault(false)
        if (rationale) return "⏳ Ask each time"
        val askedBefore = PermAskTracker(ctx).hasBeenRequested(perm)
        return if (askedBefore) "✗ Denied (don't ask)" else "◯ Not requested"
    }

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

    /** Open the Usage Access settings list so the user can flip our
     *  PACKAGE_USAGE_STATS toggle on. After granting, the Screen-on /
     *  Background rows in the Battery & Usage section start reading
     *  real numbers instead of "Needs Usage Access". */
    private fun openUsageAccessSettings() {
        runCatching {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
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

    private fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null),
        )
        startActivity(intent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
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
            val path = info.applicationInfo.sourceDir
            val size = runCatching { File(path).length() }.getOrDefault(0L)
            row(ctx, it, "Path",       path)
            row(ctx, it, "Size",       sizeStr(size))
            row(ctx, it, "Installed",  fmtMillis(info.firstInstallTime))
            row(ctx, it, "Updated",    fmtMillis(info.lastUpdateTime))
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
            val apkBytes  = runCatching { File(pkg.applicationInfo.sourceDir).length() }.getOrDefault(0L)
            // "Datos" = the app's private data root — includes filesDir,
            // databases, shared_prefs, and everything else the app
            // persists outside of cacheDir.
            val dataDir   = File(pkg.applicationInfo.dataDir)
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

        section(ctx, column, "Permissions") {
            // Each row: friendly label + runtime grant state. Permissions
            // declared in AndroidManifest; here we just read the current
            // grant. Notifications gets a dedicated request button below
            // — the only one Android lets us trigger from inside the app.
            val perms = listOf(
                "Camera"            to android.Manifest.permission.CAMERA,
                "Microphone"        to android.Manifest.permission.RECORD_AUDIO,
                "Notifications"     to "android.permission.POST_NOTIFICATIONS",
                "Location (fine)"   to android.Manifest.permission.ACCESS_FINE_LOCATION,
                "Location (coarse)" to android.Manifest.permission.ACCESS_COARSE_LOCATION,
                "Nearby Devices"    to "android.permission.BLUETOOTH_SCAN",
                "Bluetooth Connect" to "android.permission.BLUETOOTH_CONNECT",
                "Contacts"          to android.Manifest.permission.READ_CONTACTS,
                "Music and Audio"   to "android.permission.READ_MEDIA_AUDIO",
                "Photos and Videos (img)" to "android.permission.READ_MEDIA_IMAGES",
                "Photos and Videos (vid)" to "android.permission.READ_MEDIA_VIDEO",
                "SMS"               to android.Manifest.permission.READ_SMS,
                "Phone"             to android.Manifest.permission.READ_PHONE_STATE,
            )
            it.addView(small(ctx, "States: ✓ Granted · ⏳ Ask each time · ✗ Denied (don't ask) · ◯ Not requested. Restricted-by-policy permissions (SMS, Phone, Call Log) often stay at ✗ Denied (don't ask) on non-default handlers — toggle via system settings."))
            for ((label, perm) in perms) {
                row(ctx, it, label, permissionState(perm))
            }
            // Button row: request POST_NOTIFICATIONS via the standard
            // permission dialog (API 33+). On older Android levels
            // notifications are granted by default, so we just toast.
            it.addView(actionButton(ctx, "Request Notifications permission") {
                requestNotificationsPermission()
            })
            // Bulk request — fires the multi-permission system flow for
            // every runtime-grantable permission the manifest declares.
            // Android folds them into 1-N system dialogs depending on
            // group rules (Location prompts as one group, etc.).
            it.addView(actionButton(ctx, "Request All Permissions") {
                requestAllPermissions(perms.map { p -> p.second }.toTypedArray())
            })
            // Deeplink to the app's system-settings page so the user can
            // toggle the other permissions directly from there.
            it.addView(actionButton(ctx, "Open system app settings") {
                openAppSettings()
            })
        }

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
            it.addView(small(ctx, "Battery-stats internals (mAh per-component, wakelocks, wakeups) are system-only — tap the button below for the full OS report."))

            it.addView(actionButton(ctx, "Open battery usage details") {
                openBatteryDetails()
            })
            it.addView(actionButton(ctx, "Grant Usage Access") {
                openUsageAccessSettings()
            })
        }

        section(ctx, column, "VPN / WireGuard") {
            // Reads the shared GoBackend that WireGuardFragment uses.
            // getState() reflects the runtime tunnel; getStatistics()
            // returns per-peer rx/tx bytes + last handshake epoch.
            val wgPrefs = WgState.prefs(ctxAny())
            val backend = runCatching { WgState.backend(ctxAny()) }.getOrNull()
            val tunnel  = WgState.tunnel
            val state   = runCatching { backend?.getState(tunnel) }.getOrNull()
            row(ctx, it, "Tunnel name", wgPrefs.tunnelName.ifBlank { "—" })
            row(ctx, it, "State",       state?.name ?: "—")
            row(ctx, it, "Backend",     "libwg-go ${runCatching { backend?.version }.getOrNull() ?: "—"}")
            row(ctx, it, "Always-on",   runCatching { backend?.isAlwaysOn?.toString() }.getOrNull() ?: "—")
            row(ctx, it, "Lockdown",    runCatching { backend?.isLockdownEnabled?.toString() }.getOrNull() ?: "—")
            row(ctx, it, "Configured peers", wgPrefs.peers().size.toString())

            // Per-peer stats: only populated when the tunnel is UP.
            if (state == Tunnel.State.UP) {
                val stats = runCatching { backend?.getStatistics(tunnel) }.getOrNull()
                if (stats == null) {
                    it.addView(small(ctx, "Statistics not available."))
                } else {
                    val keys = stats.peers()
                    if (keys.isEmpty()) {
                        it.addView(small(ctx, "Tunnel up but no peer stats yet — handshake not completed."))
                    }
                    // Match stats.peers() keys (Curve25519 public keys)
                    // back to the user-named peers in WireGuardPrefs.
                    val nameByKey = wgPrefs.peers().associate { p ->
                        p.publicKey.trim() to p.name.ifBlank { "peer" }
                    }
                    for ((i, key) in keys.withIndex()) {
                        val ps = stats.peer(key) ?: continue
                        val k64 = runCatching { key.toBase64() }.getOrDefault("")
                        val name = nameByKey[k64] ?: "peer #${i + 1}"
                        val hsAge = if (ps.latestHandshakeEpochMillis > 0)
                            fmtDuration(System.currentTimeMillis() - ps.latestHandshakeEpochMillis) + " ago"
                        else "never"
                        row(ctx, it, "$name · pubkey",  k64.take(8) + "…" + k64.takeLast(6))
                        row(ctx, it, "$name · rx/tx",   "${sizeStr(ps.rxBytes)} / ${sizeStr(ps.txBytes)}")
                        row(ctx, it, "$name · handshake", hsAge)
                    }
                }
            } else {
                it.addView(small(ctx, "Per-peer stats appear once the tunnel is UP and a handshake completes."))
            }
        }

        section(ctx, column, "Battery (deep)") {
            // Direct read from sticky battery intent + BatteryManager
            // properties. cycle_count is the Android 14+ goodie.
            val ctxAny = requireContext()
            val bm = ctxAny.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val battery = runCatching {
                ctxAny.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
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
            row(ctx, it, "Level",       pct)
            row(ctx, it, "Status",      statusStr)
            row(ctx, it, "Power source", pluggedStr)
            row(ctx, it, "Health",      healthStr)
            row(ctx, it, "Technology",  tech)
            row(ctx, it, "Temperature", if (tempDeci >= 0) "%.1f °C".format(tempDeci / 10.0) else "—")
            row(ctx, it, "Voltage",     if (mvolts >= 0) "%d mV".format(mvolts) else "—")

            // BatteryManager properties: instant µA / µAh / µWh. cycle
            // count is API 34+ (constant value 7); query optimistically.
            if (bm != null) {
                val curNowMicro = runCatching { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
                val curAvgMicro = runCatching { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }.getOrDefault(0)
                val chargeCounterUah = runCatching { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(0)
                val energyCounterNwh = runCatching { bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }.getOrDefault(0L)
                row(ctx, it, "Current (now)", if (curNowMicro != Int.MIN_VALUE) "%d mA".format(curNowMicro / 1000) else "—")
                row(ctx, it, "Current (avg)", if (curAvgMicro != Int.MIN_VALUE) "%d mA".format(curAvgMicro / 1000) else "—")
                row(ctx, it, "Charge counter", if (chargeCounterUah > 0) "%d mAh".format(chargeCounterUah / 1000) else "—")
                row(ctx, it, "Energy counter", if (energyCounterNwh > 0) "%d µWh".format(energyCounterNwh) else "—")
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // BATTERY_PROPERTY_CYCLE_COUNT = 7 (API 34+).
                    val cycles = runCatching { bm.getIntProperty(7) }.getOrDefault(-1)
                    row(ctx, it, "Cycle count", if (cycles >= 0) cycles.toString() else "—")
                }
                val remainingMs = runCatching { bm.computeChargeTimeRemaining() }.getOrDefault(-1L)
                if (remainingMs > 0) row(ctx, it, "Time to full", fmtDuration(remainingMs))
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

        section(ctx, column, "Dev control HTTP") {
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
            it.addView(small(ctx, "Bearer token — long-press to copy. Endpoints: /ping /info /state /haptic /goto /action /update /restart /logcat /trace /crashes"))

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
            it.addView(actionButton(ctx, "Regenerate API token") {
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
        }

        section(ctx, column, "Curl shortcuts") {
            val port = DevControlPrefs(requireContext()).port
            val tok  = DevControlPrefs(requireContext()).token
            row(ctx, it, "Logcat",  "curl http://127.0.0.1:$port/logcat?n=500")
            row(ctx, it, "Trace",   "curl http://127.0.0.1:$port/trace")
            row(ctx, it, "Crashes", "curl http://127.0.0.1:$port/crashes")
            row(ctx, it, "Haptic",  "curl -XPOST -H 'Authorization: Bearer $tok' 'http://127.0.0.1:$port/haptic?preset=gemini_stream'")
            row(ctx, it, "Update",  "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/update")
            row(ctx, it, "Restart", "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/restart")
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
            // Split APKs (base + per-density + per-ABI)
            @Suppress("DEPRECATION")
            val appInfo = pm.getPackageInfo(pkg, 0).applicationInfo
            val splits = appInfo.splitSourceDirs?.size ?: 0
            row(ctx, it, "Splits",      "$splits split APK(s)")
            row(ctx, it, "Native lib dir", appInfo.nativeLibraryDir ?: "—")
            val nativeLibs = runCatching { File(appInfo.nativeLibraryDir ?: "").listFiles()?.map { it.name } }.getOrNull()
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
            val ai = pm2.getPackageInfo(ctxAny().packageName, 0).applicationInfo
            val splits = ai.splitSourceDirs
            row(ctx, it, "Base APK", File(ai.sourceDir).name + " · " + sizeStr(File(ai.sourceDir).length()))
            splits?.forEach { s ->
                row(ctx, it, "Split", File(s).name + " · " + sizeStr(File(s).length()))
            }

            // Build-time metrics. STACK_GRADLE_CONFIG_MS is the elapsed
            // time of app/build.gradle's config phase (NOT the full
            // Gradle+AGP build — that's only readable in GHA logs).
            it.addView(small(ctx, "Build metrics (this APK):"))
            row(ctx, it, "Gradle config phase", "%d ms".format(BuildConfig.STACK_GRADLE_CONFIG_MS))
            row(ctx, it, "Build SHA", BuildConfig.GIT_SHORT_SHA)
            row(ctx, it, "Built UTC", BuildConfig.BUILD_TIMESTAMP)
            it.addView(small(ctx, "Full assemble + native-build duration lives in GitHub Actions — open the run on github.com/diegonmarcos/unix/actions for the wall-clock number."))
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
        return scroll
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun section(ctx: Context, host: LinearLayout, head: String, body: (LinearLayout) -> Unit) {
        host.addView(sectionHeader(ctx, head))
        val grp = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        host.addView(grp)
        body(grp)
    }

    private fun row(ctx: Context, host: LinearLayout, key: String, value: String) {
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
        row.addView(TextView(ctx).apply {
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
        })
        host.addView(row)
    }

    private fun title(ctx: Context, text: String) = TextView(ctx).apply {
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

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF7C3AED.toInt())
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

    private fun copy(ctx: Context, v: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("about", v))
    }

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

    private fun fmtMillis(ms: Long): String =
        DateFormat.getDateTimeInstance().format(Date(ms))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = DevControlFragment() }
}
