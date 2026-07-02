package com.diegonmarcos.superapp.firewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/**
 * Read-only firewall / network status for the About-screen rows. Every
 * value comes from ConnectivityManager / Settings — NO root, NO
 * privileged permission.
 */
object FirewallInfo {

    data class State(
        val enabled: Boolean,
        val blockedCount: Int,
        val transport: String,     // "Wi-Fi" / "Cellular" / "Ethernet" / "None"
        val systemVpnActive: Boolean,
        val privateDns: String,    // "Off" / "Automatic" / hostname / "Strict"
    )

    fun read(ctx: Context): State {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "None"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // private_dns_mode: "off" | "opportunistic" | "hostname"
        val mode = runCatching {
            Settings.Global.getString(ctx.contentResolver, "private_dns_mode")
        }.getOrNull()
        val privateDns = when (mode) {
            null, "off" -> "Off"
            "opportunistic" -> "Automatic"
            "hostname" -> runCatching {
                Settings.Global.getString(ctx.contentResolver, "private_dns_specifier")
            }.getOrNull() ?: "Strict"
            else -> mode
        }

        return State(
            enabled = FirewallPrefs.isEnabled(ctx),
            blockedCount = FirewallRules.policies(ctx).size,
            transport = transport,
            systemVpnActive = vpn,
            privateDns = privateDns,
        )
    }

    fun fmtState(s: State): String = if (s.enabled) "On" else "Off"
    fun fmtBlocked(s: State): String = "${s.blockedCount} app(s)"
    fun fmtTransport(s: State): String = s.transport
    fun fmtVpn(s: State): String = if (s.systemVpnActive) "Active" else "None"
    fun fmtPrivateDns(s: State): String = s.privateDns
}
