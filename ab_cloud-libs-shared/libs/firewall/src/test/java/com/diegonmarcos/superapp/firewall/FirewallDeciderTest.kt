package com.diegonmarcos.superapp.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves the parallel-axis AppRule decision core against the user's examples. */
class FirewallDeciderTest {

    private val FG = false // isBackground = false → foreground
    private val BG = true

    @Test fun default_allowsEverything() {
        val r = AppRule()
        assertFalse(FirewallDecider.block(r, Direction.OUT, Transport.CELLULAR, BG))
        assertFalse(FirewallDecider.interimBlocked(r, Transport.CELLULAR, BG))
    }

    // "this app on wifi is all data, network no data, energy: no background data"
    @Test fun wifiOn_cellularOff_noBackground() {
        val r = AppRule(wifi = true, cellular = false, background = false)
        // Wi-Fi, foreground → allowed
        assertFalse(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))
        // Cellular → blocked (transport axis)
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.CELLULAR, FG))
        // Wi-Fi but background → blocked (background axis)
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, BG))
    }

    // VPN-only is a STRONG override: allowed only via VPN, ignoring wifi/cellular
    @Test fun vpnOnly_overridesTransports() {
        val r = AppRule(vpnMode = VpnMode.WG0_ONLY)
        assertFalse(FirewallDecider.block(r, Direction.OUT, Transport.VPN, FG))
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.CELLULAR, FG))
        // override wins even when wifi/cellular toggles would allow
        val r2 = AppRule(wifi = true, cellular = true, vpnMode = VpnMode.WG_PUBLIC_ONLY)
        assertTrue(FirewallDecider.block(r2, Direction.OUT, Transport.WIFI, FG))
        assertFalse(FirewallDecider.block(r2, Direction.OUT, Transport.VPN, FG))
    }

    // "get only data in wifi mode"
    @Test fun wifiOnly() {
        val r = AppRule(wifi = true, cellular = false)
        assertFalse(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.CELLULAR, FG))
    }

    // general axis: block all / incoming / outgoing
    @Test fun direction_blockAll() {
        val r = AppRule(direction = Direction.ALL)
        assertTrue(FirewallDecider.block(r, Direction.IN, Transport.WIFI, FG))
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))
        assertTrue(FirewallDecider.interimBlocked(r, Transport.WIFI, FG)) // ALL is enforceable interim
    }

    @Test fun direction_blockIncomingOnly() {
        val r = AppRule(direction = Direction.IN)
        assertTrue(FirewallDecider.block(r, Direction.IN, Transport.WIFI, FG))
        assertFalse(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))
        // drain-engine can't split direction → IN is a no-op interim
        assertFalse(FirewallDecider.interimBlocked(r, Transport.WIFI, FG))
    }

    @Test fun direction_blockOutgoingOnly() {
        val r = AppRule(direction = Direction.OUT)
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))
        assertFalse(FirewallDecider.block(r, Direction.IN, Transport.WIFI, FG))
    }

    // axes are a parallel SUM — any one blocking blocks the flow
    @Test fun axesCombine_anyBlocks() {
        val r = AppRule(cellular = false, background = false, direction = Direction.OUT)
        assertTrue(FirewallDecider.block(r, Direction.OUT, Transport.WIFI, FG))  // direction
        assertTrue(FirewallDecider.block(r, Direction.IN, Transport.CELLULAR, FG)) // transport
        assertTrue(FirewallDecider.block(r, Direction.IN, Transport.WIFI, BG))   // background
        assertFalse(FirewallDecider.block(r, Direction.IN, Transport.WIFI, FG))  // nothing blocks
    }

    @Test fun appRule_jsonRoundTrips() {
        val r = AppRule(wifi = false, cellular = true, background = false,
            vpnMode = VpnMode.WG_PUBLIC_ONLY, direction = Direction.IN)
        assertEquals(r, AppRule.fromJson(r.toJson()))
    }
}
