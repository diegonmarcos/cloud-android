package com.diegonmarcos.cloudnav

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.cloudnav.maps.MapStyles
import com.diegonmarcos.cloudnav.maps.MapsDemo
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.cloudnav.maps.MapsRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the data-driven shell contract:
 *   build.json::ui.* → app/build.gradle (base64 BuildConfig) → [NavConfig].
 * If a tab/island/category is added in build.json it appears here with no
 * Kotlin change; this test guards the wiring (FIRE RULE #5 — every solution
 * ships its tester).
 */
@RunWith(AndroidJUnit4::class)
class NavConfigTest {

    @Test fun tabs_match_buildjson_order() {
        assertEquals(
            listOf("routes", "navigation", "places", "timeline", "configs"),
            NavConfig.tabs.map { it.id },
        )
    }

    @Test fun default_tab_is_routes() {
        assertEquals("routes", NavConfig.defaultTab)
    }

    @Test fun demo_data_is_one_day_1987_07_18() {
        assertEquals(553564800000L, MapsDemo.dayEpochMs)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        assertEquals("1987-07-18", fmt.format(java.util.Date(MapsDemo.dayEpochMs)))
        val stops = MapsDemo.stops()
        assertTrue("demo day should have several stops", stops.size >= 5)
        stops.forEach {
            assertTrue(it.startMin in 0..1440 && it.endMin in it.startMin..1440)
            assertTrue(it.place.isNotBlank() && it.city.isNotBlank())
        }
    }

    @Test fun map_switcher_cycle_decodes() {
        assertEquals(listOf("light", "dark", "satellite"), MapStyles.order)
        assertEquals("dark", MapStyles.next("light"))
        assertEquals("light", MapStyles.next("satellite"))  // wraps
    }

    @Test fun search_cache_is_configured() {
        // Data-driven TTL = 30 days (build.json::ui.search_cache.ttl_days).
        assertEquals(30, com.diegonmarcos.cloudnav.maps.BuildConfig.UI_SEARCH_CACHE_TTL_DAYS)
        // Keys normalise (trim + lowercase) so "Bars" and " bars " share a cache entry.
        assertEquals(
            MapsProviderClient.cacheKeyForward("Bars", 0.0, 0.0),
            MapsProviderClient.cacheKeyForward("  bars ", 0.0, 0.0),
        )
        assertTrue(MapsProviderClient.cacheKeyForward("bar", 1.0, 2.0).startsWith("fwd:bar@"))
        assertTrue(MapsProviderClient.cacheKeyPoi("amenity=bar", -22.97, -43.18, -22.96, -43.17).startsWith("poi:amenity=bar@"))
    }

    @Test fun travel_modes_decode() {
        val modes = MapsRouting.modes()
        assertTrue("at least 6 travel modes", modes.size >= 6)
        assertTrue(modes.map { it.id }.containsAll(
            listOf("car", "walking", "bus", "boat", "flying", "swimming")))
        assertEquals("car", MapsRouting.defaultModeId())
        // geodesic modes carry a positive avg speed; valhalla modes a costing.
        modes.forEach {
            if (it.engine == "geodesic") assertTrue(it.avgSpeedKmh > 0)
            else assertTrue(it.costing.isNotBlank())
        }
    }

    @Test fun polyline6_decodes() {
        // "?" encodes a zero delta, so "??" = one (0,0) point, "????" = two.
        val one = MapsRouting.decodePolyline6("??")
        assertEquals(1, one.size)
        assertEquals(0.0, one[0][0], 1e-9)
        assertEquals(0.0, one[0][1], 1e-9)
        assertEquals(2, MapsRouting.decodePolyline6("????").size)
    }

    @Test fun islands_and_categories_decode() {
        assertTrue("search islands should decode", NavConfig.islands.isNotEmpty())
        assertTrue("place categories should decode", NavConfig.placeCategories.size >= 10)
        // every island/category carries a non-blank id + label (no silent nulls)
        NavConfig.islands.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
        NavConfig.placeCategories.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
    }
}
