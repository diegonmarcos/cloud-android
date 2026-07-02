package com.diegonmarcos.superapp.firewall

/**
 * Pure, netstack-agnostic decision core. No Android deps → unit-testable.
 *
 * The firestack bridge calls [block] per connection from `FlowListener.flow`
 * (OUT) / `inflow` (IN) with the real direction; [FirewallFlowPolicy] wraps
 * this with the cloud-VPN routing decision.
 */
object FirewallDecider {

    /** true = drop this flow. Blocked if ANY assigned rule matches.
     *  Used by [FirewallFlowPolicy] (staged firestack merge). */
    fun block(rules: List<RuleSpec>, flow: Direction, t: Transport, e: Energy): Boolean =
        rules.any { it.blocks(flow, t, e) }

    /** Interim verdict for the shipping drain-engine: true = drop ALL of this
     *  app's traffic right now. Inbound-only rules can't be honoured by
     *  drain-and-drop, so they're skipped (they light up under firestack). */
    fun interimBlocked(rules: List<RuleSpec>, t: Transport, e: Energy): Boolean =
        rules.any { it.appliesInterim(t, e) }
}
