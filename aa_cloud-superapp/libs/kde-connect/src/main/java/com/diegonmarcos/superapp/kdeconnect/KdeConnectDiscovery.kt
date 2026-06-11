package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Self-contained, wg0-preferred KDE Connect discovery — and its own diagnostics.
 * No dependency on the installed org.kde.kdeconnect_tp app.
 *
 * KDE Connect discovery sends a UDP **identity** packet to the peer's port 1716;
 * the peer reads the packet's **source IP** and opens a TCP link back. So the
 * packet must egress the native wg0 tunnel (source = wg0 IP) to reach the Surface
 * at 10.0.0.5. We bind the probe + identity socket to the active VPN-transport
 * [Network] (the phone's native WireGuard) and try that FIRST; then the default
 * route; then a self-contained LAN broadcast.
 *
 * [diagnose] returns the full step-by-step trace so the Configs page can show
 * exactly which route was used + the ping RTT — no hidden behaviour.
 */
object KdeConnectDiscovery {
    private const val TAG = "KdeConnect/Discovery"

    sealed class Result {
        data class Wg0Reachable(val device: KdeConnectConfig.Device, val viaVpnBind: Boolean) : Result()
        object LanFallback : Result()
        object Unreachable : Result()
    }

    /** One visible diagnostic line. [ok] = true (✓) / false (✗) / null (•info). */
    data class Step(val label: String, val detail: String, val ok: Boolean?)

    data class Report(val result: Result, val steps: List<Step>)

    /** Run discovery and return both the outcome and a human-readable trace. */
    suspend fun diagnose(context: Context): Report = withContext(Dispatchers.IO) {
        val cfg = KdeConnectConfig.get()
        val steps = mutableListOf<Step>()
        val device = cfg.primaryDevice
        if (device == null || device.wgIp.isBlank()) {
            steps += Step("Device", "none declared in build.json::ui.kde_connect", false)
            return@withContext Report(Result.Unreachable, steps)
        }
        steps += Step("Target", "${device.label} · ${device.wgIp}:${cfg.discoveryPort}", null)

        if (cfg.preferWg0) {
            // --- native wg0 (VPN-bound) ---
            val vpn = vpnNetwork(context)
            if (vpn != null) {
                val (iface, localIp) = vpnLink(context, vpn)
                steps += Step("wg0 tunnel", "up · iface=${iface ?: "?"} local=${localIp ?: "?"}", true)
                val rtt = probeRtt(vpn, device.wgIp, cfg.discoveryPort, cfg.probeTimeoutMs)
                if (rtt != null) {
                    steps += Step("Ping ${device.wgIp}:${cfg.discoveryPort} (wg0-bound)", "reachable in ${rtt} ms", true)
                    val sent = runCatching { sendIdentity(vpn, device.wgIp, cfg.discoveryPort) }.isSuccess
                    steps += Step("Identity nudge (wg0)", if (sent) "unicast sent" else "send failed", sent)
                    return@withContext Report(Result.Wg0Reachable(device, viaVpnBind = true), steps)
                }
                steps += Step("Ping ${device.wgIp}:${cfg.discoveryPort} (wg0-bound)", "no answer within ${cfg.probeTimeoutMs} ms", false)
            } else {
                steps += Step("wg0 tunnel", "no VPN-transport network is up", false)
            }

            // --- default route (covers wg0 already being the system default) ---
            val rttDef = probeRtt(null, device.wgIp, cfg.discoveryPort, cfg.probeTimeoutMs)
            if (rttDef != null) {
                steps += Step("Ping ${device.wgIp}:${cfg.discoveryPort} (default route)", "reachable in ${rttDef} ms", true)
                val sent = runCatching { sendIdentity(null, device.wgIp, cfg.discoveryPort) }.isSuccess
                steps += Step("Identity nudge (default)", if (sent) "unicast sent" else "send failed", sent)
                return@withContext Report(Result.Wg0Reachable(device, viaVpnBind = false), steps)
            }
            steps += Step("Ping ${device.wgIp}:${cfg.discoveryPort} (default route)", "no answer within ${cfg.probeTimeoutMs} ms", false)
        }

        // --- self-contained LAN broadcast fallback ---
        if (cfg.lanFallback) {
            val lan = lanAddress()
            val sent = runCatching { broadcastIdentity(cfg.discoveryPort) }.isSuccess
            steps += Step("LAN broadcast :${cfg.discoveryPort}", (if (sent) "sent" else "failed") + (lan?.let { " · from $it" } ?: ""), sent)
            return@withContext Report(Result.LanFallback, steps)
        }
        steps += Step("LAN fallback", "disabled in build.json", null)
        Report(Result.Unreachable, steps)
    }

    /** The active VPN-transport network (the native WireGuard), or null. */
    private fun vpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** (interfaceName, local IPv4) of the VPN network — the wg0 endpoint. */
    private fun vpnLink(context: Context, vpn: Network): Pair<String?, String?> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null to null
        val lp = cm.getLinkProperties(vpn) ?: return null to null
        val v4 = lp.linkAddresses
            .map { it.address.hostAddress.orEmpty() }
            .firstOrNull { it.isNotBlank() && !it.contains(":") }
        return lp.interfaceName to v4
    }

    /** TCP connect RTT in ms to [host]:[port] (pinned to [network] when given),
     *  or null if it didn't answer within [timeoutMs]. This is the "ping". */
    fun probeRtt(network: Network?, host: String, port: Int, timeoutMs: Int): Long? =
        runCatching {
            val start = System.nanoTime()
            Socket().use { s ->
                network?.bindSocket(s)
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()

    private fun sendIdentity(network: Network?, host: String, port: Int) {
        DatagramSocket().use { sock ->
            network?.bindSocket(sock)
            val bytes = identityBytes(port)
            sock.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(host, port)))
        }
        Log.i(TAG, "sent unicast identity to $host:$port (vpnBound=${network != null})")
    }

    private fun broadcastIdentity(port: Int) {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            val bytes = identityBytes(port)
            sock.send(DatagramPacket(bytes, bytes.size,
                InetSocketAddress(InetAddress.getByName("255.255.255.255"), port)))
        }
        Log.i(TAG, "broadcast identity on LAN :$port")
    }

    /** First non-loopback site-local IPv4 (the Wi-Fi/LAN address) for display. */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.isNotBlank() && !it.contains(":") && it.startsWith("192.168.") }
    }.getOrNull()

    private fun identityBytes(tcpPort: Int): ByteArray {
        val identity = JSONObject().apply {
            put("id", 0)
            put("type", "kdeconnect.identity")
            put("body", JSONObject().apply {
                put("deviceId", "cloud-superapp")
                put("deviceName", "Cloud SuperApp")
                put("protocolVersion", 7)
                put("deviceType", "phone")
                put("incomingCapabilities", JSONArray())
                put("outgoingCapabilities", JSONArray())
                put("tcpPort", tcpPort)
            })
        }
        return (identity.toString() + "\n").toByteArray(Charsets.UTF_8)
    }
}
