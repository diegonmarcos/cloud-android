package com.diegonmarcos.superapp.devcontrol
import com.diegonmarcos.devcontrol.DevControlServer

import android.content.Context
import com.diegonmarcos.devcontrol.DevControlEndpoints
import com.diegonmarcos.devcontrol.DevControlReply

/**
 * App-side provider for the loopback DevControlServer's app-SPECIFIC ops —
 * phone / battery / sysfs / energy / adb. Registered via DevControl.endpoints
 * (see MainActivity). ALL `com.diegonmarcos.superapp.*` subsystem references
 * live here, keeping libs:devcontrol app-agnostic + symlinkable. The generic
 * ops (system/diagnostics/nav/haptic/state) stay in the lib server; anything it
 * doesn't own falls through to [handle].
 *
 * Helper functions below were moved verbatim from DevControlServer.
 */
object DevControlEndpointsProvider : DevControlEndpoints {

    override fun handle(ctx: Context, op: String, query: Map<String, String>): DevControlReply? = when (op) {
        "phone/classify"       -> ok(phoneClassifyJson(ctx))
        "phone/new_apps"       -> ok(phoneNewAppsJson(ctx))
        "battery/state"        -> ok(batteryStateJson(ctx))
        "battery/reset_anchor" -> {
            com.diegonmarcos.superapp.battery.BatterySessionStats.resetAnchor(ctx)
            ok("""{"ok":true,"message":"anchor cleared — next plug/unplug will re-mint via PowerStateReceiver"}""")
        }
        "sysfs/diagnostic"     -> ok(sysfsDiagnosticJson())
        "battery/properties"   -> ok(batteryPropertiesJson(ctx))
        "battery/snapshot"     -> ok(com.diegonmarcos.superapp.battery.ChargeSnapshot.capture(ctx).toString())
        "battery/snapshots"    -> ok(com.diegonmarcos.superapp.battery.ChargeSnapshot.recent(ctx).toString())
        "energy/self"          -> ok(energySelfJson())
        "energy/attribution"   -> ok(energyAttributionJson(ctx))
        "energy/samples"       -> ok(energySamplesJson(ctx))
        "energy/shizuku"       -> ok(energyShizukuJson(ctx))
        "energy/reset"         -> {
            com.diegonmarcos.superapp.battery.EnergyLedger.reset()
            runCatching { com.diegonmarcos.superapp.battery.EnergyStore(ctx).clear() }
            ok("""{"ok":true,"message":"energy ledger + sample store cleared"}""")
        }
        "adb/status"           -> ok(adbStatusJson(ctx))
        "adb/netinfo"          -> ok(adbNetInfoJson(ctx))
        "adb/pair"             -> {
            val host = query["host"] ?: "127.0.0.1"
            val port = query["port"]?.toIntOrNull()
            val code = query["code"]
            if (port == null || code.isNullOrBlank())
                DevControlReply("400 Bad Request", "need host=<ip>&port=<pairPort>&code=<6digits>\n", "text/plain")
            else {
                val (o, m) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.pair(ctx, host, port, code)
                ok("""{"ok":$o,"message":"${jsonEscape(m)}"}""")
            }
        }
        "adb/autoconnect"      -> {
            val (o, m) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.autoConnect(ctx)
            ok("""{"ok":$o,"message":"${jsonEscape(m)}"}""")
        }
        "adb/connect"          -> {
            val host = query["host"] ?: "127.0.0.1"
            val port = query["port"]?.toIntOrNull()
            if (port == null)
                DevControlReply("400 Bad Request", "need host=<ip>&port=<connectPort>\n", "text/plain")
            else {
                val (o, m) = com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel.connect(ctx, host, port)
                ok("""{"ok":$o,"message":"${jsonEscape(m)}"}""")
            }
        }
        "adb/server-command"   -> {
            val cmd = com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.serverCommand(ctx)
            ok("""{"command":"${jsonEscape(cmd)}","port":${com.diegonmarcos.superapp.adbdebug.AdbShellBootstrap.port()},"note":"Run once per boot. Grants the SHELL SELinux domain our server needs; after this the app is self-contained (no Shizuku app)."}""")
        }
        "adb/diagnostics"      -> {
            val bundle = query["bundle"] ?: "charger"
            ok(com.diegonmarcos.superapp.adbdebug.AdbDiagnostics.runBundle(ctx, bundle))
        }
        "adb/exec"             -> {
            val cmd = query["cmd"]
            if (cmd.isNullOrBlank())
                DevControlReply("400 Bad Request", "missing cmd\n", "text/plain")
            else {
                val ch = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
                val out = ch?.exec(ctx, cmd)
                    ?: "ERR: no shell channel ready — ${com.diegonmarcos.superapp.adbdebug.LocalShellChannel.status(ctx)}\n"
                DevControlReply(body = out, contentType = "text/plain")
            }
        }
        "adb/grant-dump"       -> {
            val ch = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
            val out = ch?.exec(ctx, "pm grant ${ctx.packageName} android.permission.DUMP")
            val held = ctx.checkSelfPermission("android.permission.DUMP") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ok("""{"channel":"${jsonEscape(ch?.name() ?: "none")}","ran":${out != null},"held":$held,"output":"${jsonEscape(out ?: "no shell channel ready")}"}""")
        }
        "adb/sfc"              -> ok(com.diegonmarcos.superapp.adbdebug.SfcVerdict.run(ctx))
        else                   -> null
    }

