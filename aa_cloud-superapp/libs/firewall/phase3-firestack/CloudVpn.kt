package com.diegonmarcos.superapp.firewall

/**
 * The merged firestack service routes "cloud-VPN only" flows through the
 * WireGuard proxy. That WG config lives in `libs:net` / the app's network
 * package — which `libs:firewall` must NOT import (lib→app is forbidden).
 *
 * So the app injects a [CloudVpnProvider] into [FirewallController]; the
 * firewall depends only on this thin seam.
 */
interface CloudVpnProvider {
    /** True when a usable WireGuard tunnel/proxy is available right now. */
    fun isUp(): Boolean

    /** The firestack proxy id to route a flow through, chosen by the app's
     *  [AppRule.vpnMode] ([VpnMode.WG0_ONLY] → wg0 peer, [VpnMode.WG_PUBLIC_ONLY]
     *  → wg-public peer). [VpnMode.NONE] asks for the default/preferred cloud
     *  tunnel. Returns null when that tunnel isn't configured. */
    fun proxyId(mode: VpnMode = VpnMode.NONE): String?

    /** The WireGuard config (wg-quick / uapi form) to register as a firestack
     *  proxy, or null when unconfigured. Consumed by the tunnel service. */
    fun wgConfig(mode: VpnMode = VpnMode.NONE): String?

    companion object {
        /** No cloud VPN — every "vpn-only" app is simply blocked. */
        val NONE: CloudVpnProvider = object : CloudVpnProvider {
            override fun isUp() = false
            override fun proxyId(mode: VpnMode): String? = null
            override fun wgConfig(mode: VpnMode): String? = null
        }
    }
}
