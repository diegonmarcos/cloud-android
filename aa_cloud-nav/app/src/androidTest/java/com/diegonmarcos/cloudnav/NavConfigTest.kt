package com.diegonmarcos.cloudnav

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.cloudnav.maps.MapStyles
import com.diegonmarcos.cloudnav.maps.MapsDemo
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

    @Test fun islands_and_categories_decode() {
        assertTrue("search islands should decode", NavConfig.islands.isNotEmpty())
        assertTrue("place categories should decode", NavConfig.placeCategories.size >= 10)
        // every island/category carries a non-blank id + label (no silent nulls)
        NavConfig.islands.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
        NavConfig.placeCategories.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
    }
}
