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
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import java.io.File
import java.io.FileOutputStream
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE Explored regression test — runs on a real emulator in CI
 * (test-cloud-nav.yml) and proves, end to end, what four rounds of static
 * review could not: that the Explored tab's city pins are (a) plumbed into
 * the map's GeoJSON source and (b) ACTUALLY RENDERED by the GL layer on
 * screen. It also drops a screenshot into the app's external files dir
 * (copied to /data/local/tmp so it survives the post-test uninstall) for
 * human inspection as a CI artifact.
 *
 * Flow: wipe DB + seed the full 1987-1992 demo trip → launch MainActivity →
 * tap "Timeline" (bottom nav) → tap "Explored" (tab) → poll
 * [MapsMapFragment.pinProbe] until the pins source holds one feature per
 * demo city AND at least one pin is rendered in the viewport (vector-style
 * fetch + GL warm-up are async, so poll with a generous deadline).
 */
@RunWith(AndroidJUnit4::class)
class ExploredRenderTest {

    @Test fun explored_renders_one_pin_per_city_from_daily_data() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        // Clean slate → full demo seed (same path as the Configs button).
        MapsDb.get(ctx).clearAll()
        MapsDemo.resetSeedFlag(ctx)
        assertTrue("demo seed must insert rows", MapsDemo.seed(ctx) > 0)
        val expectedCities = MapsDemo.cityTemplates.size

        // Simulate a REAL user's device: the tracker has a last GPS fix
        // (Berlin — deliberately in none of the demo cities). Without the
        // worldView camera fix, the map opens zoomed ~14 into this fix and
        // every pin is off-screen on another continent — the exact
        // "empty map, no pins" report that a fresh no-fix emulator never
        // reproduced (its world-zoom default made the old code look fine).
        com.diegonmarcos.cloudnav.maps.MapsTrackerPrefs(ctx).apply {
            lastFixLat = 52.5200
            lastFixLon = 13.4050
            lastFixTs = System.currentTimeMillis()
        }

        try {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The bottom-nav item renders TWO "Timeline" TextViews (a visible
            // small-label + an INVISIBLE large-label), so a bare withText is
            // ambiguous — match only the displayed one. Same for the tab.
            onView(allOf(withText("Timeline"), isDisplayed())).perform(click())
            onView(allOf(withText("Explored"), isDisplayed())).perform(click())

            var src = -1
            var rendered = -1
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { act ->
                    runCatching {
                        val mapFrag = act.supportFragmentManager.fragments
                            .flatMap { it.childFragmentManager.fragments }
                            .flatMap { listOf(it) + it.childFragmentManager.fragments }
                            .filterIsInstance<MapsMapFragment>()
                            .firstOrNull()
                        if (mapFrag != null) {
                            val p = mapFrag.pinProbe()
                            src = p.first; rendered = p.second
                        }
                    }
                }
                android.util.Log.i("ExploredRenderTest", "probe src=$src rendered=$rendered (want src>=$expectedCities, rendered>0)")
                if (src >= expectedCities && rendered > 0) break
                Thread.sleep(1000)
            }

            // Evidence PNG FIRST (so the artifact exists even if asserts fail).
            // Use MapLibre's own map.snapshot() — it renders the GL scene (pins
            // included) offscreen, which works on the headless -no-window CI
            // emulator where UiAutomation.takeScreenshot() returns null.
            runCatching {
                val latch = java.util.concurrent.CountDownLatch(1)
                var shot: Bitmap? = null
                scenario.onActivity { act ->
                    val mapFrag = act.supportFragmentManager.fragments
                        .flatMap { it.childFragmentManager.fragments }
                        .flatMap { listOf(it) + it.childFragmentManager.fragments }
                        .filterIsInstance<MapsMapFragment>().firstOrNull()
                    if (mapFrag != null) mapFrag.snapshot { bmp -> shot = bmp; latch.countDown() }
                    else latch.countDown()
                }
                latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
                val bmp = shot
                if (bmp != null) {
                    // App uid can only write its own dir; write there, then use
                    // the UiAutomation shell (shell uid) to copy it to
                    // /data/local/tmp, which survives the AGP post-test uninstall.
                    val dir = File(ctx.getExternalFilesDir(null), "test-screens").apply { mkdirs() }
                    val png = File(dir, "explored.png")
                    FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    shell(inst, "cp ${png.absolutePath} /data/local/tmp/explored.png")
                    shell(inst, "chmod 644 /data/local/tmp/explored.png")
                }
            }

            assertTrue(
                "Explored pins source must contain one feature per demo city " +
                    "(want >=$expectedCities, got $src) — pins never reached the style source",
                src >= expectedCities,
            )
            assertTrue(
                "at least one Explored pin must be RENDERED in the viewport " +
                    "(got $rendered) — pins are in the source but the GL layer drew nothing",
                rendered > 0,
            )
        }
        } finally {
            // Never leave the 2192-row demo seed behind — it pollutes the
            // other DB-touching instrumented tests (class E runs before N).
            MapsDb.get(ctx).clearAll()
            MapsDemo.resetSeedFlag(ctx)
        }
    }

    /** Run a shell command via UiAutomation and drain its output (the command
     *  only completes reliably once the returned fd is fully read + closed). */
    private fun shell(inst: android.app.Instrumentation, cmd: String) {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
