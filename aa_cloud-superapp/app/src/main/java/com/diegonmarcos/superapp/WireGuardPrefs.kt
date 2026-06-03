package com.diegonmarcos.superapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import org.json.JSONArray
import org.json.JSONObject

/**
 * WireGuard tunnel persistence — Interface + a list of Peers used by
 * libs:net's [com.wireguard.android.backend.GoBackend]. Plain
 * SharedPreferences (the form ships persisted values back through
 * GoBackend at Connect time; pre-shared key + private key are
 * sensitive but stored here unencrypted to match the rest of the
 * Configs UX — the threat model for a phone with a granted VPN
 * profile is already root-equivalent).
 *
 * First-run defaults come from BuildConfig.UI_WG_* (Interface fields
 * are individual Strings; peers come baked as a single base64 JSON
 * blob, parsed lazily on first read). Key material is intentionally
 * NOT seeded — the user pastes / imports / generates.
 *
 * Peers are stored as a JSON array under one SharedPref key so add /
 * remove / reorder are atomic. UI calls [peers] for the current list
 * and [savePeers] to commit edits.
 *
 * [toWgConfig] hydrates an upstream [Config] from the stored fields
 * (Interface + every peer); throws on validation so the caller can
 * show the error inline.
 */
class WireGuardPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("wireguard_prefs", Context.MODE_PRIVATE)

    var tunnelName: String
        get() = sp.getString(K_TUNNEL_NAME, BuildConfig.UI_WG_TUNNEL_NAME)
            ?: BuildConfig.UI_WG_TUNNEL_NAME
        set(value) { sp.edit().putString(K_TUNNEL_NAME, value).apply() }

    var interfacePrivateKey: String
        get() = sp.getString(K_IF_PRIVKEY, "") ?: ""
        set(value) { sp.edit().putString(K_IF_PRIVKEY, value).apply() }

    var interfaceAddress: String
        get() = sp.getString(K_IF_ADDRESS, BuildConfig.UI_WG_INTERFACE_ADDRESS)
            ?: BuildConfig.UI_WG_INTERFACE_ADDRESS
        set(value) { sp.edit().putString(K_IF_ADDRESS, value).apply() }

    var interfaceDns: String
        get() = sp.getString(K_IF_DNS, BuildConfig.UI_WG_INTERFACE_DNS)
            ?: BuildConfig.UI_WG_INTERFACE_DNS
        set(value) { sp.edit().putString(K_IF_DNS, value).apply() }

    var interfaceListenPort: String
        get() = sp.getString(K_IF_LISTEN_PORT, BuildConfig.UI_WG_INTERFACE_LISTEN_PORT)
            ?: BuildConfig.UI_WG_INTERFACE_LISTEN_PORT
        set(value) { sp.edit().putString(K_IF_LISTEN_PORT, value).apply() }

    var interfaceMtu: String
        get() = sp.getString(K_IF_MTU, BuildConfig.UI_WG_INTERFACE_MTU)
            ?: BuildConfig.UI_WG_INTERFACE_MTU
        set(value) { sp.edit().putString(K_IF_MTU, value).apply() }

    /** Last user-driven Connect/Disconnect state — restored on app
     *  restart so the toggle reflects the actual tunnel state. */
    var tunnelEnabled: Boolean
        get() = sp.getBoolean(K_TUNNEL_ENABLED, false)
        set(value) { sp.edit().putBoolean(K_TUNNEL_ENABLED, value).apply() }

    /** Current peer list. Falls back to the build-time default
     *  (UI_WG_PEERS_JSON_B64) on first read. */
    fun peers(): MutableList<PeerData> {
        val stored = sp.getString(K_PEERS_JSON, null)
        val raw = stored ?: defaultPeersJson()
        return parsePeers(raw).toMutableList()
    }

    fun savePeers(peers: List<PeerData>) {
        val arr = JSONArray()
        for (p in peers) arr.put(p.toJson())
        sp.edit().putString(K_PEERS_JSON, arr.toString()).apply()
    }

    /**
     * Derive the interface public key from the stored private key.
     * Returns empty string if no private key is set or the value
     * isn't a valid base64-encoded 32-byte Curve25519 key. UI surfaces
     * this as a read-only field so the user can verify their key
     * material.
     */
    fun derivedInterfacePublicKey(): String = try {
        if (interfacePrivateKey.isBlank()) ""
        else KeyPair(Key.fromBase64(interfacePrivateKey)).publicKey.toBase64()
    } catch (_: Throwable) {
        ""
    }

    /**
     * Build the upstream Config from the stored fields. Throws on any
     * validation failure so the caller can show the error inline.
     */
    fun toWgConfig(): Config {
        val ifBuilder = Interface.Builder()
            .parsePrivateKey(interfacePrivateKey)
            .parseAddresses(interfaceAddress)
        if (interfaceDns.isNotBlank())          ifBuilder.parseDnsServers(interfaceDns)
        if (interfaceListenPort.isNotBlank())   ifBuilder.parseListenPort(interfaceListenPort)
        if (interfaceMtu.isNotBlank())          ifBuilder.parseMtu(interfaceMtu)

        val cfg = Config.Builder().setInterface(ifBuilder.build())
        for (p in peers()) {
            val pb = Peer.Builder()
                .parsePublicKey(p.publicKey)
                .parseAllowedIPs(p.allowedIps)
            if (p.presharedKey.isNotBlank())          pb.parsePreSharedKey(p.presharedKey)
            if (p.endpoint.isNotBlank())              pb.parseEndpoint(p.endpoint)
            if (p.persistentKeepalive.isNotBlank())   pb.parsePersistentKeepalive(p.persistentKeepalive)
            cfg.addPeer(pb.build())
        }
        return cfg.build()
    }

    private fun defaultPeersJson(): String {
        val b64 = BuildConfig.UI_WG_PEERS_JSON_B64
        if (b64.isBlank()) return "[]"
        return try {
            String(Base64.decode(b64, Base64.DEFAULT))
        } catch (_: Throwable) {
            "[]"
        }
    }

    private fun parsePeers(raw: String): List<PeerData> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PeerData.fromJson(arr.getJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * One WireGuard peer. `name` is a UI-only label so the user can
     * tell "gcp-proxy" apart from "oci-apps" in the list; it's stripped
     * before the upstream [Peer.Builder] is built.
     */
    data class PeerData(
        val name: String,
        val publicKey: String,
        val presharedKey: String,
        val endpoint: String,
        val allowedIps: String,
        val persistentKeepalive: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("public_key", publicKey)
            put("preshared_key", presharedKey)
            put("endpoint", endpoint)
            put("allowed_ips", allowedIps)
            put("persistent_keepalive", persistentKeepalive)
        }

        companion object {
            val EMPTY = PeerData("", "", "", "", "", "")

            fun fromJson(o: JSONObject): PeerData = PeerData(
                name                = o.optString("name", ""),
                publicKey           = o.optString("public_key", ""),
                presharedKey        = o.optString("preshared_key", ""),
                endpoint            = o.optString("endpoint", ""),
                allowedIps          = o.optString("allowed_ips", ""),
                persistentKeepalive = o.optString("persistent_keepalive", ""),
            )
        }
    }

    companion object {
        private const val K_TUNNEL_NAME    = "tunnel_name"
        private const val K_IF_PRIVKEY     = "if_privkey"
        private const val K_IF_ADDRESS     = "if_address"
        private const val K_IF_DNS         = "if_dns"
        private const val K_IF_LISTEN_PORT = "if_listen_port"
        private const val K_IF_MTU         = "if_mtu"
        private const val K_PEERS_JSON     = "peers_json"
        private const val K_TUNNEL_ENABLED = "tunnel_enabled"
    }
}
