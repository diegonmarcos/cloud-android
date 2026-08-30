package com.diegonmarcos.superapp.firewall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the Phase-3 flow verdict, incl. the cloud-VPN routing superpower. */
class FirewallFlowPolicyTest {

    private val FG = false // isBackground = false → foreground
    private val BG = true

    private fun v(rule: AppRule, dir: Direction, t: Transport, vpnUp: Boolean, bg: Boolean = FG) =
        FirewallFlowPolicy.verdict(rule, dir, t, bg, vpnUp)

    @Test fun default_allowsDirect() {
        assertEquals(FlowVerdict.ALLOW_DIRECT, v(AppRule(), Direction.OUT, Transport.CELLULAR, false))
    }

    @Test fun blockAll_blocksRegardlessOfVpn() {
        val r = AppRule(direction = Direction.ALL)
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.OUT, Transport.WIFI, true))
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.IN, Transport.CELLULAR, true))
    }

    @Test fun vpnOnly_routesViaVpnWhenUp_blocksWhenDown() {
        // "only get data when under cloud vpn" = explicit vpnMode override.
        val r = AppRule(vpnMode = VpnMode.WG0_ONLY)
        assertEquals(FlowVerdict.ALLOW_VPN, v(r, Direction.OUT, Transport.WIFI, true))
        assertEquals(FlowVerdict.ALLOW_VPN, v(r, Direction.OUT, Transport.CELLULAR, true))
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.OUT, Transport.WIFI, false))
    }

    @Test fun vpnOnly_backgroundStillBlocks() {
        // background axis applies on top of the VPN-only override
        val r = AppRule(background = false, vpnMode = VpnMode.WG_PUBLIC_ONLY)
        assertEquals(FlowVerdict.ALLOW_VPN, v(r, Direction.OUT, Transport.WIFI, true, bg = FG))
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.OUT, Transport.WIFI, true, bg = BG))
    }

    @Test fun wifiOnly_directOnWifi_blocksCell_noImplicitVpn() {
        // "wifi only" (cellular off, no vpnMode): direct on Wi-Fi, blocked on
        // cell — it does NOT silently tunnel (that needs an explicit vpnMode).
        val r = AppRule(wifi = true, cellular = false)
        assertEquals(FlowVerdict.ALLOW_DIRECT, v(r, Direction.OUT, Transport.WIFI, true))
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.OUT, Transport.CELLULAR, true))
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.OUT, Transport.CELLULAR, false))
    }

    @Test fun incomingOnly_blocksInboundAllowsOutbound() {
        val r = AppRule(direction = Direction.IN)
        assertEquals(FlowVerdict.BLOCK, v(r, Direction.IN, Transport.WIFI, false))
        assertEquals(FlowVerdict.ALLOW_DIRECT, v(r, Direction.OUT, Transport.WIFI, false))
    }
}
