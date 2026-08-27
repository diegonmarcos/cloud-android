package com.diegonmarcos.cloudnav.sky

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the reported "constellations is crashing" bug. Launches
 * ConstellationsActivity directly (exported=false doesn't block same-package
 * instrumentation), waits for the async celestial-data load, and simulates a
 * drag gesture on SkyView (the same path used without a working rotation
 * sensor). Any crash here fails the CI job AND surfaces the real stack trace
 * in the already-uploaded logcat.txt artifact -- no physical device needed.
 */
@RunWith(AndroidJUnit4::class)
class ConstellationsRenderTest {

    @Test fun constellations_loads_data_and_survives_interaction() {
        ActivityScenario.launch(ConstellationsActivity::class.java).use { scenario ->
            var skyView: SkyView? = null
            val deadline = System.currentTimeMillis() + 30_000
            var starsLoaded = false
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { act ->
                    skyView = act.findSkyViewForTest()
                    starsLoaded = (skyView?.stars?.isNotEmpty() == true)
                }
                android.util.Log.i("ConstellationsTest", "probe starsLoaded=$starsLoaded")
                if (starsLoaded) break
                Thread.sleep(500)
            }
            assertTrue("celestial data must finish loading (stars list still empty after 30s)", starsLoaded)
            assertTrue("expected several thousand stars, got ${skyView?.stars?.size}", (skyView?.stars?.size ?: 0) > 1000)
            assertTrue("expected all 88 IAU constellations worth of line segments, got ${skyView?.constellationLines?.size}",
                (skyView?.constellationLines?.size ?: 0) > 50)
            assertTrue("expected Sun + planets, got ${skyView?.bodies?.size}", (skyView?.bodies?.size ?: 0) >= 5)

            // Simulate the no-sensor drag/pinch path (same code a real device with
            // TYPE_ROTATION_VECTOR would drive via onSensorChanged) -- must not crash.
            scenario.onActivity { act ->
                val v = act.findSkyViewForTest()
                val now = System.currentTimeMillis()
                val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0)
                val move = android.view.MotionEvent.obtain(now, now + 16, android.view.MotionEvent.ACTION_MOVE, 250f, 180f, 0)
                val up = android.view.MotionEvent.obtain(now, now + 32, android.view.MotionEvent.ACTION_UP, 250f, 180f, 0)
                v?.dispatchTouchEvent(down)
                v?.dispatchTouchEvent(move)
                v?.dispatchTouchEvent(up)
                down.recycle(); move.recycle(); up.recycle()
            }

            var finishing = false
            scenario.onActivity { act -> finishing = act.isFinishing || act.isDestroyed }
            assertFalse("activity must still be alive after data load + drag interaction (it crashed/finished)", finishing)
        }
    }
}
