/**
 * Plain network probes, extracted from feature_node.presentation.util.NetworkExt.
 *
 * They use nothing but android.net / android.net.wifi, but living in a
 * presentation package meant cloud/network/ServerUrlResolver could not be
 * compiled without pulling UI in — the layering violation that blocks
 * extracting the cloud engine into libs:media-center. The Compose half
 * (connectivityState) stays where it is; presentation depending on core is
 * the right direction.
 */
package com.diegonmarcos.mediacenter.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * Whether the active network is a local-area transport (Wi-Fi or Ethernet), as opposed to
 * cellular or no network. Used to warn that LAN-only providers (SMB/NFS) are unreachable.
 */
fun Context.isOnLocalNetwork(): Boolean {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return try {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } catch (_: SecurityException) {
        // ACCESS_NETWORK_STATE unavailable (offline variant / restricted profile).
        false
    }
}

/**
 * Best-effort current Wi-Fi SSID. Returns null when not on Wi-Fi or when the SSID cannot be
 * read (on Android 10+ a real SSID requires location permission + enabled location services;
 * without them the system returns "<unknown ssid>", which we treat as null). Callers should
 * gracefully degrade — e.g. treat a blank configured SSID as "any local network".
 */
@Suppress("DEPRECATION")
fun Context.currentWifiSsid(): String? {
    val wifiManager =
        applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val rawSsid = try {
        wifiManager.connectionInfo?.ssid
    } catch (_: SecurityException) {
        null
    } ?: return null
    val cleaned = rawSsid.trim('"')
    return cleaned.takeIf {
        it.isNotBlank() && !it.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true) &&
                !it.equals("<unknown ssid>", ignoreCase = true)
    }
}