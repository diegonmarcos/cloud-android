package com.diegonmarcos.superapp.firewall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the Phase-3 flow verdict, incl. the cloud-VPN routing superpower. */
class FirewallFlowPolicyTest {

    private val allT = setOf(Transport.WIFI, Transport.CELL, Transport.VPN, Transport.OTHER)
    private val bothE = setOf(Energy.ACTIVE, Energy.BACKGROUND)
    private fun rule(block: Direction, t: Set<Transport>, e: Set<Energy> = bothE) =
        RuleSpec("r", "r", block, t, e)

    private fun v(policy: List<RuleSpec>, dir: Direction, t: Transport, vpnUp: Boolean) =
        FirewallFlowPolicy.verdict(policy, dir, t, Energy.ACTIVE, vpnUp)

    @Test fun noPolicy_allowsDirect() {
        assertEquals(FlowVerdict.ALLOW_DIRECT, v(emptyList(), Direction.OUT, Transport.CELL, false))
    }

    @Test fun blockAll_blocksRegardlessOfVpn() {
        val p = listOf(rule(Direction.ALL, allT))
        assertEquals(FlowVerdict.BLOCK, v(p, Direction.OUT, Transport.WIFI, true))
        assertEquals(FlowVerdict.BLOCK, v(p, Direction.IN, Transport.CELL, true))
    }

    @Test fun vpnOnly_routesViaVpnWhenUp_blocksWhenDown() {
        // "only get data when under cloud vpn" = block every physical transport,
        // allow VPN. Merged service routes it through WG when up.
        val p = listOf(rule(Direction.ALL, setOf(Transport.WIFI, Transport.CELL, Transport.OTHER)))
        assertEquals(FlowVerdict.ALLOW_VPN, v(p, Direction.OUT, Transport.WIFI, true))
        assertEquals(FlowVerdict.BLOCK, v(p, Direction.OUT, Transport.WIFI, false))
    }

    @Test fun wifiOnly_directOnWifi_routesViaVpnOnCell_blocksCellWhenVpnDown() {
        // "wifi only" blocks {cell,other}, allows {wifi,vpn}: direct on wifi;
        // on cell it routes via WG when up (reachable through the tunnel),
        // and is blocked on cell when the tunnel is down.
        val p = listOf(rule(Direction.ALL, setOf(Transport.CELL, Transport.OTHER)))
        assertEquals(FlowVerdict.ALLOW_DIRECT, v(p, Direction.OUT, Transport.WIFI, true))
        assertEquals(FlowVerdict.ALLOW_VPN, v(p, Direction.OUT, Transport.CELL, true))
        assertEquals(FlowVerdict.BLOCK, v(p, Direction.OUT, Transport.CELL, false))
    }

    @Test fun incomingOnly_blocksInboundAllowsOutbound() {
        val p = listOf(rule(Direction.IN, allT))
        assertEquals(FlowVerdict.BLOCK, v(p, Direction.IN, Transport.WIFI, false))
        assertEquals(FlowVerdict.ALLOW_DIRECT, v(p, Direction.OUT, Transport.WIFI, false))
    }
}
