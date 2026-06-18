package com.diegonmarcos.superapp.network
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.battery.BatterySessionStats
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.wireguard.android.backend.Tunnel
import java.net.NetworkInterface

/**
 * Status-strip network popup. Sections, in the order the user
 * actually wants to scan:
 *   1. Cellular  — carrier · type · bars+dBm · mobile RX/TX rate.
 *   2. WiFi      — SSID · channel · RSSI · link speed · WiFi RX/TX rate.
 *   3. Mesh      — every wg.../tun... interface up (NOT just the app's
 *                  GoBackend tunnel). Catches the official WireGuard
 *                  app's tunnel alongside ours.
 *                  (KDoc trap: never write the literal asterisk-
 *                  slash glob inside a block comment — it
 *                  terminates the doc; see also BatterySessionStats
 *                  + SysfsProc for the same engine fix.)
 *   4. Bluetooth — adapter state + connected device names (HEADSET /
 *                  A2DP / GATT) via the hidden BluetoothDevice
 *                  isConnected() probe.
 *   5. Network   — DNS servers from the active network's
 *                  LinkProperties + every IPv4 bound on a live
 *                  interface.
 *
 * Same dark-glass bubble visual shape as BatteryEstimatePopup +
 * SystemInfoPopup. Anchored under the tapped icon (Gravity.START so
 * it extends rightward from the leftmost cluster).
 *
 * Down/up rates are derived from TrafficStats deltas (cumulative
 * since boot, sampled on each open). Cached in [lastSample]; first
 * open of a fresh session shows "—" for rate (one delta needs two
 * samples) and a cumulative byte total instead.
 */
object NetworkInfoPopup {

    private data class Sample(val tsMs: Long, val totalRx: Long, val totalTx: Long, val mobileRx: Long, val mobileTx: Long)
    @Volatile private var lastSample: Sample? = null

