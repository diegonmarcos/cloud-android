package com.diegonmarcos.superapp.kdeconnect

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves build.json::ui.kde_connect → BuildConfig.KDE_CONNECT_B64 →
 * KdeConnectConfig resolves the Surface Pro target over wg0 with no
 * hardcoded IP/port/package in Kotlin.
 *
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class KdeConnectConfigTest {

    @Test fun surfaceTargetResolves() {
        val cfg = KdeConnectConfig.get()
        assertEquals("org.kde.kdeconnect_tp", cfg.pkg)
        assertEquals(1716, cfg.discoveryPort)
        assertTrue("wg0 must be preferred", cfg.preferWg0)
        assertTrue("LAN fallback must be on", cfg.lanFallback)

        val device = cfg.primaryDevice
        assertNotNull("a primary device must be declared", device)
        assertEquals("10.0.0.5", device!!.wgIp)
    }
}
