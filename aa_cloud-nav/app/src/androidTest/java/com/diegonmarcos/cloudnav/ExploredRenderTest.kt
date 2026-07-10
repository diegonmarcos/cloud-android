package com.diegonmarcos.cloudnav

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.MapsDemo
import com.diegonmarcos.cloudnav.maps.MapsExploredFragment
import java.io.File
import java.io.FileOutputStream
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE Explored regression test. Explored draws its city pins with a custom
 * Canvas OVERLAY (a plain Android View), not MapLibre's GeoJSON/CircleLayer
 * path — which was proven never to render on this stack (source + layer
 * registered, yet 0 features tile). A plain View can be drawn to a Bitmap even
 * on the headless emulator (map.snapshot / queryRenderedFeatures could not),
 * so this test actually observes the pins:
 *
 *   seed 228-city demo → launch → Timeline → Explored → poll until the overlay
 *   holds one pin per city → draw the overlay to a Bitmap → assert it contains
 *   many coloured (non-transparent) pixels → save it as CI evidence.
 */
@RunWith(AndroidJUnit4::class)
class ExploredRenderTest {

    @Test fun explored_overlay_draws_a_pin_per_city() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        MapsDb.get(ctx).clearAll()
        MapsDemo.resetSeedFlag(ctx)
        assertTrue("demo seed must insert rows", MapsDemo.seed(ctx) > 0)
        val expectedCities = MapsDemo.cityTemplates.size

        // Real-device condition: a last GPS fix far from every demo city.
        com.diegonmarcos.cloudnav.maps.MapsTrackerPrefs(ctx).apply {
            lastFixLat = 52.5200; lastFixLon = 13.4050; lastFixTs = System.currentTimeMillis()
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(allOf(withText("Timeline"), isDisplayed())).perform(click())
                onView(allOf(withText("Explored"), isDisplayed())).perform(click())

                fun explored(): MapsExploredFragment? = run {
                    var found: MapsExploredFragment? = null
                    scenario.onActivity { act ->
                        found = act.supportFragmentManager.fragments
                            .flatMap { it.childFragmentManager.fragments }
                            .flatMap { listOf(it) + it.childFragmentManager.fragments }
                            .filterIsInstance<MapsExploredFragment>().firstOrNull()
                    }
                    found
                }

                var pinCount = -1
                var coloured = -1
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        explored()?.let { f ->
                            pinCount = f.debugOverlayPinCount()
                            f.debugDrawOverlay()?.let { bmp -> coloured = countColoured(bmp) }
                        }
                    }
                    android.util.Log.i("MapPins", "overlayProbe pins=$pinCount coloured=$coloured (want pins>=$expectedCities, coloured>0)")
                    if (pinCount >= expectedCities && coloured > 0) break
                    Thread.sleep(1000)
                }

                // Save the overlay bitmap as evidence (survives uninstall).
                runCatching {
                    var bmp: Bitmap? = null
                    scenario.onActivity { bmp = explored()?.debugDrawOverlay() }
                    bmp?.let {
                        val dir = File(ctx.getExternalFilesDir(null), "test-screens").apply { mkdirs() }
                        val png = File(dir, "explored.png")
                        FileOutputStream(png).use { os -> it.compress(Bitmap.CompressFormat.PNG, 90, os) }
                        shell(inst, "cp ${png.absolutePath} /data/local/tmp/explored.png")
                        shell(inst, "chmod 644 /data/local/tmp/explored.png")
                    }
                }

                assertTrue(
                    "overlay must hold one pin per demo city (want >=$expectedCities, got $pinCount)",
                    pinCount >= expectedCities,
                )
                assertTrue(
                    "overlay must actually PAINT pins — many coloured pixels expected (got $coloured)",
                    coloured > 0,
                )
            }
        } finally {
            MapsDb.get(ctx).clearAll()
            MapsDemo.resetSeedFlag(ctx)
        }
    }

    /** Count non-transparent pixels — the overlay is transparent except where
     *  it paints dots, so a positive count proves pins were drawn. */
    private fun countColoured(bmp: Bitmap): Int {
        var n = 0
        val w = bmp.width; val h = bmp.height
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (bmp.getPixel(x, y) ushr 24 != 0) n++   // alpha > 0
                x += 3
            }
            y += 3
        }
        return n
    }

    private fun shell(inst: android.app.Instrumentation, cmd: String) {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
