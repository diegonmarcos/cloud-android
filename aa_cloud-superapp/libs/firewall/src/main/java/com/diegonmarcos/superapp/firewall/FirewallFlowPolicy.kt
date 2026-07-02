package com.diegonmarcos.superapp.firewall

/**
 * Phase-3 decision core: maps ONE firestack flow to a routing verdict. PURE
 * (no Android / no firestack deps) so it is unit-testable without the aar.
 *
 * Firestack calls `FlowListener.Flow()` (outbound) / `Inflow()` (inbound) per
 * connection with the owning app's `uid`; [FirewallFlowBridge] resolves the
 * uid→package + live conditions and delegates the actual decision here.
 *
 * Three verdicts map to firestack proxy ids (Backend.Block / Backend.Exit /
 * a WireGuard proxy id) in the bridge:
 *  - [BLOCK]        → drop the flow (Mark = Backend.Block)
 *  - [ALLOW_DIRECT] → allow via the real network (Mark = Backend.Exit)
 *  - [ALLOW_VPN]    → allow but route through the cloud WireGuard proxy
 */
enum class FlowVerdict { BLOCK, ALLOW_DIRECT, ALLOW_VPN }

object FirewallFlowPolicy {

    /**
     * @param policy       the app's assigned rules (empty ⇒ fully allowed)
     * @param direction    OUT for Flow(), IN for Inflow()
     * @param transport    the real physical transport right now (never VPN —
     *                     the VPN *is* our tunnel; VPN intent is expressed by
     *                     routing via the WG proxy, below)
     * @param energy       per-app energy (screen × foreground)
     * @param cloudVpnUp   true when a WireGuard proxy is registered & usable
     *
     * "Cloud-VPN only" apps (policy blocks every physical transport but would
     * allow VPN) are ROUTED through the WG proxy when it's up — so they're
     * effectively always-under-VPN — and blocked when it's down. This is the
     * merged-service superpower the interim drain-engine couldn't do.
     */
    fun verdict(
        policy: List<RuleSpec>,
        direction: Direction,
        transport: Transport,
        energy: Energy,
        cloudVpnUp: Boolean,
    ): FlowVerdict {
        if (policy.isEmpty()) return FlowVerdict.ALLOW_DIRECT

        val blockedOnPhysical = FirewallDecider.block(policy, direction, transport, energy)
        val blockedIfVpn = FirewallDecider.block(policy, direction, Transport.VPN, energy)

        return when {
            // Allowed on the real network as-is.
            !blockedOnPhysical -> FlowVerdict.ALLOW_DIRECT
            // Blocked physically but permitted under VPN, and VPN is up →
            // tunnel it through WG to honour the "only under cloud VPN" intent.
            !blockedIfVpn && cloudVpnUp -> FlowVerdict.ALLOW_VPN
            // Blocked, and VPN can't rescue it.
            else -> FlowVerdict.BLOCK
        }
    }
}
