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
import java.net.Socket

/**
 * Self-contained, wg0-preferred KDE Connect discovery. No dependency on the
 * installed org.kde.kdeconnect_tp app.
 *
 * KDE Connect discovery works by sending a UDP **identity** packet to the
 * peer's port 1716; the peer reads the packet's **source IP** and opens a TCP
 * link back to it. So which interface the packet leaves on matters: to reach
 * the Surface at its mesh IP (10.0.0.5) the packet MUST egress the native wg0
 * tunnel, otherwise its source is a Wi-Fi address the Surface can't route back
 * to. We therefore bind the probe + identity socket to the active
 * VPN-transport [Network] (the phone's native WireGuard) and try that FIRST;
 * then the default route; then a self-contained LAN broadcast.
 *
 * SLICE scope: reachability + identity nudge only. The TLS pairing + plugin
 * link is the upstream vendor phase (upstreams.kde-connect).
 */
object KdeConnectDiscovery {
    private const val TAG = "KdeConnect/Discovery"

    sealed class Result {
        /** Surface answered on wg_ip:port over the wg0 tunnel; identity sent. */
        data class Wg0Reachable(val device: KdeConnectConfig.Device, val viaVpnBind: Boolean) : Result()
        /** wg0 didn't reach the Surface; a LAN-broadcast identity was sent. */
        object LanFallback : Result()
        /** Nothing reachable and no fallback permitted. */
        object Unreachable : Result()
    }

    /**
     * Decide + act on the route for the primary device. Pure network I/O —
     * hops to [Dispatchers.IO] internally.
     */
    suspend fun discover(context: Context): Result = withContext(Dispatchers.IO) {
        val cfg = KdeConnectConfig.get()
        val device = cfg.primaryDevice
        if (device == null || device.wgIp.isBlank()) return@withContext Result.Unreachable

        if (cfg.preferWg0) {
            // 1. Native wg0 explicitly: bind to the VPN-transport network so the
            //    packet leaves the tunnel with the wg0 source IP.
            val vpn = vpnNetwork(context)
            if (vpn != null && probe(vpn, device.wgIp, cfg.discoveryPort, cfg.probeTimeoutMs)) {
                runCatching { sendIdentity(vpn, device.wgIp, cfg.discoveryPort) }
                    .onFailure { Log.w(TAG, "identity over vpn-bind failed: ${it.message}") }
                Log.i(TAG, "reached ${device.wgIp} via VPN-bound wg0")
                return@withContext Result.Wg0Reachable(device, viaVpnBind = true)
            }
            // 2. Default route — covers the case where wg0 is already the
            //    system default network (so no explicit bind is needed).
            if (probe(null, device.wgIp, cfg.discoveryPort, cfg.probeTimeoutMs)) {
                runCatching { sendIdentity(null, device.wgIp, cfg.discoveryPort) }
                    .onFailure { Log.w(TAG, "identity over default route failed: ${it.message}") }
                Log.i(TAG, "reached ${device.wgIp} via default route")
                return@withContext Result.Wg0Reachable(device, viaVpnBind = false)
            }
            Log.i(TAG, "${device.wgIp}:${cfg.discoveryPort} not reachable over wg0")
        }

        // 3. Self-contained LAN fallback — broadcast an identity on the local
        //    segment (the standard non-VPN discovery path). No external app.
        if (cfg.lanFallback) {
            runCatching { broadcastIdentity(cfg.discoveryPort) }
                .onFailure { Log.w(TAG, "LAN broadcast failed: ${it.message}") }
            return@withContext Result.LanFallback
        }
        Result.Unreachable
    }

    /** The active VPN-transport network (the phone's native WireGuard), or null
     *  if no VPN is up. Binding sockets to it forces egress over wg0. */
    private fun vpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /** TCP connect to [host]:[port] within [timeoutMs], optionally pinned to
     *  [network] so the attempt routes over a specific interface (wg0). */
    fun probe(network: Network?, host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { s ->
                network?.bindSocket(s)
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)

    /** Unicast KDE Connect identity (protocol v7) to [host]:[port], optionally
     *  pinned to [network]. The peer connects back to this packet's source IP,
     *  so binding to the wg0 [network] is what makes the link traverse the
     *  tunnel. Best-effort. */
    private fun sendIdentity(network: Network?, host: String, port: Int) {
        DatagramSocket().use { sock ->
            network?.bindSocket(sock)
            val bytes = identityBytes(port)
            sock.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(host, port)))
        }
        Log.i(TAG, "sent unicast identity to $host:$port (vpnBound=${network != null})")
    }

    /** Self-contained LAN discovery — broadcast the identity to the local
     *  segment on [port]. Replaces the old "launch the installed app" path. */
    private fun broadcastIdentity(port: Int) {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            val bytes = identityBytes(port)
            sock.send(DatagramPacket(bytes, bytes.size,
                InetSocketAddress(InetAddress.getByName("255.255.255.255"), port)))
        }
        Log.i(TAG, "broadcast identity on LAN :$port")
    }

    /** A minimal, well-formed KDE Connect identity datagram (newline-terminated
     *  JSON) the daemon accepts on its discovery socket. */
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
