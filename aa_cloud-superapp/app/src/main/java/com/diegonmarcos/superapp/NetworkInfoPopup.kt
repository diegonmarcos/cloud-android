package com.diegonmarcos.superapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.wireguard.android.backend.Tunnel
import java.net.NetworkInterface

/**
 * Network status popup — shown when the user taps any icon in the
 * left status-strip cluster (5G / WiFi / WG). Surfaces:
 *
 *   • WiFi    — SSID, RSSI dBm, link speed, frequency band.
 *   • Cellular — carrier, network type label.
 *   • WireGuard — tunnel UP / DOWN + RX/TX bytes if running.
 *   • Local IPs — every non-loopback IPv4 with its interface name
 *                 (wlan0 / rmnet_data0 / wg0 / tun0 …).
 *
 * Mirrors BatteryEstimatePopup's visual shape (same dark-glass bubble,
 * monospace text, top-anchored under the tapped icon).
 */
object NetworkInfoPopup {

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

        container.addView(label(ctx, "Network"))
        container.addView(valueBig(ctx, summary(ctx)))
        container.addView(spacer(ctx, (10 * d).toInt()))

        // WiFi
        val wifi = readWifi(ctx)
        container.addView(label(ctx, "WiFi"))
        container.addView(valueSmall(ctx, wifi))
        container.addView(spacer(ctx, (4 * d).toInt()))

        // Cellular
        container.addView(label(ctx, "Cellular"))
        container.addView(valueSmall(ctx, readCellular(ctx)))
        container.addView(spacer(ctx, (4 * d).toInt()))

        // WireGuard
        container.addView(label(ctx, "WireGuard"))
        container.addView(valueSmall(ctx, readWg(ctx)))
        container.addView(spacer(ctx, (8 * d).toInt()))

        // Local IPs
        container.addView(label(ctx, "Local IPs"))
        val ips = readLocalIps()
        if (ips.isEmpty()) container.addView(valueSmall(ctx, "—"))
        else ips.forEach { container.addView(valueSmall(ctx, it)) }

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
        pw.showAsDropDown(anchor, 0, (6 * d).toInt(), Gravity.START)
    }

    private fun summary(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "Offline"
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     parts += "WiFi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "Cellular"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))      parts += "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "Ethernet"
        return if (parts.isEmpty()) "Offline" else parts.joinToString(" · ")
    }

    private fun readWifi(ctx: Context): String = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "—"
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return "—"
        @Suppress("DEPRECATION")
        val ssid = (info.ssid ?: "<unknown>").trim('"').take(32)
        val rssi = info.rssi
        val speed = info.linkSpeed
        val freq = info.frequency
        val band = when {
            freq in 2400..2500 -> "2.4 GHz"
            freq in 4900..5900 -> "5 GHz"
            freq in 5925..7125 -> "6 GHz"
            freq <= 0 -> "—"
            else -> "${freq} MHz"
        }
        "$ssid · $rssi dBm · $speed Mbps · $band"
    } catch (_: Throwable) { "—" }

    private fun readCellular(ctx: Context): String = try {
        val tm = ctx.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "—"
        val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "—"
        @Suppress("DEPRECATION")
        val type = networkTypeLabel(tm.networkType)
        "$carrier · $type"
    } catch (_: Throwable) { "—" }

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

    private fun readWg(ctx: Context): String = try {
        val backend = WgState.backend(ctx)
        val state = runCatching { backend.getState(WgState.tunnel) }.getOrNull()
        when (state) {
            Tunnel.State.UP -> {
                val stats = runCatching { backend.getStatistics(WgState.tunnel) }.getOrNull()
                val rx = stats?.totalRx() ?: 0L
                val tx = stats?.totalTx() ?: 0L
                "UP · ↓ ${fmtBytes(rx)} · ↑ ${fmtBytes(tx)}"
            }
            Tunnel.State.DOWN -> "DOWN"
            Tunnel.State.TOGGLE -> "Toggling…"
            null -> "—"
        }
    } catch (_: Throwable) { "—" }

    private fun readLocalIps(): List<String> = try {
        val out = mutableListOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (nif.isLoopback || !nif.isUp) continue
            val name = nif.name
            for (addr in nif.inetAddresses) {
                val ip = addr.hostAddress ?: continue
                // IPv4 only — IPv6 link-local clutters the popup. The user
                // can drill into Configs/About for the full surface.
                if (ip.contains(":")) continue
                out += "$name  $ip"
            }
        }
        out
    } catch (_: Throwable) { emptyList() }

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
    private fun valueBig(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFFL.toInt())
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
