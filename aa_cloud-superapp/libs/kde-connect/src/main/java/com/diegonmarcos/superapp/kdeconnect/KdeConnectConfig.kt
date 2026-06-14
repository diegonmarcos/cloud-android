package com.diegonmarcos.superapp.kdeconnect

import android.util.Base64
import org.json.JSONObject

/**
 * Runtime view of `build.json::ui.kde_connect` — the single source of truth
 * for the KDE Connect integration. Baked into BuildConfig.KDE_CONNECT_B64 at
 * gradle eval time, parsed lazily. No IP / package / port is ever hardcoded
 * in Kotlin — editing build.json is the only knob.
 */
object KdeConnectConfig {

    /** One declared peer (the Surface Pro today). [wgIp] is its WireGuard
     *  mesh address (data/mesh.json), reachable only over the wg0 tunnel. */
    data class Device(
        val id: String,
        val label: String,
        val wgIp: String,
        val primary: Boolean,
    )

    /** One entry of the KDE Connect plugin catalog (build.json plugins[]).
     *  [status] = "active" (we implement + advertise it) or "planned". */
    data class Plugin(
        val id: String,
        val name: String,
        val packet: String,
        val dir: String,      // in | out | both (phone's view)
        val status: String,   // active | planned
        val action: String,   // on-device button action (active only), or ""
    ) {
        val active: Boolean get() = status == "active"
    }

    data class Config(
        /** Official client package handed off to for LAN discovery + pairing. */
        val pkg: String,
        /** Our advertised device name on the mesh (build.json self_name);
         *  blank → fall back to the phone model at runtime. */
        val selfName: String,
        /** KDE Connect's fixed discovery/identity port (1716). */
        val discoveryPort: Int,
        /** Try the wg0 route before LAN broadcast. */
        val preferWg0: Boolean,
        /** Fall back to LAN broadcast when wg0 is unreachable. */
        val lanFallback: Boolean,
        /** TCP-probe timeout used to decide "is the Surface up over wg0". */
        val probeTimeoutMs: Int,
        val devices: List<Device>,
        val plugins: List<Plugin>,
    ) {
        val primaryDevice: Device?
            get() = devices.firstOrNull { it.primary } ?: devices.firstOrNull()
    }

    @Volatile private var cached: Config? = null

    fun get(): Config {
        cached?.let { return it }
        val json = String(Base64.decode(BuildConfig.KDE_CONNECT_B64, Base64.NO_WRAP))
        val o = JSONObject(json)
        val devices = mutableListOf<Device>()
        val arr = o.optJSONArray("devices")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                devices.add(
                    Device(
                        id      = d.optString("id", ""),
                        label   = d.optString("label", d.optString("id", "device")),
                        wgIp    = d.optString("wg_ip", ""),
                        primary = d.optBoolean("primary", false),
                    )
                )
            }
        }
        val plugins = mutableListOf<Plugin>()
        o.optJSONArray("plugins")?.let { pa ->
            for (i in 0 until pa.length()) {
                val p = pa.getJSONObject(i)
                plugins.add(Plugin(
                    id     = p.optString("id", ""),
                    name   = p.optString("name", p.optString("id", "")),
                    packet = p.optString("packet", ""),
                    dir    = p.optString("dir", "both"),
                    status = p.optString("status", "planned"),
                    action = p.optString("action", ""),
                ))
            }
        }
        val parsed = Config(
            pkg            = o.optString("package", "org.kde.kdeconnect_tp"),
            selfName       = o.optString("self_name", ""),
            discoveryPort  = o.optInt("discovery_port", 1716),
            preferWg0      = o.optBoolean("prefer_wg0", true),
            lanFallback    = o.optBoolean("lan_fallback", true),
            probeTimeoutMs = o.optInt("probe_timeout_ms", 1500),
            devices        = devices,
            plugins        = plugins,
        )
        cached = parsed
        return parsed
    }
}
