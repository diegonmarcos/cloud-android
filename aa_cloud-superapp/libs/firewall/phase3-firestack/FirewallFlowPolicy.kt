package com.diegonmarcos.superapp.firewall

/**
 * Phase-3 decision core: maps ONE firestack flow to a routing verdict. PURE
 * (no Android / no firestack deps) so it is unit-testable without the aar.
 *
 * Firestack calls `FlowListener.flow()` (outbound) / `inflow()` (inbound) per
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
     * @param rule         the app's rule (default ⇒ fully allowed)
     * @param direction    OUT for flow(), IN for inflow()
     * @param transport    the real physical transport right now
     * @param isBackground per-app background state (screen × foreground)
     * @param cloudVpnUp   true when a WireGuard proxy is registered & usable
     *
     * VPN routing is driven by the EXPLICIT [AppRule.vpnOnly] override (the
     * user picking "wg0 / wg-public VPN only"), NOT by an implicit heuristic —
     * "Wi-Fi only" means block off-Wi-Fi, it does not silently tunnel. The
     * background / direction axes still apply on top of a VPN-only rule. This
     * is the merged-service superpower the interim drain-engine couldn't do.
     */
    fun verdict(
        rule: AppRule,
        direction: Direction,
        transport: Transport,
        isBackground: Boolean,
        cloudVpnUp: Boolean,
    ): FlowVerdict {
        if (rule.isDefault) return FlowVerdict.ALLOW_DIRECT

        if (rule.vpnOnly) {
            // Explicit cloud-VPN-only: the wifi/cellular toggles don't apply,
            // but the background / direction axes still can block outright.
            val blockedByOtherAxis =
                (isBackground && !rule.background) || rule.directionBlocks(direction)
            return when {
                blockedByOtherAxis -> FlowVerdict.BLOCK
                cloudVpnUp -> FlowVerdict.ALLOW_VPN
                else -> FlowVerdict.BLOCK // VPN-only but the tunnel is down
            }
        }

        return if (FirewallDecider.block(rule, direction, transport, isBackground))
            FlowVerdict.BLOCK else FlowVerdict.ALLOW_DIRECT
    }
}
