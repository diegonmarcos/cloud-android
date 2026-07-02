package com.diegonmarcos.superapp.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves the pure decision core against the user's own rule examples. */
class FirewallDeciderTest {

    private val all = setOf(Transport.WIFI, Transport.CELL, Transport.VPN, Transport.OTHER)
    private val bothEnergy = setOf(Energy.ACTIVE, Energy.BACKGROUND)

    private fun rule(block: Direction, t: Set<Transport>, e: Set<Energy>) =
        RuleSpec("r", "r", block, t, e)

    @Test fun blockAll_dropsEveryFlow() {
        val p = listOf(rule(Direction.ALL, all, bothEnergy))
        assertTrue(FirewallDecider.block(p, Direction.OUT, Transport.WIFI, Energy.ACTIVE))
        assertTrue(FirewallDecider.block(p, Direction.IN, Transport.CELL, Energy.BACKGROUND))
    }

    @Test fun wifiOnly_blocksCellAllowsWifi() {
        // "get data only in wifi" = block ALL on non-wifi transports.
        val p = listOf(rule(Direction.ALL, setOf(Transport.CELL, Transport.OTHER), bothEnergy))
        assertTrue(FirewallDecider.block(p, Direction.OUT, Transport.CELL, Energy.ACTIVE))
        assertFalse(FirewallDecider.block(p, Direction.OUT, Transport.WIFI, Energy.ACTIVE))
    }

    @Test fun vpnOnly_allowsOnlyUnderVpn() {
        // "only get data when under cloud vpn"
        val p = listOf(rule(Direction.ALL, setOf(Transport.WIFI, Transport.CELL, Transport.OTHER), bothEnergy))
        assertFalse(FirewallDecider.block(p, Direction.OUT, Transport.VPN, Energy.ACTIVE))
        assertTrue(FirewallDecider.block(p, Direction.OUT, Transport.WIFI, Energy.ACTIVE))
    }

    @Test fun wifiOnly_noBackground_needsTwoRules() {
        // "work only on wifi and no background" = block non-wifi  OR  block in background.
        val p = listOf(
            rule(Direction.ALL, setOf(Transport.CELL, Transport.VPN, Transport.OTHER), bothEnergy),
            rule(Direction.ALL, all, setOf(Energy.BACKGROUND)),
        )
        assertFalse(FirewallDecider.block(p, Direction.OUT, Transport.WIFI, Energy.ACTIVE))   // allowed
        assertTrue(FirewallDecider.block(p, Direction.OUT, Transport.WIFI, Energy.BACKGROUND)) // bg blocked
        assertTrue(FirewallDecider.block(p, Direction.OUT, Transport.CELL, Energy.ACTIVE))     // cell blocked
    }

    @Test fun incomingOnly_blocksInboundLeavesOutbound() {
        val p = listOf(rule(Direction.IN, all, bothEnergy))
        assertTrue(FirewallDecider.block(p, Direction.IN, Transport.WIFI, Energy.ACTIVE))
        assertFalse(FirewallDecider.block(p, Direction.OUT, Transport.WIFI, Energy.ACTIVE))
    }

    @Test fun emptyPolicy_allowsEverything() {
        assertFalse(FirewallDecider.block(emptyList(), Direction.OUT, Transport.CELL, Energy.BACKGROUND))
    }

    // ── shipping drain-engine (interimBlocked: drop-all when a non-IN rule's
    //    condition matches; inbound-only rules can't be enforced this way) ──

    @Test fun interim_dropsAppWhenConditionMatches() {
        val p = listOf(rule(Direction.ALL, setOf(Transport.CELL), bothEnergy)) // no-cell
        assertTrue(FirewallDecider.interimBlocked(p, Transport.CELL, Energy.ACTIVE))
        assertFalse(FirewallDecider.interimBlocked(p, Transport.WIFI, Energy.ACTIVE))
    }

    @Test fun interim_cannotEnforceInboundOnly() {
        val p = listOf(rule(Direction.IN, all, bothEnergy))
        assertFalse(FirewallDecider.interimBlocked(p, Transport.WIFI, Energy.ACTIVE))
    }

    @Test fun interim_emptyPolicyAllows() {
        assertFalse(FirewallDecider.interimBlocked(emptyList(), Transport.CELL, Energy.BACKGROUND))
    }

    @Test fun ruleSpec_jsonRoundTrips() {
        val r = rule(Direction.OUT, setOf(Transport.WIFI, Transport.VPN), setOf(Energy.BACKGROUND))
        assertEquals(r, RuleSpec.fromJson(r.toJson()))
    }
}
