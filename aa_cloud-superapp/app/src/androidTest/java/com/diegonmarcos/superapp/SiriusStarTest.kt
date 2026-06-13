package com.diegonmarcos.superapp

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the Sirius Star (home-screen sparkle → FloatingNav menu).
 *
 * Covers the data path build.json::ui.sirius_star → BuildConfig, and the
 * force-open contract it relies on: FloatingNavService.showMenu refuses (and
 * the star toasts instead of silently failing) when "display over other apps"
 * isn't granted — the exact guard MainActivity.setupSiriusStar depends on.
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class SiriusStarTest {

    @Test fun siriusConfigBaked() {
        assertTrue("Sirius Star must be enabled by build.json::ui.sirius_star",
            BuildConfig.SIRIUS_STAR_ENABLED)
        assertTrue("Sirius glyph must be non-blank", BuildConfig.SIRIUS_STAR_GLYPH.isNotBlank())
    }

    @Test fun showMenuRefusesWithoutOverlayPermission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Settings.canDrawOverlays(ctx)) {
            assertFalse("showMenu must refuse (→ star toasts) without overlay permission",
                FloatingNavService.showMenu(ctx))
        }
    }
}
