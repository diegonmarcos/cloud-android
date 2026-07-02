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

    /** The firestack proxy id the WG peer is registered under (e.g. "wg1"),
     *  or null when no cloud VPN is configured. */
    fun proxyId(): String?

    /** The WireGuard config (wg-quick / uapi form) to register as a firestack
     *  proxy, or null when unconfigured. Consumed by the tunnel service. */
    fun wgConfig(): String?

    companion object {
        /** No cloud VPN — every "vpn-only" app is simply blocked when its
         *  physical transport is disallowed. */
        val NONE: CloudVpnProvider = object : CloudVpnProvider {
            override fun isUp() = false
            override fun proxyId(): String? = null
            override fun wgConfig(): String? = null
        }
    }
}