    fun show(ctx: Context, anchor: View) {
        val d = ctx.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(0xEE111111.toInt())
                setStroke(maxOf(1, (1 * d).toInt()), 0x44FFFFFF.toInt())
            }
        }

        // Sample now so every rate row reads from the same snapshot.
        val sample = sampleTraffic()
        val prev = lastSample
        lastSample = sample

        // ── 1. Cellular
        container.addView(label(ctx, "Cellular"))
        for (row in readCellular(ctx, sample, prev)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 2. WiFi
        container.addView(label(ctx, "WiFi"))
        for (row in readWifi(ctx, sample, prev)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 3. Mesh (multi-tunnel)
        container.addView(label(ctx, "Mesh"))
        for (row in readMesh(ctx)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 4. Bluetooth
        container.addView(label(ctx, "Bluetooth"))
        for (row in readBluetooth(ctx)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 5. USB (cable / data-transfer)
        container.addView(label(ctx, "USB"))
        for (row in readUsb(ctx)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 6. Network (DNS + private IPs)
        container.addView(label(ctx, "Network"))
        for (row in readNetwork(ctx)) container.addView(valueSmall(ctx, row))

        val pw = PopupWindow(
            container,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8 * d
        }
        // Pin the BOX's LEFT EDGE to the screen's left edge,
        // matching Battery/SysInfo's right-edge alignment on the
        // other side. We stay on showAsDropDown (the same vertical
        // primitive Battery uses) so the popup sits FLUSH with the
        // status strip — showAtLocation introduced a 1-2 dp
        // vertical drift the user noticed. Horizontal pinning is
        // done by computing xOffset = -anchorScreenX so the popup's
        // left edge lands at screen x=0 regardless of which strip
        // icon was tapped.
        val anchorLoc = IntArray(2); anchor.getLocationOnScreen(anchorLoc)
        pw.showAsDropDown(anchor, -anchorLoc[0], (6 * d).toInt(), Gravity.START)
    }

    // ─────────────────────────── Cellular ───────────────────────────

    private fun readCellular(ctx: Context, now: Sample, prev: Sample?): List<String> {
        val rows = mutableListOf<String>()
        val tm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) { rows += "—"; return rows }
        val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "—"
        @Suppress("DEPRECATION")
        val type = networkTypeLabel(runCatching { tm.networkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        rows += "$carrier · $type"
        // Signal — TelephonyManager.signalStrength (API 28+). Pre-28
        // we can only show "—" without the deprecated PhoneStateListener
        // dance, which we deliberately avoid for a one-shot popup.
        if (Build.VERSION.SDK_INT >= 28) {
            val ss = runCatching { tm.signalStrength }.getOrNull()
            if (ss != null) {
                val bars = ss.level.coerceIn(0, 4)
                val barsStr = "▮".repeat(bars) + "▯".repeat(4 - bars)
                val dbm = runCatching {
                    ss.cellSignalStrengths.firstOrNull()?.dbm
                }.getOrNull()
                rows += if (dbm != null) "Signal: $barsStr  $dbm dBm" else "Signal: $barsStr"
            }
        }
        // Mobile rate from TrafficStats delta.
        rows += "Rate: " + fmtRate(now.mobileRx, now.mobileTx, prev?.mobileRx, prev?.mobileTx, now.tsMs, prev?.tsMs)
        return rows
    }

    private fun networkTypeLabel(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR  -> "5G NR"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS  -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE  -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS  -> "GPRS"
        TelephonyManager.NETWORK_TYPE_GSM   -> "GSM"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
        else -> "type $t"
    }

    // ─────────────────────────── WiFi ───────────────────────────

    private fun readWifi(ctx: Context, now: Sample, prev: Sample?): List<String> {
        val rows = mutableListOf<String>()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) { rows += "—"; return rows }
        @Suppress("DEPRECATION")
        val info = runCatching { wm.connectionInfo }.getOrNull()
        if (info == null) { rows += "—"; return rows }
        @Suppress("DEPRECATION")
        val ssid = (info.ssid ?: "<unknown>").trim('"').take(32)
        val rssi = info.rssi
        val speed = info.linkSpeed
        val freq = info.frequency
        val (band, channel) = decodeWifiFreq(freq)
        rows += "SSID: $ssid"
        rows += "Channel: $channel  ($band)"
        rows += "Signal: $rssi dBm · $speed Mbps"
        // WiFi rate ≈ total − mobile (TrafficStats has no WiFi-specific
        // bucket; ethernet usually 0 on phone so this is accurate).
        val wifiRxNow  = now.totalRx  - now.mobileRx
        val wifiTxNow  = now.totalTx  - now.mobileTx
        val wifiRxPrev = prev?.let { it.totalRx - it.mobileRx }
        val wifiTxPrev = prev?.let { it.totalTx - it.mobileTx }
        rows += "Rate: " + fmtRate(wifiRxNow, wifiTxNow, wifiRxPrev, wifiTxPrev, now.tsMs, prev?.tsMs)
        return rows
    }

    /** WiFi frequency → (band-label, channel-number). Standard 802.11
     *  channel arithmetic: 2.4 GHz starts at 2412 / 5, 5 GHz at 5000 / 5,
     *  6 GHz at 5950 / 5. Out-of-band → "—". */
    private fun decodeWifiFreq(freq: Int): Pair<String, String> = when {
        freq in 2412..2484 -> "2.4 GHz" to ((freq - 2407) / 5).toString()
        freq in 5160..5885 -> "5 GHz"   to ((freq - 5000) / 5).toString()
        freq in 5925..7125 -> "6 GHz"   to ((freq - 5950) / 5).toString()
        freq <= 0          -> "—" to "—"
        else               -> "${freq} MHz" to "—"
    }

    // ─────────────────────────── Mesh ───────────────────────────

    /** Every wg.../tun.../utun... interface that's UP on the kernel side.
     *  Catches BOTH the SuperApp's own GoBackend tunnel AND any
     *  tunnel brought up by another app (official WireGuard /
     *  Tailscale / system-VPN-of-the-day). For the SuperApp's
     *  tracked tunnel we also append RX/TX bytes from the backend
     *  statistics — the kernel doesn't expose per-interface bytes
     *  through java.net.NetworkInterface and /sys/class/net/... is
     *  SELinux-blocked on hardened Samsung. */
    private fun readMesh(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val tunnels = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && (it.name.startsWith("wg") || it.name.startsWith("tun") || it.name.startsWith("utun")) }
                .toList()
        }.getOrDefault(emptyList())

        // SuperApp's tracked tunnel — try the GoBackend stats path
        // for accurate RX/TX. Surfaced even when NetworkInterface
        // doesn't list it (the TUN may be in a namespace we can't
        // enumerate).
        val appTunnelRow = runCatching {
            val backend = WgState.backend(ctx)
            val state = backend.getState(WgState.tunnel)
            if (state == Tunnel.State.UP) {
                val stats = runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull()
                val rx = stats?.totalRx() ?: 0L
                val tx = stats?.totalTx() ?: 0L
                "${WgState.tunnel.name}: UP · ↓ ${fmtBytes(rx)} · ↑ ${fmtBytes(tx)}  (app)"
            } else null
        }.getOrNull()
        if (appTunnelRow != null) rows += appTunnelRow

        for (nif in tunnels) {
            // Skip if this interface IS the app tunnel we already
            // surfaced — matched by name (best heuristic available).
            if (appTunnelRow != null && nif.name == WgState.tunnel.name) continue
            val ipv4 = runCatching {
                nif.inetAddresses.asSequence().filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
                    .firstOrNull()?.hostAddress
            }.getOrNull()
            rows += if (ipv4 != null) "${nif.name}: UP · $ipv4  (system)" else "${nif.name}: UP  (system)"
        }

        if (rows.isEmpty()) rows += "No tunnels up"
        return rows
    }

    // ─────────────────────────── Bluetooth ───────────────────────────

    /** Adapter state + the names of every currently-connected bonded
     *  device. Connection state is checked via the hidden
     *  BluetoothDevice.isConnected() reflection — public API only
     *  exposes BluetoothManager.getConnectedDevices(profile) which
     *  needs the profile listener already set up (async). For a
     *  popup we want it synchronous; reflection is the standard
     *  workaround used by every system-tray network info widget. */
    private fun readBluetooth(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val mgr = ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null) { rows += "Unsupported"; return rows }
        if (!adapter.isEnabled) { rows += "OFF"; return rows }
        val bonded = runCatching {
            // BLUETOOTH_CONNECT (API 31+) — manifest already declares it.
            adapter.bondedDevices ?: emptySet()
        }.getOrDefault(emptySet())
        val connected = bonded.mapNotNull { dev ->
            val name = dev.name ?: dev.address
            val isOnline = runCatching {
                val m = dev.javaClass.getMethod("isConnected")
                m.invoke(dev) as? Boolean ?: false
            }.getOrDefault(false)
            if (isOnline) name else null
        }
        rows += "ON · ${bonded.size} bonded · ${connected.size} connected"
        if (connected.isEmpty()) {
            rows += "No active links"
        } else {
            for (name in connected) rows += "  • $name"
        }
        return rows
    }

    // ─────────────────────────── USB ───────────────────────────

    /** USB cable + data-transfer state from the ACTION_USB_STATE sticky
     *  broadcast (registerReceiver(null, …) returns the current intent).
     *  Distinguishes charge-only from a data mode (MTP/PTP/RNDIS/NCM/MIDI/
     *  mass-storage/accessory) and OTG host. No permission needed. */
    private fun readUsb(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val intent = runCatching {
            ctx.applicationContext.registerReceiver(
                null, android.content.IntentFilter("android.hardware.usb.action.USB_STATE"))
        }.getOrNull()
        if (intent == null) { rows += "—"; return rows }
        val connected  = intent.getBooleanExtra("connected", false)
        val configured = intent.getBooleanExtra("configured", false)
        val host       = intent.getBooleanExtra("host_connected", false)
        if (!connected && !host) { rows += "Disconnected (no cable / charge-only AC)"; return rows }
        val fns = listOf(
            "mtp" to "MTP (file transfer)", "ptp" to "PTP (photo)",
            "rndis" to "RNDIS (tether)", "ncm" to "NCM (tether)", "midi" to "MIDI",
            "mass_storage" to "Mass storage", "accessory" to "Accessory", "audio_source" to "Audio source",
        ).filter { intent.getBooleanExtra(it.first, false) }.map { it.second }
        when {
            host -> rows += "OTG host connected"
            fns.isNotEmpty() -> rows += "Connected · DATA transfer"
            connected -> rows += "Connected · charge-only (no data)"
        }
        if (fns.isNotEmpty()) rows += "Mode: " + fns.joinToString(", ")
        rows += "Configured: ${if (configured) "yes" else "no"}"
        return rows
    }

    // ─────────────────────────── Network (DNS + IPs) ───────────────────────────

    private fun readNetwork(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm?.getNetworkCapabilities(it) }
        val transportSummary = if (caps != null) {
            val parts = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     parts += "WiFi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "Cellular"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))      parts += "VPN"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "Ethernet"
            if (parts.isEmpty()) "Offline" else parts.joinToString(" · ")
        } else "Offline"
        rows += "Active: $transportSummary"
        // DNS servers — public API since API 23 via LinkProperties.dnsServers.
        if (active != null && cm != null) {
            val lp = runCatching { cm.getLinkProperties(active) }.getOrNull()
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
            if (dns.isEmpty()) rows += "DNS: —"
            else dns.forEach { rows += "DNS: $it" }
        }
        // Local IPv4 IPs, per interface.
        val ips = readLocalIps()
        if (ips.isEmpty()) rows += "IP: —"
        else ips.forEach { rows += "IP: $it" }
        return rows
    }

    private fun readLocalIps(): List<String> = try {
        val out = mutableListOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (nif.isLoopback || !nif.isUp) continue
            val name = nif.name
            for (addr in nif.inetAddresses) {
                val ip = addr.hostAddress ?: continue
                if (ip.contains(":")) continue
                out += "$name  $ip"
            }
        }
        out
    } catch (_: Throwable) { emptyList() }

    // ─────────────────────────── traffic sampling ───────────────────────────

    private fun sampleTraffic(): Sample = Sample(
        tsMs    = SystemClock.elapsedRealtime(),
        totalRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L),
        totalTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L),
        mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0L),
        mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0L),
    )

    /** Format an interface's rate row. Needs ≥1s + ≥1 byte delta on
     *  both sides to compute a meaningful rate; otherwise falls back
     *  to the cumulative byte totals from this sample. */
    private fun fmtRate(rxNow: Long, txNow: Long, rxPrev: Long?, txPrev: Long?, tsNow: Long, tsPrev: Long?): String {
        if (rxPrev != null && txPrev != null && tsPrev != null && tsNow - tsPrev >= 1000L) {
            val dtSec = (tsNow - tsPrev) / 1000.0
            val rxBps = ((rxNow - rxPrev).coerceAtLeast(0L) / dtSec).toLong()
            val txBps = ((txNow - txPrev).coerceAtLeast(0L) / dtSec).toLong()
            return "↓ ${fmtBytes(rxBps)}/s · ↑ ${fmtBytes(txBps)}/s"
        }
        // No prior sample → show cumulative totals so the row carries
        // some information instead of "—".
        return "↓ ${fmtBytes(rxNow)} · ↑ ${fmtBytes(txNow)}  (cumulative)"
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun fmtBytes(b: Long): String = when {
        b > 1_000_000_000 -> "%.2f GB".format(b / 1e9)
        b > 1_000_000     -> "%.2f MB".format(b / 1e6)
        b > 1_000         -> "%.1f kB".format(b / 1e3)
        else              -> "$b B"
    }

    private fun label(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        textSize = 11f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun valueSmall(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}
