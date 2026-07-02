package com.diegonmarcos.superapp.firewall

/**
 * Pure, netstack-agnostic decision core. No Android deps → unit-testable.
 * A flow is blocked if ANY axis of the [AppRule] blocks it (parallel sum).
 */
object FirewallDecider {

    /** Direction-aware per-flow verdict (firestack path): true = drop. */
    fun block(r: AppRule, flow: Direction, t: Transport, isBackground: Boolean): Boolean =
        r.transportBlocks(t) ||
            (isBackground && !r.background) ||
            r.directionBlocks(flow)

    /** Interim drain-engine verdict: true = drop ALL of this app's traffic now.
     *  The drain-engine can't split inbound/outbound, so only the direction=ALL
     *  case of the direction axis is honoured here; IN/OUT need firestack. */
    fun interimBlocked(r: AppRule, t: Transport, isBackground: Boolean): Boolean =
        r.transportBlocks(t) ||
            (isBackground && !r.background) ||
            r.direction == Direction.ALL
}
