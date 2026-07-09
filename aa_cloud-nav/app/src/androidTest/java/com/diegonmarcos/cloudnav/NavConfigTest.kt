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

    @Test fun demo_data_is_rich_fixed_historical_1987_1992_with_revisits() {
        val days = MapsDemo.demoDays
        assertTrue("demo trip should be VERY rich — many days", days.size >= 14)
        // Fixed historical dates (1987-01-01..1992-12-31), NEVER relative to
        // "today" — this is the whole point: it can't collide with real data.
        days.forEach { d ->
            assertTrue("every date must be ISO yyyy-MM-dd", d.dateIso.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
            val year = d.dateIso.substring(0, 4).toInt()
            assertTrue("$year must fall in 1987..1992", year in 1987..1992)
        }
        val allStops = days.flatMap { it.stops }
        assertTrue("demo trip should have many stops total", allStops.size >= 35)
        allStops.forEach {
            assertTrue(it.startMin in 0..1440 && it.endMin in it.startMin..1440)
            assertTrue(it.place.isNotBlank() && it.city.isNotBlank())
        }
        // Richness: multiple continents' worth of countries, not just one region.
        val countries = allStops.map { it.country }.toSet()
        assertTrue("demo trip should span many countries", countries.size >= 7)

        // Revisits: FIVE distinct cities each appear on 2+ DISTINCT demo days —
        // real material for the Explored tab's visit-history feature.
        val cityToDistinctDays = days.flatMap { d -> d.stops.map { it.city }.toSet().map { city -> city to d.dateIso } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, dates) -> dates.toSet().size }
        assertTrue("Rio de Janeiro must be revisited 3x", (cityToDistinctDays["Rio de Janeiro"] ?: 0) >= 3)
        listOf("São Paulo", "Buenos Aires", "Lisbon", "Paris").forEach { city ->
            assertTrue("$city must be revisited 2x", (cityToDistinctDays[city] ?: 0) >= 2)
        }
        assertTrue("at least 5 distinct cities must be revisited", cityToDistinctDays.values.count { it >= 2 } >= 5)
    }

    @Test fun demo_dates_parse_to_the_correct_fixed_utc_midnight() {
        // Pure-function check (no DB, no clock dependency) — proves dates are
        // resolved to a fixed instant, not "N days before whenever this runs".
        val ms = MapsDemo.utcMidnightOf("1987-07-18")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        assertEquals("1987-07-18", fmt.format(java.util.Date(ms)))
        assertTrue("must be decades before now, not near 'today'", System.currentTimeMillis() - ms > 30L * 365 * 24 * 3600_000L)
    }

    @Test fun explored_groups_by_city_from_daily_entries_not_raw_stops() {
        fun entry(dayMs: Long, city: String?, place: String?, lat: Double, lon: Double) =
            com.diegonmarcos.cloudnav.maps.MapsDailyFragment.DailyEntry(dayMs, place, null, city, "Brazil", lat, lon)

        // Rio appears on two DIFFERENT days (a revisit — what Explored exists to
        // show); São Paulo appears once. Sourced from Daily's one-pick-per-day
        // output, not raw Stops, so a city with many Stops in one day still
        // collapses to a single visit entry for that day.
        val daily = listOf(
            entry(1000L, "Rio de Janeiro", "Home — Copacabana", -22.9711, -43.1822),
            entry(2000L, "Rio de Janeiro", "Ipanema Beach", -22.9869, -43.2045),
            entry(3000L, "São Paulo", "Hotel — Jardins", -23.5629, -46.6544),
        )
        val cities = com.diegonmarcos.cloudnav.maps.MapsExploredFragment.groupByCity(daily)
        assertEquals("two distinct cities", 2, cities.size)

        val rio = cities.first { it.city == "Rio de Janeiro" }
        assertEquals("revisited on 2 distinct days", 2, rio.visits.size)
        assertTrue(rio.visits.map { it.dayMs }.toSet() == setOf(1000L, 2000L))

        val sp = cities.first { it.city == "São Paulo" }
        assertEquals(1, sp.visits.size)
    }

    @Test fun explored_drops_entries_with_no_resolved_city() {
        fun entry(dayMs: Long, city: String?) =
            com.diegonmarcos.cloudnav.maps.MapsDailyFragment.DailyEntry(dayMs, null, null, city, null, 0.0, 0.0)

        // An unresolved (still-enriching) daily entry has nothing meaningful to
        // pin or title — must not produce a bogus/blank pin.
        val daily = listOf(entry(1000L, null), entry(2000L, "Rio de Janeiro"))
        val cities = com.diegonmarcos.cloudnav.maps.MapsExploredFragment.groupByCity(daily)
        assertEquals(1, cities.size)
        assertEquals("Rio de Janeiro", cities.first().city)
    }

    @Test fun map_switcher_cycle_decodes() {
        assertEquals(
            listOf("light", "dark", "satellite", "vector_light", "vector_dark", "satellite_hybrid"),
            MapStyles.order,
        )
        assertEquals("dark", MapStyles.next("light"))
        assertEquals("light", MapStyles.next("satellite_hybrid"))  // wraps
    }

    @Test fun vector_styles_are_data_driven_and_localized_to_english() {
        val vl = MapStyles.get("vector_light")
        val vd = MapStyles.get("vector_dark")
        assertTrue("vector_light carries a real GL style URL", vl.vectorStyleUrl?.startsWith("https://") == true)
        assertTrue("vector_dark carries a real GL style URL", vd.vectorStyleUrl?.startsWith("https://") == true)
        assertEquals("name_en", vl.labelFieldPref)
        assertEquals("light", vl.rasterFallback)
        assertEquals("dark", vd.rasterFallback)
        // Raster styles must NOT declare a vector URL (branch selector in MapsMapFragment.applyStyle).
        assertTrue(MapStyles.get("light").vectorStyleUrl == null)
        assertTrue(MapStyles.get("dark").vectorStyleUrl == null)
        assertTrue(MapStyles.get("satellite").vectorStyleUrl == null)
    }

    @Test fun satellite_hybrid_is_data_driven() {
        val h = MapStyles.get("satellite_hybrid")
        assertTrue("hybrid flag set", h.hybrid)
        assertEquals("satellite", h.baseStyleKey)
        assertEquals("satellite", h.rasterFallback)
        assertTrue("hybrid carries a vector overlay URL", h.vectorOverlayUrl?.startsWith("https://") == true)
        // Not a plain vector style (that branch is for vector_light/vector_dark only).
        assertTrue(h.vectorStyleUrl == null)
    }

    @Test fun raster_styles_point_at_their_vector_equivalent() {
        assertEquals("vector_light", MapStyles.get("light").vectorEquivalent)
        assertEquals("vector_dark", MapStyles.get("dark").vectorEquivalent)
        assertEquals("satellite_hybrid", MapStyles.get("satellite").vectorEquivalent)
    }

    @Test fun resolve_maps_raster_to_vector_only_when_preferred() {
        assertEquals("light", MapStyles.resolve("light", preferVector = false))
        assertEquals("vector_light", MapStyles.resolve("light", preferVector = true))
        assertEquals("satellite_hybrid", MapStyles.resolve("satellite", preferVector = true))
        // A style with no equivalent (shouldn't happen for our 3 raster keys, but the
        // function must degrade to identity rather than crash) still resolves to itself.
        assertEquals("vector_light", MapStyles.resolve("vector_light", preferVector = true))
    }

    @Test fun hybrid_composite_keeps_only_roads_and_name_labels() {
        // Fixture mirrors OpenFreeMap "positron"'s real shape (verified live):
        // background + fill (landuse/water) + line (roads) + symbol (labels, some
        // icon-only). Hybrid must keep line + name-bearing symbol only.
        val fixture = """
            { "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
              "sources": {
                "ne2_shaded": { "type": "raster", "tiles": ["https://example/{z}/{x}/{y}.png"] },
                "openmaptiles": { "type": "vector", "url": "https://example/tiles.json" }
              },
              "layers": [
                { "id": "background", "type": "background", "source": null },
                { "id": "hillshade", "type": "raster", "source": "ne2_shaded" },
                { "id": "landuse", "type": "fill", "source": "openmaptiles" },
                { "id": "road", "type": "line", "source": "openmaptiles", "paint": { "line-color": "#999" } },
                { "id": "poi-icon", "type": "symbol", "source": "openmaptiles", "layout": { "icon-image": "bar-11" } },
                { "id": "city-label", "type": "symbol", "source": "openmaptiles", "layout":
                    { "text-field": ["coalesce", ["get","name_en"], ["get","name"]] } }
              ] }
        """.trimIndent()
        val base = com.diegonmarcos.cloudnav.maps.MapStyles.Style(
            key = "satellite", label = "Satellite",
            tiles = "https://esri/{z}/{y}/{x}", attribution = "© Esri", background = "#000", maxzoom = 19,
            glyphs = "unused",
        )
        val out = org.json.JSONObject(
            com.diegonmarcos.cloudnav.maps.VectorStyleLoader.buildHybridFromJson(fixture, base, "name_en"),
        )
        val sourceIds = out.getJSONObject("sources").keys().asSequence().toSet()
        assertTrue("raster imagery source present", sourceIds.contains("raster"))
        assertTrue("vector source carried over", sourceIds.contains("openmaptiles"))
        assertTrue("hillshade raster source dropped", !sourceIds.contains("ne2_shaded"))

        val layerIds = (0 until out.getJSONArray("layers").length())
            .map { out.getJSONArray("layers").getJSONObject(it).getString("id") }
        assertTrue("base imagery layer present", layerIds.contains("hybrid-raster"))
        assertTrue("road line kept", layerIds.contains("road"))
        assertTrue("name label kept", layerIds.contains("city-label"))
        assertTrue("background dropped", !layerIds.contains("background"))
        assertTrue("hillshade raster layer dropped", !layerIds.contains("hillshade"))
        assertTrue("fill polygon dropped", !layerIds.contains("landuse"))
        assertTrue("icon-only symbol dropped (labels only)", !layerIds.contains("poi-icon"))

        val roadLayer = (0 until out.getJSONArray("layers").length())
            .map { out.getJSONArray("layers").getJSONObject(it) }.first { it.getString("id") == "road" }
        assertEquals("#FFFFFF", roadLayer.getJSONObject("paint").getString("line-color"))
        val labelLayer = (0 until out.getJSONArray("layers").length())
            .map { out.getJSONArray("layers").getJSONObject(it) }.first { it.getString("id") == "city-label" }
        assertTrue("label re-localized to name_en-first", labelLayer.getJSONObject("layout").get("text-field").toString().contains("name_en"))
        assertEquals("#FFFFFF", labelLayer.getJSONObject("paint").getString("text-color"))
    }

    @Test fun vector_style_localizer_prefers_english_and_skips_ref_shields() {
        // Fixture mirrors OpenFreeMap "liberty"'s actual shape (verified live):
        // name labels are case/coalesce expressions on name/name:latin/name:nonlatin/name_en;
        // road shields render `ref` and must NOT be touched.
        val fixture = """
            { "layers": [
                { "id": "label_city", "type": "symbol", "layout": { "text-field":
                    ["case", ["has","name:nonlatin"], ["concat", ["get","name:latin"], "\n", ["get","name:nonlatin"]],
                     ["coalesce", ["get","name_en"], ["get","name"]]] } },
                { "id": "highway-shield-non-us", "type": "symbol", "layout": { "text-field": ["to-string", ["get","ref"]] } }
            ] }
        """.trimIndent()
        val out = org.json.JSONObject(
            com.diegonmarcos.cloudnav.maps.VectorStyleLoader.localize(fixture, "name_en"),
        )
        val cityField = out.getJSONArray("layers").getJSONObject(0).getJSONObject("layout").get("text-field").toString()
        assertTrue("rewritten to a name_en-first coalesce", cityField.contains("name_en") && cityField.startsWith("[\"coalesce\""))
        assertTrue("no longer branches on nonlatin script", !cityField.contains("nonlatin"))
        val shieldField = out.getJSONArray("layers").getJSONObject(1).getJSONObject("layout").get("text-field").toString()
        assertEquals("ref shield untouched", """["to-string",["get","ref"]]""", shieldField)
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
        assertEquals("walking", modes.first().id)            // Walk is first
        assertEquals("walking", MapsRouting.defaultModeId())  // and the default
        // geodesic modes carry a positive avg speed; valhalla modes a costing.
        modes.forEach {
            if (it.engine == "geodesic") assertTrue(it.avgSpeedKmh > 0)
            else assertTrue(it.costing.isNotBlank())
        }
    }

    @Test fun cockpit_modes_decode() {
        val m = NavConfig.cockpitModes
        assertTrue("at least the 4 vehicle modes", m.size >= 4)
        assertTrue(m.map { it.id }.containsAll(listOf("bike", "car", "boat", "airplane")))
        // default belongs to the decoded set (no dangling default_cockpit_mode).
        assertTrue(m.any { it.id == NavConfig.defaultCockpitMode })
        m.forEach {
            assertTrue("${it.id} has gauges", it.gauges.isNotEmpty())
            assertTrue("${it.id} accent parsed (opaque)", it.accent != 0)
            assertTrue("${it.id} speed unit", it.speedUnit in listOf("kmh", "kn", "mph"))
        }
        // Airplane is the PFD profile: attitude + altitude instruments, feet.
        val air = m.first { it.id == "airplane" }
        assertTrue(air.gauges.containsAll(listOf("attitude", "altitude")))
        assertEquals("ft", air.altUnit)
    }

    @Test fun cockpit_unit_conversions() {
        // knots ≈ km/h ÷ 1.852; feet ≈ m × 3.28084 (the gauge feeders).
        assertEquals(100.0 / 1.852, com.diegonmarcos.cloudnav.cockpit.CockpitGauges.kmhTo("kn", 100.0), 1e-6)
        assertEquals(100.0, com.diegonmarcos.cloudnav.cockpit.CockpitGauges.kmhTo("kmh", 100.0), 1e-9)
        assertEquals(328.084, com.diegonmarcos.cloudnav.cockpit.CockpitGauges.metersTo("ft", 100.0), 1e-3)
    }

    @Test fun basemap_pref_defaults_to_auto_vector_and_family_is_toggleable() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        // Isolate from any state a previous test run left behind.
        ctx.getSharedPreferences("maps_basemap_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = com.diegonmarcos.cloudnav.maps.MapsBasemapPrefs(ctx)
        assertEquals("vector", com.diegonmarcos.cloudnav.maps.MapStyles.defaultFamily)
        assertTrue("first-run: no pin, family follows build.json (vector)", prefs.explicitStyleKey == null && prefs.preferVectorFamily)
        // Auto resolves each screen's own raster pick through its vector equivalent.
        assertEquals("vector_dark", prefs.resolve("dark"))
        prefs.preferVectorFamily = false
        assertEquals("dark", com.diegonmarcos.cloudnav.maps.MapsBasemapPrefs(ctx).resolve("dark"))
        prefs.preferVectorFamily = true  // restore, so this test is order-independent
    }

    @Test fun basemap_explicit_pin_overrides_every_screen() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("maps_basemap_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = com.diegonmarcos.cloudnav.maps.MapsBasemapPrefs(ctx)
        prefs.explicitStyleKey = "satellite_hybrid"
        // A pin wins regardless of each screen's own raw request.
        assertEquals("satellite_hybrid", prefs.resolve("light"))
        assertEquals("satellite_hybrid", prefs.resolve("dark"))
        assertEquals("satellite_hybrid", prefs.resolve("satellite"))
        prefs.explicitStyleKey = null  // restore
    }

    @Test fun polyline6_decodes() {
        // "?" encodes a zero delta, so "??" = one (0,0) point, "????" = two.
        val one = MapsRouting.decodePolyline6("??")
        assertEquals(1, one.size)
        assertEquals(0.0, one[0][0], 1e-9)
        assertEquals(0.0, one[0][1], 1e-9)
        assertEquals(2, MapsRouting.decodePolyline6("????").size)
    }

    @Test fun search_scopes_decode_default_city() {
        val s = NavConfig.searchScopes
        assertEquals(listOf("city", "country", "world"), s.map { it.id })
        assertEquals("city", NavConfig.defaultScope)
        assertTrue("city scope is bounded", s.first { it.id == "city" }.radiusKm > 0)
        assertEquals(0.0, s.first { it.id == "world" }.radiusKm, 1e-9)  // world = unbounded
    }

    @Test fun poi_detail_fields_decode() {
        val f = NavConfig.poiDetailFields
        assertTrue("detail fields decode", f.isNotEmpty())
        // website + phone rows must exist (the actionable ones) with the right kind.
        assertEquals("url", f.first { it.tag == "website" }.kind)
        assertEquals("phone", f.first { it.tag == "phone" }.kind)
        // website carries fallback tags (contact:website / url) so odd OSM data still resolves.
        assertTrue(f.first { it.tag == "website" }.alt.contains("contact:website"))
        f.forEach {
            assertTrue(it.tag.isNotBlank() && it.label.isNotBlank())
            assertTrue(it.kind in listOf("text", "url", "phone", "email"))
        }
    }

    @Test fun search_hit_carries_raw_tags() {
        // The POI pipeline must preserve the full OSM tag set for the detail sheet.
        val hit = MapsProviderClient.SearchHit(
            "Bar X", null, 1.0, 2.0, "bar",
            tags = mapOf("website" to "https://x.test", "phone" to "+1"),
        )
        assertEquals("https://x.test", hit.tags["website"])
        assertEquals(2, hit.tags.size)
    }

    @Test fun place_categories_have_emoji() {
        assertTrue(NavConfig.placeCategories.all { it.emoji.isNotBlank() })
    }

    @Test fun islands_and_categories_decode() {
        assertTrue("search islands should decode", NavConfig.islands.isNotEmpty())
        assertTrue("place categories should decode", NavConfig.placeCategories.size >= 10)
        // every island/category carries a non-blank id + label (no silent nulls)
        NavConfig.islands.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
        NavConfig.placeCategories.forEach { assertTrue(it.id.isNotBlank() && it.label.isNotBlank()) }
    }
}
