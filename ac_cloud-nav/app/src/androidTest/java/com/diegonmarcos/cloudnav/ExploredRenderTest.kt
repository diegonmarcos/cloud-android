package com.diegonmarcos.cloudnav

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
import com.diegonmarcos.cloudnav.maps.MapsGeoLayers
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE Explored regression test. Explored now renders pins as a NATIVE MapLibre
 * CircleLayer (typed FeatureCollection — proven to register on this stack once
 * the string-geojson bug was fixed), and paints visited areas with FillLayers.
 * Both are GL layers, so this test observes them via the live SOURCE feature
 * counts (querySourceFeatures), the exact signal that string-geojson pins failed
 * (0 features):
 *
 *   seed 228-city demo → Timeline → Explored → poll until:
 *     • the native pin SOURCE reports > 0 features (pins render, no overlay), and
 *     • the Countries fill SOURCE reports > 0 features (choropleth renders), and
 *     • switching to PLACES yields far MORE pins than CITY (684 > 228).
 */
@RunWith(AndroidJUnit4::class)
class ExploredRenderTest {

    @Test fun explored_renders_native_pins_and_visited_fills() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        MapsDb.get(ctx).clearAll()
        MapsDemo.resetSeedFlag(ctx)
        assertTrue("demo seed must insert rows", MapsDemo.seed(ctx) > 0)
        val expectedCities = MapsDemo.cityTemplates.size

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

                // ── native pins + Countries fill both register on the GL stack ──
                var pinSource = -1
                var fillSource = -1
                var deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        explored()?.let { f ->
                            pinSource = f.debugPinSourceCount()
                            fillSource = f.debugFillSourceCount(MapsGeoLayers.Layer.COUNTRIES)
                        }
                    }
                    android.util.Log.i("MapPins", "probe pinSource=$pinSource fillSource=$fillSource")
                    if (pinSource > 0 && fillSource > 0) break
                    Thread.sleep(1000)
                }
                assertTrue("native pin SOURCE must report >0 features (got $pinSource)", pinSource > 0)
                assertTrue("Countries fill SOURCE must report >0 features (got $fillSource)", fillSource > 0)

                // ── PLACES mode must render FAR more pins than CITY (684 > 228) ──
                var cityCount = -1
                var placeCount = -1
                var placePinSource = -1
                scenario.onActivity {
                    explored()?.let { f ->
                        f.debugSetMode(MapsExploredFragment.Mode.CITY); cityCount = f.debugPinModelCount()
                        f.debugSetMode(MapsExploredFragment.Mode.PLACES); placeCount = f.debugPinModelCount()
                    }
                }
                deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity { explored()?.let { placePinSource = it.debugPinSourceCount() } }
                    android.util.Log.i("MapPins", "placesProbe city=$cityCount places=$placeCount pinSource=$placePinSource")
                    if (placePinSource > cityCount) break
                    Thread.sleep(1000)
                }
                assertTrue("PLACES ($placeCount) must be many more than CITY ($cityCount)", placeCount > cityCount * 2)
                assertTrue("native pin source in PLACES must exceed city count (got $placePinSource)", placePinSource > cityCount)

                // Timeline map defaults to vector-light (style "light").
                var mapStyle: String? = null
                scenario.onActivity {
                    mapStyle = explored()?.childFragmentManager?.fragments
                        ?.filterIsInstance<com.diegonmarcos.cloudnav.maps.MapsMapFragment>()
                        ?.firstOrNull()?.arguments?.getString("style")
                }
                assertTrue("Timeline map must default to light (got $mapStyle)", mapStyle == "light")
            }
        } finally {
            MapsDb.get(ctx).clearAll()
            MapsDemo.resetSeedFlag(ctx)
        }
    }
}
