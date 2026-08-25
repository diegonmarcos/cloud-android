package com.diegonmarcos.superapp.network
import com.diegonmarcos.superapp.ui.SystemInfoPopup
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.battery.BatterySessionStats
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
            // At least half the screen wide so the (wider) mesh rows + data read
            // comfortably; still WRAP_CONTENT beyond that.
            minimumWidth = (ctx.resources.displayMetrics.widthPixels * 0.5f).toInt()
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

        // Per-radio on/off state for the light indicators. Tapping a light
        // toggles the WG mesh in-app (the app owns that tunnel); for Wi-Fi /
        // cellular / Bluetooth the platform forbids a 3rd-party app flipping
        // the radio, so the tap deep-links to the system toggle instead.
        var popup: PopupWindow? = null
        val dismiss = { popup?.dismiss() }

        // ── 1. Cellular
        container.addView(lightRow(ctx, "Cellular", hasTransport(ctx, NetworkCapabilities.TRANSPORT_CELLULAR)) {
            dismiss(); openSettings(ctx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                else Settings.ACTION_WIRELESS_SETTINGS)
        })
        for (row in readCellular(ctx, sample, prev)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 2. WiFi
        container.addView(lightRow(ctx, "WiFi", wifiEnabled(ctx)) {
            dismiss(); openSettings(ctx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
                else Settings.ACTION_WIFI_SETTINGS)
        })
        for (row in readWifi(ctx, sample, prev)) container.addView(valueSmall(ctx, row))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 3. Mesh — header carries one status light PER mesh (wg0 + wg-public
        //      ride the single Android tunnel). Each light pings that mesh's hub
        //      (.1 of its allowed-IP range); tap toggles the shared tunnel. Below,
        //      a labelled block per mesh: endpoint, last talk, key, IP range.
        val meshes = meshList(ctx)
        container.addView(meshHeaderRow(ctx, meshes) { dismiss(); toggleMesh(ctx) })
        if (meshes.isEmpty()) {
            for (row in readMesh(ctx)) container.addView(valueSmall(ctx, row))
        } else {
            val stats = runCatching {
                val b = WgState.backend(ctx)
                if (b.getState(WgState.tunnel) == Tunnel.State.UP) b.getStatistics(WgState.tunnel) else null
            }.getOrNull()
            for (m in meshes) {
                container.addView(label(ctx, m.tag))
                for (row in meshDetail(m, stats)) container.addView(valueSmall(ctx, row))
            }
        }
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 3b. KDE Connect — status + connected device count; tap → KDE configs.
        val kdeConn = runCatching {
            com.diegonmarcos.superapp.kdeconnect.KdeConnectManager.connectedIds().size
        }.getOrDefault(0)
        val kdeTotal = runCatching {
            com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig.get().devices.size
        }.getOrDefault(0)
        container.addView(lightRow(ctx, "KDE Connect", kdeConn > 0) {
            dismiss(); (ctx as? com.diegonmarcos.superapp.MainActivity)?.openSection("kde")
        })
        container.addView(valueSmall(ctx, "$kdeConn / $kdeTotal device(s) connected"))
        container.addView(spacer(ctx, (6 * d).toInt()))

        // ── 4. Bluetooth
        container.addView(lightRow(ctx, "Bluetooth", bluetoothEnabled(ctx)) {
            dismiss(); openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)
        })
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
        popup = pw  // so a light-indicator tap can dismiss before deep-linking
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

    // ──────────────── Light indicators + radio toggles ────────────────

    /** Section header with a trailing on/off light. The light's hit area is
     *  tappable → [onTap]. Green = on, dim = off. */
    private fun lightRow(ctx: Context, title: String, on: Boolean, onTap: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        // WRAP_CONTENT (no weight spacer) so the row sizes to title + light and
        // never widens the popup — the light sits one space after the title.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(label(ctx, title))
        val dot = View(ctx).apply {
            val sz = (10 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) 0xFF35E07F.toInt() else 0x55FFFFFF)
            }
        }
        val hit = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            // left padding = the "one space" gap after the title; small touch pad.
            val p = (5 * d).toInt(); setPadding((8 * d).toInt(), p, p, p)
            isClickable = true
            setOnClickListener { onTap() }
            addView(dot)
        }
        row.addView(hit)
        return row
    }

    private fun hasTransport(ctx: Context, t: Int): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(t) == true }
    }.getOrDefault(false)

    private fun wifiEnabled(ctx: Context): Boolean = runCatching {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    }.getOrDefault(false)

    private fun bluetoothEnabled(ctx: Context): Boolean = runCatching {
        (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true
    }.getOrDefault(false)

    private fun meshUp(ctx: Context): Boolean = runCatching {
        WgState.backend(ctx).getState(WgState.tunnel) == Tunnel.State.UP
    }.getOrDefault(false)

    /** Hub IP of a peer = the `.1` of its first allowed-IP subnet
     *  ("10.1.0.0/24" → "10.1.0.1"). Null if unparseable. */
    private fun hubIp(allowedIps: String): String? {
        val ip = allowedIps.split(",").firstOrNull()?.trim()?.substringBefore('/') ?: return null
        val o = ip.split('.')
        return if (o.size == 4) "${o[0]}.${o[1]}.${o[2]}.1" else null
    }

    /** A mesh = one WG peer. [tag] is the friendly id (wg0 / wg-public). */
    private data class Mesh(
        val tag: String, val name: String, val endpoint: String,
        val allowedIps: String, val pubkey: String)

    /** "Mesh" header carrying one ping dot PER mesh. Each dot starts amber
     *  (probing) / green (fresh handshake), then resolves to green (hub reachable
     *  OR fresh handshake) or dim. Whole row taps → [onTap] (toggle tunnel). */
    private fun meshHeaderRow(ctx: Context, meshes: List<Mesh>, onTap: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val green = 0xFF35E07F.toInt(); val amber = 0xFFE0A235.toInt(); val dim = 0x55FFFFFF
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; setOnClickListener { onTap() }
        }
        row.addView(label(ctx, "Mesh"))
        for (m in meshes) {
            val fresh = peerHandshakeFresh(ctx, m.pubkey)
            val dotBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (fresh) green else amber) }
            val sz = (10 * d).toInt()
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = (8 * d).toInt() }
                background = dotBg
            }
            row.addView(dot)
            val hub = hubIp(m.allowedIps)
            if (hub != null) Thread {
                val ok = pingHub(hub)
                dot.post { dotBg.setColor(if (ok || fresh) green else dim) }
            }.start() else if (!fresh) dotBg.setColor(dim)
        }
        return row
    }

    /** Per-mesh detail: endpoint, last talk (handshake age), traffic, key, range. */
    private fun meshDetail(m: Mesh, stats: com.wireguard.android.backend.Statistics?): List<String> {
        val rows = mutableListOf<String>()
        if (m.endpoint.isNotBlank()) rows += "Endpoint: ${m.endpoint}"
        val ps = runCatching { stats?.peer(com.wireguard.crypto.Key.fromBase64(m.pubkey)) }.getOrNull()
        val hs = ps?.latestHandshakeEpochMillis() ?: 0L
        rows += "Last Talk: " + if (hs > 0L) "${(System.currentTimeMillis() - hs) / 1000}s ago" else "—"
        if (ps != null) rows += "Traffic: ↓ ${fmtBytes(ps.rxBytes())} · ↑ ${fmtBytes(ps.txBytes())}"
        if (m.pubkey.isNotBlank())   rows += "Public Key: ${m.pubkey}"
        if (m.allowedIps.isNotBlank()) rows += "IP Range: ${m.allowedIps}"
        return rows
    }

    /** Reachability probe for a mesh hub. ICMP (isReachable) is often blocked
     *  over WG, so try a quick TCP connect to a few hub ports first (53 = the
     *  mesh DNS hub, 443/80/22 = common services), then fall back to ICMP. */
    private fun pingHub(hubIp: String): Boolean {
        for (port in intArrayOf(53, 443, 80, 22)) {
            val ok = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(hubIp, port), 600); true }
            }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching { java.net.InetAddress.getByName(hubIp).isReachable(600) }.getOrDefault(false)
    }

    /** Meshes to display: configured peers (WireGuardPrefs) unioned with the
     *  baked-default meshes (build.json), deduped by subnet — so wg0 + wg-public
     *  always appear even before the second peer is configured. tag/endpoint fall
     *  back to the baked entry when the saved peer lacks them. */
    private fun meshList(ctx: Context): List<Mesh> {
        val baked = parseBakedMeshes()
        val tagBySubnet = baked.associate { subnetKey(it.allowedIps) to it.tag }
        val out = LinkedHashMap<String, Mesh>()
        runCatching { WgState.prefs(ctx).peers() }.getOrDefault(emptyList()).forEach { p ->
            val k = subnetKey(p.allowedIps)
            out[k] = Mesh(tagBySubnet[k] ?: deriveTag(p.allowedIps), p.name, p.endpoint, p.allowedIps, p.publicKey)
        }
        baked.forEach { out.putIfAbsent(subnetKey(it.allowedIps), it) }
        return out.values.toList()
    }

    private fun subnetKey(allowedIps: String) = allowedIps.split(",").firstOrNull()?.trim().orEmpty()
    private fun deriveTag(allowedIps: String): String {
        val net = allowedIps.split(",").firstOrNull()?.trim()?.substringBefore('/').orEmpty()
        return when {
            net.startsWith("10.0.") -> "wg0"
            net.startsWith("10.1.") -> "wg-public"
            else -> "mesh"
        }
    }

    /** The canonical meshes baked from build.json::ui.wireguard_default.peers. */
    private fun parseBakedMeshes(): List<Mesh> = runCatching {
        val json = String(android.util.Base64.decode(
            com.diegonmarcos.superapp.BuildConfig.UI_WG_PEERS_JSON_B64, android.util.Base64.DEFAULT))
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val allowed = o.optString("allowed_ips")
            Mesh(o.optString("mesh").ifBlank { deriveTag(allowed) }, o.optString("name"),
                o.optString("endpoint"), allowed, o.optString("public_key"))
        }
    }.getOrDefault(emptyList())

    /** A mesh peer is "up" when the tunnel is up AND that peer has a recent
     *  WireGuard handshake (< ~3 min). Per-peer signal from GoBackend stats,
     *  matched by the peer's public key. */
    private fun peerHandshakeFresh(ctx: Context, publicKey: String): Boolean = runCatching {
        val backend = WgState.backend(ctx)
        if (backend.getState(WgState.tunnel) != Tunnel.State.UP) false
        else {
            val stats = backend.getStatistics(WgState.tunnel)
            val ps = stats.peer(com.wireguard.crypto.Key.fromBase64(publicKey))
            val hs = ps?.latestHandshakeEpochMillis() ?: 0L
            hs > 0L && (System.currentTimeMillis() - hs) < 190_000L
        }
    }.getOrDefault(false)

    /** The app owns the WG tunnel, so it can flip it directly. Bringing it UP
     *  needs prior VPN consent (granted via the Mesh page on first connect);
     *  if that's missing setState throws → we point the user there. */
    private fun toggleMesh(ctx: Context) {
        runCatching {
            val b = WgState.backend(ctx)
            if (b.getState(WgState.tunnel) == Tunnel.State.UP) {
                WgState.requestTunnelDown(ctx)
                Toast.makeText(ctx, "Mesh: disconnecting", Toast.LENGTH_SHORT).show()
            } else {
                b.setState(WgState.tunnel, Tunnel.State.UP, WgState.prefs(ctx).toWgConfig())
                Toast.makeText(ctx, "Mesh: connecting", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(ctx, "Mesh: open the Mesh page to grant VPN access", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings(ctx: Context, action: String) {
        runCatching {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(ctx, "No system screen for that toggle", Toast.LENGTH_SHORT).show()
        }
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
        val prefs = runCatching { WgState.prefs(ctx) }.getOrNull()
        val backend = runCatching { WgState.backend(ctx) }.getOrNull()
        val up = runCatching { backend?.getState(WgState.tunnel) == Tunnel.State.UP }.getOrDefault(false)
        val stats = if (up) runCatching { backend?.getStatistics(WgState.tunnel) }.getOrNull() else null

        // ── Interface ────────────────────────────────────────────────────────
        rows += "Tunnel: ${prefs?.tunnelName ?: "wg-mesh"} · ${if (up) "UP" else "DOWN"}"
        prefs?.let { p ->
            if (p.interfaceAddress.isNotBlank()) rows += "Addr: ${p.interfaceAddress}"
            val meta = buildList {
                if (p.interfaceMtu.isNotBlank()) add("MTU ${p.interfaceMtu}")
                if (p.interfaceListenPort.isNotBlank()) add("port ${p.interfaceListenPort}")
            }
            if (meta.isNotEmpty()) rows += meta.joinToString(" · ")
            if (p.interfaceDns.isNotBlank()) rows += "DNS: ${p.interfaceDns}"
            val pub = runCatching { p.derivedInterfacePublicKey() }.getOrDefault("")
            if (pub.isNotBlank()) rows += "Pubkey: ${pub.take(16)}…"
        }
        if (stats != null) rows += "Total: ↓ ${fmtBytes(stats.totalRx())} · ↑ ${fmtBytes(stats.totalTx())}"

        // ── Per-peer (each peer = one mesh) ──────────────────────────────────
        val peers = runCatching { prefs?.peers() }.getOrNull() ?: emptyList()
        for (p in peers) {
            rows += "▸ ${p.name}"
            if (p.endpoint.isNotBlank())   rows += "   endpoint ${p.endpoint}"
            if (p.allowedIps.isNotBlank())  rows += "   allowed ${p.allowedIps}"
            if (p.persistentKeepalive.isNotBlank()) rows += "   keepalive ${p.persistentKeepalive}s"
            if (p.publicKey.isNotBlank())   rows += "   key ${p.publicKey.take(16)}…"
            val ps = runCatching {
                stats?.peer(com.wireguard.crypto.Key.fromBase64(p.publicKey))
            }.getOrNull()
            if (ps != null) {
                val hs = ps.latestHandshakeEpochMillis()
                val ago = if (hs > 0L) "${(System.currentTimeMillis() - hs) / 1000}s ago" else "never"
                rows += "   handshake $ago · ↓ ${fmtBytes(ps.rxBytes())} · ↑ ${fmtBytes(ps.txBytes())}"
            } else if (up) rows += "   handshake — (no traffic yet)"
        }

        // System WG/tun interfaces not tracked by our backend (e.g. the official
        // WireGuard app's tunnel), with their bound IPs.
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && (it.name.startsWith("wg") || it.name.startsWith("tun") || it.name.startsWith("utun")) }
                .forEach { nif ->
                    val addrs = nif.inetAddresses.asSequence().filter { !it.isLoopbackAddress }
                        .mapNotNull { it.hostAddress }.joinToString(", ")
                    rows += "iface ${nif.name}: ${if (addrs.isBlank()) "up" else addrs}"
                }
        }

        if (rows.isEmpty()) rows += "No tunnel configured"
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

    /** Persistent USB section: combines the USB data session (ACTION_USB_STATE)
     *  with the charge state (ACTION_BATTERY_CHANGED EXTRA_PLUGGED) so it's
     *  always informative — Data transfer vs Charge-only vs OTG host vs
     *  Disconnected — plus the active mode and (for tethering) the live USB
     *  network interface. Battery WATTAGE is intentionally left to the battery
     *  popup. No permission needed (both are sticky broadcasts). */
    private fun readUsb(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val app = ctx.applicationContext
        val usb = runCatching {
            app.registerReceiver(null, android.content.IntentFilter("android.hardware.usb.action.USB_STATE"))
        }.getOrNull()
        val batt = runCatching {
            app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val connected  = usb?.getBooleanExtra("connected", false) ?: false
        val configured = usb?.getBooleanExtra("configured", false) ?: false
        val host       = usb?.getBooleanExtra("host_connected", false) ?: false
        val plugged    = batt?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugLabel  = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> null
        }
        val fns = listOf(
            "mtp" to "MTP (file transfer)", "ptp" to "PTP (photo)",
            "rndis" to "RNDIS (tether)", "ncm" to "NCM (tether)", "midi" to "MIDI",
            "mass_storage" to "Mass storage", "accessory" to "Accessory", "audio_source" to "Audio source",
        ).filter { usb?.getBooleanExtra(it.first, false) == true }.map { it.second }

        // Status line — the headline the user scans.
        rows += when {
            host -> "OTG host connected"
            connected && fns.isNotEmpty() -> "● Data transfer"
            connected -> "● Connected (charge-only, no data)"
            plugLabel == "Wireless" -> "Wireless charging (no cable)"
            plugLabel != null -> "● Charging (charge-only)"
            else -> "○ Disconnected"
        }
        if (plugLabel != null) rows += "Power: $plugLabel"
        if (fns.isNotEmpty()) {
            rows += "Mode: " + fns.joinToString(", ")
            rows += "Configured: ${if (configured) "yes" else "no"}"
            // Data stats — for tether modes the kernel exposes a usb/rndis/ncm
            // NIC; surface its IP (per-iface byte counters are SELinux-blocked
            // on hardened Samsung, same wall the Mesh section hits).
            for (s in usbNetStats()) rows += s
        }
        return rows
    }

    /** Live USB-tether interface(s) named rndis / usb / ncm + their IPv4. */
    private fun usbNetStats(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && (it.name.startsWith("rndis") || it.name.startsWith("usb") || it.name.startsWith("ncm")) }
            .map { nif ->
                val ip = nif.inetAddresses.asSequence()
                    .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }?.hostAddress
                if (ip != null) "Tether ${nif.name}: $ip" else "Tether ${nif.name}: up"
            }.toList()
    }.getOrDefault(emptyList())

    // ─────────────────────────── Network (DNS + IPs) ───────────────────────────

    private fun readNetwork(ctx: Context): List<String> {
        val rows = mutableListOf<String>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val lp = active?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

        // ── transports ───────────────────────────────────────────────────────
        val transports = mutableListOf<String>()
        caps?.let {
            if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     transports += "WiFi"
            if (it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Cellular"
            if (it.hasTransport(NetworkCapabilities.TRANSPORT_VPN))      transports += "VPN"
            if (it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
        }
        rows += "Active: ${if (transports.isEmpty()) "Offline" else transports.joinToString(" · ")}"

        // ── capability flags + bandwidth ─────────────────────────────────────
        caps?.let { c ->
            val flags = mutableListOf<String>()
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))  flags += "internet"
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) flags += "validated"
            flags += if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "unmetered" else "metered"
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) flags += "captive-portal"
            if (flags.isNotEmpty()) rows += "State: ${flags.joinToString(" · ")}"
            if (c.linkDownstreamBandwidthKbps > 0 || c.linkUpstreamBandwidthKbps > 0)
                rows += "Bandwidth: ↓ ${fmtKbps(c.linkDownstreamBandwidthKbps)} · ↑ ${fmtKbps(c.linkUpstreamBandwidthKbps)}"
        }

        // ── WiFi specifics (RSSI / link speed / frequency; SSID if permitted) ─
        val ti = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps?.transportInfo else null
        if (ti is android.net.wifi.WifiInfo) {
            val ssid = ti.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            if (ssid != null) rows += "SSID: $ssid"
            rows += "WiFi: ${ti.rssi} dBm · ${ti.linkSpeed} Mbps" +
                (if (ti.frequency > 0) " · ${ti.frequency} MHz" else "")
        }

        // ── cellular type ────────────────────────────────────────────────────
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            val tm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.let {
                @Suppress("DEPRECATION")
                val t = runCatching { networkTypeLabel(it.networkType) }.getOrDefault("—")
                rows += "Cell: ${it.networkOperatorName ?: "—"} · $t"
            }
        }

        // ── link properties (iface / DNS / domains / proxy / gateway) ────────
        lp?.let { l ->
            l.interfaceName?.let { rows += "Iface: $it" }
            l.dnsServers.mapNotNull { it.hostAddress }.forEach { rows += "DNS: $it" }
            if (!l.domains.isNullOrBlank()) rows += "Domains: ${l.domains}"
            runCatching { l.httpProxy?.let { rows += "Proxy: ${it.host}:${it.port}" } }
            l.routes.filter { it.isDefaultRoute && it.gateway != null }
                .mapNotNull { it.gateway?.hostAddress }.distinct()
                .forEach { rows += "Gateway: $it" }
        }

        // ── every bound IP (v4 + v6), per interface, scope-tagged ────────────
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .forEach { nif ->
                    nif.inetAddresses.asSequence()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .forEach { a ->
                            val scope = if (a.isSiteLocalAddress) "private" else "public"
                            a.hostAddress?.let { rows += "IP: ${nif.name} $it ($scope)" }
                        }
                }
        }
        // ── open ports (own-UID TCP sockets from /proc/net) ──────────────────
        readPorts().forEach { rows += it }
        return rows
    }

    /** Listening ports + established connections from /proc/net/tcp{,6}. Modern
     *  Android only exposes our OWN-UID sockets here. There is no per-socket
     *  "last activity" timestamp in /proc, so established connections (active
     *  talks) are listed with their remote endpoint as the closest signal. */
    private fun readPorts(): List<String> {
        val listen = sortedSetOf<Int>()
        val estab = mutableListOf<String>()
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                java.io.File(path).readLines().drop(1).forEach { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size < 4) return@forEach
                    val lport = f[1].substringAfter(':').toIntOrNull(16) ?: return@forEach
                    when (f[3]) {
                        "0A" -> listen += lport                                   // LISTEN
                        "01" -> {                                                 // ESTABLISHED
                            val rport = f[2].substringAfter(':').toIntOrNull(16)
                            if (rport != null && rport != 0)
                                estab += "$lport → ${hexToIp(f[2].substringBefore(':'))}:$rport"
                        }
                    }
                }
            }
        }
        val out = mutableListOf<String>()
        if (listen.isNotEmpty()) out += "Listening: ${listen.joinToString(", ")}"
        estab.distinct().take(12).forEach { out += "Conn: $it" }
        return out
    }

    private fun hexToIp(a: String): String = runCatching {
        if (a.length == 8) (3 downTo 0).joinToString(".") { a.substring(it * 2, it * 2 + 2).toInt(16).toString() }
        else "[v6]"
    }.getOrDefault("?")

    private fun fmtKbps(kbps: Int): String =
        if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000.0) else "$kbps Kbps"

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