    private fun ok(body: String) = DevControlReply(body = body, contentType = "application/json")

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    // ════════════ helper functions moved verbatim from DevControlServer ════════════
    /** Walk every launchable activity + report the folder
     *  PhoneAppClassifier routes it to. Debug surface for the Home
     *  Apps/Phone tab — directly answers "why is app X in folder Y?". */
    /** Intra-app energy ledger — which subsystem inside Cloud SuperApp
     *  spent the most CPU / wakeups / bytes since the window start. */
    private fun energySelfJson(): String {
        val (since, stats) = com.diegonmarcos.superapp.battery.EnergyLedger.snapshot()
        val sorted = stats.entries.sortedByDescending { it.value.cpuNanos }
        val sb = StringBuilder("""{"since_ms":""").append(since).append(',')
        sb.append(""""tags":[""")
        var first = true
        for ((tag, s) in sorted) {
            if (!first) sb.append(','); first = false
            sb.append("""{"tag":"""").append(jsonEscape(tag)).append('"').append(',')
            sb.append(""""cpu_ms":""").append(s.cpuNanos / 1_000_000L).append(',')
            sb.append(""""calls":""").append(s.calls).append(',')
            sb.append(""""wakeups":""").append(s.wakeups).append(',')
            sb.append(""""bytes":""").append(s.bytes).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Shizuku exact per-app mAh from dumpsys batterystats (Tier 2). */
    private fun energyShizukuJson(ctx: Context): String {
        val se = com.diegonmarcos.superapp.battery.ShizukuEnergy
        val sb = StringBuilder("{")
        sb.append(""""available":""").append(se.isAvailable()).append(',')
        sb.append(""""granted":""").append(se.isGranted()).append(',')
        sb.append(""""status":"""").append(jsonEscape(se.status())).append('"').append(',')
        val exact = runCatching { se.exact(ctx) }.getOrNull()
        sb.append(""""apps":""")
        if (exact == null) sb.append("null")
        else {
            sb.append('[')
            var first = true
            for (a in exact) {
                if (!first) sb.append(','); first = false
                sb.append("""{"pkg":"""").append(jsonEscape(a.pkg)).append('"').append(',')
                sb.append(""""label":"""").append(jsonEscape(a.label)).append('"').append(',')
                sb.append(""""uid":""").append(a.uid).append(',')
                sb.append(""""mah":""").append("%.2f".format(a.mAh)).append('}')
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    /** Device-level state → draw attribution + per-foreground-app +
     *  our own self cost, computed over the stored sample window. */
    private fun energyAttributionJson(ctx: Context): String {
        val m = com.diegonmarcos.superapp.battery.EnergyWatchdog.attribution(ctx)
        return mapToJson(m)
    }

    /** Raw recent watchdog samples (last 200). */
    private fun energySamplesJson(ctx: Context): String {
        val rows = runCatching { com.diegonmarcos.superapp.battery.EnergyStore(ctx).recent(200) }
            .getOrDefault(emptyList())
        val sb = StringBuilder("[")
        var first = true
        for (s in rows) {
            if (!first) sb.append(','); first = false
            sb.append('{')
            sb.append(""""ts":""").append(s.ts).append(',')
            sb.append(""""draw_ma":""").append(s.drawMa).append(',')
            sb.append(""""batt_pct":""").append(s.battPct).append(',')
            sb.append(""""screen_on":""").append(s.screenOn).append(',')
            sb.append(""""brightness":""").append(s.brightness).append(',')
            sb.append(""""charging":""").append(s.charging).append(',')
            sb.append(""""fg_pkg":"""").append(jsonEscape(s.fgPkg)).append('"').append(',')
            sb.append(""""cpu_load":""").append(s.cpuLoadPct).append(',')
            sb.append(""""mobile_dbm":""").append(s.mobileSignalDbm).append(',')
            sb.append(""""wifi_rssi":""").append(s.wifiRssi).append(',')
            sb.append(""""audio":""").append(s.audioActive).append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Minimal recursive Map/List/primitive → JSON for the attribution
     *  payload (it's already a clean Map<String,Any> tree). */
    private fun mapToJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${jsonEscape(v)}\""
        is Boolean, is Int, is Long, is Double, is Float -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
            "\"${jsonEscape(it.key.toString())}\":${mapToJson(it.value)}"
        }
        is List<*> -> v.joinToString(",", "[", "]") { mapToJson(it) }
        else -> "\"${jsonEscape(v.toString())}\""
    }

    private fun phoneClassifyJson(ctx: Context): String {
        val folders = com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig()
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            as android.content.pm.LauncherApps
        val me = android.os.Process.myUserHandle()
        val pm = ctx.packageManager
        val sb = StringBuilder("[")
        var first = true
        for (info in launcher.getActivityList(null, me)) {
            val pkg = info.applicationInfo.packageName
            val label = info.label.toString()
            val folderId = com.diegonmarcos.superapp.apps.PhoneAppClassifier
                .classify(pkg, label, folders)
            // Install-source debug fields — exactly what
            // PhoneSmartFolders.install_source_not reads, so we can see
            // why a given app does/doesn't land in Alternative Sources.
            var installing: String? = null
            var initiating: String? = null
            var isSystem = false
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                isSystem = (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val src = pm.getInstallSourceInfo(pkg)
                    installing = src.installingPackageName
                    initiating = src.initiatingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    installing = pm.getInstallerPackageName(pkg)
                }
            }
            if (!first) sb.append(','); first = false
            sb.append("""{"pkg":"""").append(jsonEscape(pkg)).append('"').append(',')
            sb.append(""""label":"""").append(jsonEscape(label)).append('"').append(',')
            sb.append(""""folder":"""").append(jsonEscape(folderId)).append('"').append(',')
            sb.append(""""installing":""")
                .append(if (installing == null) "null" else "\"${jsonEscape(installing!!)}\"").append(',')
            sb.append(""""initiating":""")
                .append(if (initiating == null) "null" else "\"${jsonEscape(initiating!!)}\"").append(',')
            sb.append(""""system":""").append(isSystem)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Only the apps that landed in the sink folder (new_apps by
     *  default) — focused view for "what's still uncategorised?". */
    private fun phoneNewAppsJson(ctx: Context): String {
        val folders = com.diegonmarcos.superapp.apps.PhoneFolders.loadFromBuildConfig()
        val sinkId = com.diegonmarcos.superapp.apps.PhoneFolders.sinkFolderId(folders)
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            as android.content.pm.LauncherApps
        val me = android.os.Process.myUserHandle()
        val sb = StringBuilder("""{"sink_folder_id":"""")
        sb.append(jsonEscape(sinkId)).append('"').append(',')
        sb.append(""""apps":[""")
        var first = true
        for (info in launcher.getActivityList(null, me)) {
            val pkg = info.applicationInfo.packageName
            val label = info.label.toString()
            val folderId = com.diegonmarcos.superapp.apps.PhoneAppClassifier
                .classify(pkg, label, folders)
            if (folderId != sinkId) continue
            if (!first) sb.append(','); first = false
            sb.append("""{"pkg":"""").append(jsonEscape(pkg)).append('"').append(',')
            sb.append(""""label":"""").append(jsonEscape(label)).append('"').append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Full BatterySessionStats.Snapshot as JSON. The single source of
     *  truth for debugging battery-rate / anchor regressions —
     *  surfaces EVERY field of the Snapshot data class plus the
     *  derived chargerSpec subobject (so callers can see whether
     *  liveInputW came from sysfs or only from dumpsys). */
    private fun batteryStateJson(ctx: Context): String {
        val s = com.diegonmarcos.superapp.battery.BatterySessionStats.read(ctx)
        val sb = StringBuilder("{")
        sb.append(""""isCharging":""").append(s.isCharging).append(',')
        sb.append(""""curPct":""").append(s.curPct).append(',')
        sb.append(""""nowMs":""").append(s.nowMs).append(',')
        // Discharge anchor block
        sb.append(""""unplugTs":""").append(s.unplugTs).append(',')
        sb.append(""""unplugPct":""").append(s.unplugPct).append(',')
        sb.append(""""unplugAnchorSource":"""").append(jsonEscape(s.unplugAnchorSource)).append('"').append(',')
        sb.append(""""elapsedMs":""").append(s.elapsedMs).append(',')
        sb.append(""""consumedPct":""").append(s.consumedPct).append(',')
        sb.append(""""ratePerMin":""").append(s.ratePerMin).append(',')
        sb.append(""""etaMs":""").append(s.etaMs).append(',')
        sb.append(""""etaDrainedAt":""").append(s.etaDrainedAt).append(',')
        // Charge anchor block
        sb.append(""""plugTs":""").append(s.plugTs).append(',')
        sb.append(""""plugPct":""").append(s.plugPct).append(',')
        sb.append(""""plugAnchorSource":"""").append(jsonEscape(s.plugAnchorSource)).append('"').append(',')
        sb.append(""""chargeElapsedMs":""").append(s.chargeElapsedMs).append(',')
        sb.append(""""gainedPct":""").append(s.gainedPct).append(',')
        sb.append(""""chargeRatePerMin":""").append(s.chargeRatePerMin).append(',')
        sb.append(""""etaFullMs":""").append(s.etaFullMs).append(',')
        sb.append(""""etaFullAt":""").append(s.etaFullAt).append(',')
        // Power readings (BatteryManager)
        sb.append(""""voltageMv":""").append(s.voltageMv).append(',')
        sb.append(""""currentRaw":""").append(s.currentRaw).append(',')
        sb.append(""""currentUa":""").append(s.currentUa).append(',')
        sb.append(""""rescaledMaToUa":""").append(s.rescaledMaToUa).append(',')
        sb.append(""""powerW":""").append(s.powerW).append(',')
        sb.append(""""powerWSource":"""").append(jsonEscape(s.powerWSource)).append('"').append(',')
        // BatteryManager system-service surface (AccuBattery's trick)
        sb.append(""""batteryTempC":""").append(s.batteryTempC).append(',')
        sb.append(""""chargeCounterUah":""").append(s.chargeCounterUah).append(',')
        sb.append(""""cycleCount":""").append(s.cycleCount).append(',')
        sb.append(""""peakChargeCounterUah":""").append(s.peakChargeCounterUah).append(',')
        sb.append(""""cumulativeChargedUah":""").append(s.cumulativeChargedUah).append(',')
        // Charger spec subobject
        sb.append(""""chargerSpec":{""")
        sb.append(""""maxCurrentUa":""").append(s.chargerSpec.maxCurrentUa).append(',')
        sb.append(""""maxVoltageUv":""").append(s.chargerSpec.maxVoltageUv).append(',')
        sb.append(""""maxPowerW":""").append(s.chargerSpec.maxPowerW).append(',')
        sb.append(""""liveInputW":""").append(s.chargerSpec.liveInputW).append(',')
        sb.append(""""source":"""").append(jsonEscape(s.chargerSpec.source)).append('"').append(',')
        sb.append(""""usbPowered":""").append(s.chargerSpec.usbPowered).append(',')
        sb.append(""""acPowered":""").append(s.chargerSpec.acPowered).append(',')
        sb.append(""""wirelessPowered":""").append(s.chargerSpec.wirelessPowered)
        sb.append('}')
        sb.append('}')
        return sb.toString()
    }

    /** Full dump of every BatteryManager.BATTERY_PROPERTY_* getter +
     *  every sticky ACTION_BATTERY_CHANGED extra. Path bypasses the
     *  SELinux block on /sys/class/power_supply because BatteryManager
     *  goes through the system_server service binder, not raw sysfs.
     *  Used to identify what's actually exposed on a hardened Samsung
     *  / Pixel so we can wire BatterySessionStats to the system-
     *  service surface instead of the kernel files. */
    private fun batteryPropertiesJson(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val sticky = ctx.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val sb = StringBuilder("{")

        // BatteryManager (system-service path, immune to sysfs blocking)
        sb.append(""""batteryManager":{""")
        var firstBm = true
        fun appendIntProp(label: String, prop: Int) {
            if (!firstBm) sb.append(','); firstBm = false
            val v = runCatching { bm?.getIntProperty(prop) }.getOrNull()
            sb.append('"').append(label).append("\":").append(v ?: "null")
        }
        fun appendLongProp(label: String, prop: Int) {
            if (!firstBm) sb.append(','); firstBm = false
            val v = runCatching { bm?.getLongProperty(prop) }.getOrNull()
            sb.append('"').append(label).append("\":").append(v ?: "null")
        }
        appendIntProp("current_now_uA",          android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        appendIntProp("current_average_uA",      android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        appendIntProp("capacity_pct",            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        appendIntProp("status",                  android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        appendLongProp("charge_counter_uAh",     android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        appendLongProp("energy_counter_nWh",     android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        if (!firstBm) sb.append(','); firstBm = false
        sb.append(""""isCharging":""").append(bm?.isCharging ?: "null")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            sb.append(',')
            val t = runCatching { bm?.computeChargeTimeRemaining() }.getOrNull()
            sb.append(""""compute_charge_time_remaining_ms":""").append(t ?: "null")
        }
        sb.append('}')

        // Sticky ACTION_BATTERY_CHANGED extras (broadcast surface;
        // some fields like cycle_count + charging_status are API 31+
        // and only land in the extras bundle on devices that support
        // them — we read by string key for forward-compat).
        sb.append(""","stickyExtras":{""")
        if (sticky == null) {
            sb.append(""""_present":false""")
        } else {
            sb.append(""""_present":true""")
            fun appendIntExtra(label: String, key: String, default: Int = Int.MIN_VALUE) {
                sb.append(',').append('"').append(label).append("\":")
                val v = sticky.getIntExtra(key, default)
                sb.append(if (v == default) "null" else v)
            }
            fun appendBoolExtra(label: String, key: String) {
                sb.append(',').append('"').append(label).append("\":")
                sb.append(sticky.getBooleanExtra(key, false))
            }
            fun appendStringExtra(label: String, key: String) {
                sb.append(',').append('"').append(label).append("\":")
                val v = sticky.getStringExtra(key)
                if (v == null) sb.append("null")
                else sb.append('"').append(jsonEscape(v)).append('"')
            }
            appendIntExtra("level",        android.os.BatteryManager.EXTRA_LEVEL)
            appendIntExtra("scale",        android.os.BatteryManager.EXTRA_SCALE)
            appendIntExtra("status",       android.os.BatteryManager.EXTRA_STATUS)
            appendIntExtra("plugged",      android.os.BatteryManager.EXTRA_PLUGGED)
            appendIntExtra("health",       android.os.BatteryManager.EXTRA_HEALTH)
            appendIntExtra("voltage_mV",   android.os.BatteryManager.EXTRA_VOLTAGE)
            appendIntExtra("temperature_dC", android.os.BatteryManager.EXTRA_TEMPERATURE)
            appendStringExtra("technology", android.os.BatteryManager.EXTRA_TECHNOLOGY)
            appendBoolExtra("present",     android.os.BatteryManager.EXTRA_PRESENT)
            // API 31+ extras read by canonical string key (constants
            // not always resolvable at compileSdk < 31). If absent,
            // getIntExtra returns the default (MIN_VALUE) which we
            // surface as null.
            appendIntExtra("cycle_count",     "android.os.extra.CYCLE_COUNT")
            appendIntExtra("charging_status", "android.os.extra.CHARGING_STATUS")
            appendIntExtra("max_charging_current_uA", "android.os.extra.MAX_CHARGING_CURRENT")
            appendIntExtra("max_charging_voltage_uV", "android.os.extra.MAX_CHARGING_VOLTAGE")
        }
        sb.append('}')

        sb.append('}')
        return sb.toString()
    }

    /** Per-path readability snapshot of every kernel sysfs/proc file
     *  the app touches. Sorted: failing paths first so the user sees
     *  what's blocked on their device immediately, then OKs.
     *  The frontline answer to "AccuBattery's trick isn't working
     *  for me" — exposes whether the kernel actually allows the
     *  read or whether SELinux is denying it. */
    private fun sysfsDiagnosticJson(): String {
        val all = com.diegonmarcos.superapp.battery.SysfsProc.sysfsReadDiagnostic()
        val sorted = all.sortedBy { (_, status) -> if (status.startsWith("✗")) 0 else 1 }
        val sb = StringBuilder("""{"paths":[""")
        var first = true
        for ((path, status) in sorted) {
            if (!first) sb.append(','); first = false
            sb.append('{')
            sb.append(""""path":"""").append(jsonEscape(path)).append('"').append(',')
            sb.append(""""status":"""").append(jsonEscape(status)).append('"')
            sb.append('}')
        }
        sb.append("],")
        val total = all.size
        val ok = all.count { (_, status) -> status.startsWith("✓") }
        sb.append(""""summary":{"total":""").append(total).append(',')
        sb.append(""""ok":""").append(ok).append(',')
        sb.append(""""blocked":""").append(total - ok).append('}')
        sb.append('}')
        return sb.toString()
    }

    /** Device network layout — every NIC + its IPs, plus each
     *  ConnectivityManager Network with transports + link addresses +
     *  routes. Answers "which interface owns 10.0.0.9 and what's the real
     *  Wi-Fi IP" so the embedded-adb pairing targets the right address. */
    private fun adbNetInfoJson(ctx: Context): String {
        val sb = StringBuilder("{")
        // ── java.net interfaces ──
        sb.append(""""interfaces":[""")
        runCatching {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            var first = true
            for (nif in java.util.Collections.list(ifaces)) {
                val addrs = nif.inetAddresses
                val list = java.util.Collections.list(addrs)
                if (!first) sb.append(','); first = false
                sb.append('{')
                sb.append(""""name":"""").append(jsonEscape(nif.name)).append('"').append(',')
                sb.append(""""up":""").append(runCatching { nif.isUp }.getOrDefault(false)).append(',')
                sb.append(""""addrs":[""")
                list.forEachIndexed { i, a ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(jsonEscape(a.hostAddress ?: "")).append('"')
                }
                sb.append("]}")
            }
        }
        sb.append("],")
        // ── ConnectivityManager networks ──
        sb.append(""""networks":[""")
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.forEachIndexed { i, n ->
                if (i > 0) sb.append(',')
                val caps = cm.getNetworkCapabilities(n)
                val lp = cm.getLinkProperties(n)
                sb.append('{')
                sb.append(""""wifi":""").append(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: false).append(',')
                sb.append(""""cell":""").append(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ?: false).append(',')
                sb.append(""""vpn":""").append(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false).append(',')
                sb.append(""""iface":"""").append(jsonEscape(lp?.interfaceName ?: "")).append('"').append(',')
                sb.append(""""addrs":[""")
                lp?.linkAddresses?.forEachIndexed { j, la ->
                    if (j > 0) sb.append(',')
                    sb.append('"').append(jsonEscape(la.address.hostAddress ?: "")).append('"')
                }
                sb.append("]}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Shell-channel ladder status (self-contained local-server first,
     *  Shizuku fallback) + the data-driven bundle ids available via
     *  /api/adb/diagnostics. */
    private fun adbStatusJson(ctx: Context): String {
        val channels = com.diegonmarcos.superapp.adbdebug.ShellChannels.all
        val active = com.diegonmarcos.superapp.adbdebug.ShellChannels.active(ctx)
        val bundles = com.diegonmarcos.superapp.adbdebug.AdbDiagnostics.bundleIds()
        val dumpHeld = ctx.checkSelfPermission("android.permission.DUMP") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val sb = StringBuilder("{")
        sb.append(""""active":"""").append(jsonEscape(active?.name() ?: "none")).append('"').append(',')
        sb.append(""""dump_in_process":""").append(dumpHeld).append(',')
        sb.append(""""channels":[""")
        channels.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append(""""name":"""").append(jsonEscape(c.name())).append('"').append(',')
            sb.append(""""ready":""").append(c.isReady(ctx)).append(',')
            sb.append(""""status":"""").append(jsonEscape(c.status(ctx))).append('"')
            sb.append('}')
        }
        sb.append("],")
        sb.append(""""bundles":[""")
        bundles.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEscape(b)).append('"')
        }
        sb.append("]}")
        return sb.toString()
    }
}
