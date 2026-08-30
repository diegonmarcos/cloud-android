package com.diegonmarcos.superapp.kdeconnect

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * kdeconnect.connectivity_report — report the phone's mobile network type +
 * signal level (0–4) to the desktop. Gated on READ_PHONE_STATE; without it we
 * report an UNKNOWN/0 entry so the desktop still shows the device.
 */
object ConnectivityReportPlugin : KdePlugin {
    override val id = "connectivity_report"
    override val incoming = setOf("kdeconnect.connectivity_report.request")
    override val outgoing = setOf("kdeconnect.connectivity_report")

    override fun onLinkReady(ctx: Context, link: KdeLink) { link.send(report(ctx)) }
    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        link.send(report(ctx)); return true
    }

    private fun report(ctx: Context): NetworkPacket {
        var type = "Unknown"; var level = 0
        if (granted(ctx, Manifest.permission.READ_PHONE_STATE)) runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            type = networkType(tm.dataNetworkType)
            level = tm.signalStrength?.level ?: 0
        }
        val entry = JSONObject().put("networkType", type).put("signalStrength", level)
        return NetworkPacket.of("kdeconnect.connectivity_report") {
            put("signalStrengths", JSONObject().put("0", entry))
        }
    }

    private fun networkType(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0 -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
        else -> "Mobile"
    }
}
