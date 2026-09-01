package com.diegonmarcos.superapp

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.floatingnav.ScreensaverService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the Screensaver (build.json::ui.screensaver → BuildConfig
 * → ScreensaverService). The screensaver is a SYSTEM_ALERT_WINDOW overlay, so
 * — like the floating nav — it must refuse to start without the "display over
 * other apps" grant (the guard FloatingNavService's screensaver action relies
 * on), and stay not-running.
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class ScreensaverConfigTest {

    @Test fun configBaked() {
        assertTrue(BuildConfig.SCREENSAVER_ENABLED)
        assertTrue("time format baked", BuildConfig.SCREENSAVER_TIME_FORMAT.isNotBlank())
        assertTrue("date format baked", BuildConfig.SCREENSAVER_DATE_FORMAT.isNotBlank())
        assertTrue("unlock label baked", BuildConfig.SCREENSAVER_UNLOCK_LABEL.isNotBlank())
    }

    @Test fun startRefusesWithoutOverlayPermission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Settings.canDrawOverlays(ctx)) {
            assertFalse(ScreensaverService.start(ctx))
            assertFalse(ScreensaverService.isRunning)
        }
    }
}
