package com.diegonmarcos.superapp.kdeconnect

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * wg0-preferred KDE Connect discovery.
 *
 * The official client finds devices via UDP **broadcast** on port 1716, which
 * does not cross a WireGuard point-to-point tunnel. So when the wg0 tunnel is
 * up we reach the Surface directly at its mesh IP (the standard non-broadcast
 * "VPN / custom device" path): probe wg_ip:1716 over the tunnel, and on a hit
 * send a **unicast** KDE Connect identity datagram there to nudge the Surface
 * daemon into linking back. When the tunnel is down we fall back to the normal
 * LAN path (the caller launches the official app, which broadcasts as usual).
 *
 * SLICE scope: this does discovery + nudge + reachability only. Full TLS
 * pairing + plugins arrive with the upstream vendor (upstreams.kde-connect).
 */
object KdeConnectDiscovery {
    private const val TAG = "KdeConnect/Discovery"

    sealed class Result {
        /** Surface answered on wg_ip:port over the tunnel; identity nudge sent. */
        data class Wg0Reachable(val device: KdeConnectConfig.Device) : Result()
        /** Tunnel down / unreachable but LAN fallback is allowed. */
        object LanFallback : Result()
        /** Nothing reachable and no fallback permitted. */
        object Unreachable : Result()
    }

    /**
     * Decide the route for the primary device. Pure network I/O — call off the
     * main thread (it already hops to [Dispatchers.IO]).
     */
    suspend fun discover(): Result = withContext(Dispatchers.IO) {
        val cfg = KdeConnectConfig.get()
        val device = cfg.primaryDevice
        if (cfg.preferWg0 && device != null && device.wgIp.isNotBlank()) {
            if (probe(device.wgIp, cfg.discoveryPort, cfg.probeTimeoutMs)) {
                runCatching { sendIdentity(device.wgIp, cfg.discoveryPort) }
                    .onFailure { Log.w(TAG, "identity nudge failed: ${it.message}") }
                return@withContext Result.Wg0Reachable(device)
            }
            Log.i(TAG, "${device.wgIp}:${cfg.discoveryPort} not reachable over wg0")
        }
        if (cfg.lanFallback) Result.LanFallback else Result.Unreachable
    }

    /** True iff a TCP connection to [host]:[port] completes within [timeoutMs].
     *  Over wg0 the Surface's mesh IP is only routable through the tunnel, so a
     *  successful connect is a reliable "tunnel up AND device up" signal. */
    fun probe(host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)

    /** Send a minimal, well-formed KDE Connect identity datagram (protocol v7)
     *  to [host]:[port]. Newline-terminated JSON, exactly as the daemon expects
     *  on its discovery socket. Best-effort nudge — fire and forget. */
    private fun sendIdentity(host: String, port: Int) {
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
                put("tcpPort", port)
            })
        }
        val bytes = (identity.toString() + "\n").toByteArray(Charsets.UTF_8)
        DatagramSocket().use { sock ->
            sock.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(host, port)))
        }
        Log.i(TAG, "sent unicast identity to $host:$port over wg0")
    }
}
