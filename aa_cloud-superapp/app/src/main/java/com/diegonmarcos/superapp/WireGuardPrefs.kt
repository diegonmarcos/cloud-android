package com.diegonmarcos.superapp

import android.content.Context
import android.content.SharedPreferences
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * WireGuard tunnel persistence — Interface + Peer fields used by
 * libs:net's [com.wireguard.android.backend.GoBackend]. Plain
 * SharedPreferences (the form ships persisted values back through
 * GoBackend at Connect time; pre-shared key + private key are
 * sensitive but stored here unencrypted to match the rest of the
 * Configs UX — the threat model for a phone with a granted VPN
 * profile is already root-equivalent).
 *
 * First-run defaults come from BuildConfig.UI_WG_* (baked from
 * `build.json::ui.wireguard_default` at compile time). Key material
 * is intentionally NOT seeded — the user pastes / imports / generates.
 *
 * [toWgConfig] hydrates an upstream [Config] from the stored fields;
 * throws [com.wireguard.config.BadConfigException] on invalid input
 * so the caller can show a Snackbar.
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

    var peerPublicKey: String
        get() = sp.getString(K_PEER_PUBKEY, "") ?: ""
        set(value) { sp.edit().putString(K_PEER_PUBKEY, value).apply() }

    var peerPresharedKey: String
        get() = sp.getString(K_PEER_PSK, "") ?: ""
        set(value) { sp.edit().putString(K_PEER_PSK, value).apply() }

    var peerEndpoint: String
        get() = sp.getString(K_PEER_ENDPOINT, BuildConfig.UI_WG_PEER_ENDPOINT)
            ?: BuildConfig.UI_WG_PEER_ENDPOINT
        set(value) { sp.edit().putString(K_PEER_ENDPOINT, value).apply() }

    var peerAllowedIps: String
        get() = sp.getString(K_PEER_ALLOWED_IPS, BuildConfig.UI_WG_PEER_ALLOWED_IPS)
            ?: BuildConfig.UI_WG_PEER_ALLOWED_IPS
        set(value) { sp.edit().putString(K_PEER_ALLOWED_IPS, value).apply() }

    var peerPersistentKeepalive: String
        get() = sp.getString(K_PEER_KEEPALIVE, BuildConfig.UI_WG_PEER_PERSISTENT_KEEPALIVE)
            ?: BuildConfig.UI_WG_PEER_PERSISTENT_KEEPALIVE
        set(value) { sp.edit().putString(K_PEER_KEEPALIVE, value).apply() }

    /** Last user-driven Connect/Disconnect state — restored on app
     *  restart so the toggle reflects the actual tunnel state. */
    var tunnelEnabled: Boolean
        get() = sp.getBoolean(K_TUNNEL_ENABLED, false)
        set(value) { sp.edit().putBoolean(K_TUNNEL_ENABLED, value).apply() }

    /**
     * Derive the interface public key from the stored private key.
     * Returns empty string if no private key set or the value isn't a
     * valid base64-encoded 32-byte Curve25519 key. UI surfaces this as
     * a read-only field so the user can verify their key material.
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

        val peerBuilder = Peer.Builder()
            .parsePublicKey(peerPublicKey)
            .parseAllowedIPs(peerAllowedIps)
        if (peerPresharedKey.isNotBlank())          peerBuilder.parsePreSharedKey(peerPresharedKey)
        if (peerEndpoint.isNotBlank())              peerBuilder.parseEndpoint(peerEndpoint)
        if (peerPersistentKeepalive.isNotBlank())   peerBuilder.parsePersistentKeepalive(peerPersistentKeepalive)

        return Config.Builder()
            .setInterface(ifBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    companion object {
        private const val K_TUNNEL_NAME      = "tunnel_name"
        private const val K_IF_PRIVKEY       = "if_privkey"
        private const val K_IF_ADDRESS       = "if_address"
        private const val K_IF_DNS           = "if_dns"
        private const val K_IF_LISTEN_PORT   = "if_listen_port"
        private const val K_IF_MTU           = "if_mtu"
        private const val K_PEER_PUBKEY      = "peer_pubkey"
        private const val K_PEER_PSK         = "peer_psk"
        private const val K_PEER_ENDPOINT    = "peer_endpoint"
        private const val K_PEER_ALLOWED_IPS = "peer_allowed_ips"
        private const val K_PEER_KEEPALIVE   = "peer_keepalive"
        private const val K_TUNNEL_ENABLED   = "tunnel_enabled"
    }
}
