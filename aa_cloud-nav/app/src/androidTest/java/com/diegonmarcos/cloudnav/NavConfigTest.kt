package com.diegonmarcos.cloudnav

import androidx.test.ext.junit.runners.AndroidJUnit4
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
            listOf("routes", "navigation", "timeline", "places", "configs"),
            NavConfig.tabs.map { it.id },
        )
    }

    @Test fun default_tab_is_routes() {
        assertEquals("routes", NavConfig.defaultTab)
    }

    @Test fun islands_and_categories_decode() {
        assertTrue("search islands should decode", NavConfig.islands.isNotEmpty())
        assertTrue("place categories should decode", NavConfig.placeCategories.size >= 10)
        // every island/category carries a non-blank id + label (no silent nulls)
        NavConfig.islands.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
        NavConfig.placeCategories.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
    }
}
