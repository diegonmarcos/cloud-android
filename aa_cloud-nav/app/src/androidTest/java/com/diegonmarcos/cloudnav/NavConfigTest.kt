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

    @Test fun demo_range_covers_every_day_1987_to_1992() {
        // FULL daily coverage, not a sparse sample: totalDays must span the
        // entire fixed historical range (1987-01-01..1992-12-31 inclusive,
        // across 2 leap years = 2192 days), never relative to "today".
        assertEquals("1987-01-01", MapsDemo.rangeStartIso)
        assertEquals("1992-12-31", MapsDemo.rangeEndIso)
        assertEquals(2192, MapsDemo.totalDays)
    }

    @Test fun demo_city_templates_are_rich_and_span_continents() {
        // Per direct request: at LEAST 200 distinct cities (generated from the
        // real travel-data.json city set — 228 cities / 38 countries).
        val cities = MapsDemo.cityTemplates
        assertTrue("at least 200 distinct cities (got ${cities.size})", cities.size >= 200)
        val countries = cities.map { it.country }.toSet()
        assertTrue("dozens of countries (got ${countries.size})", countries.size >= 30)
        cities.forEach { c ->
            // >=2 stops/day so PLACES mode renders more pins than CITY, and the
            // stops are DISTINCT places (not the same point repeated).
            assertTrue("${c.city} needs >=2 stops/day (got ${c.stops.size})", c.stops.size >= 2)
            assertTrue("${c.city} stops must be distinct places",
                c.stops.map { it.place }.toSet().size == c.stops.size)
            c.stops.forEach { s ->
                assertTrue(s.startMin in 0..1440 && s.endMin in s.startMin..1440)
                assertTrue(s.place.isNotBlank())
            }
        }
        // No duplicate city entries (each city = exactly one template).
        assertEquals("city names unique", cities.size, cities.map { it.city }.toSet().size)
    }

    @Test fun demo_places_are_spread_far_enough_to_render_as_distinct_pins() {
        // The bug: PLACES mode built 684 pins but they LOOKED like the 228 city
        // pins, because the 3 places/city were offset by only ~0.005-0.009°
        // (~0.7 km) — sub-pixel at Explored's world/region framing, so all 3
        // collapsed onto their city. Guard that every city's places fan out by a
        // visible margin (max pairwise separation ≳ 0.1°, ~11 km) so PLACES is
        // actually denser than CITY on screen, not just in the list.
        MapsDemo.cityTemplates.forEach { c ->
            var maxSep = 0.0
            for (i in c.stops.indices) for (j in i + 1 until c.stops.size) {
                val a = c.stops[i]; val b = c.stops[j]
                maxSep = maxOf(maxSep, kotlin.math.hypot(a.lat - b.lat, a.lon - b.lon))
            }
            assertTrue("${c.city} places must spread visibly (got maxSep=$maxSep°)", maxSep >= 0.1)
        }
    }

    @Test fun geo_country_name_normalizes_with_aliases() {
        // Tolerant match between the traveller's stored names and Natural Earth's
        // NAME field, so visited-country fills actually hit their polygons.
        assertEquals("united states of america", com.diegonmarcos.cloudnav.maps.MapsGeoLayers.normalize("United States"))
        assertEquals("united states of america", com.diegonmarcos.cloudnav.maps.MapsGeoLayers.normalize("  the USA "))
        assertEquals("czechia", com.diegonmarcos.cloudnav.maps.MapsGeoLayers.normalize("Czech Republic"))
        assertEquals("france", com.diegonmarcos.cloudnav.maps.MapsGeoLayers.normalize("France"))
    }

    @Test fun geo_visited_subregions_from_visited_iso() {
        // Political-Cultural region is "visited" iff any member country is visited.
        val members = listOf(
            com.diegonmarcos.cloudnav.maps.MapsGeoLayers.RegionMembers("Norse-Scandinavian", setOf("SE", "NO", "DK", "IS")),
            com.diegonmarcos.cloudnav.maps.MapsGeoLayers.RegionMembers("Iberians", setOf("ES", "PT")),
        )
        assertEquals(
            setOf("Norse-Scandinavian"),
            com.diegonmarcos.cloudnav.maps.MapsGeoLayers.visitedSubregionsFor(members, setOf("NO", "JP")),
        )
        assertTrue(com.diegonmarcos.cloudnav.maps.MapsGeoLayers.visitedSubregionsFor(members, setOf("JP")).isEmpty())
    }

    @Test fun geo_assets_are_valid_and_present() {
        // The bundled choropleth assets exist, parse, and carry the keyed props
        // the manager filters on (guards the tools/geo pipeline output). Parsed
        // with org.json (the MapLibre geojson types are internal to :libs:maps).
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        fun feats(name: String) = org.json.JSONObject(
            ctx.assets.open(name).bufferedReader().use { it.readText() }).getJSONArray("features")
        val countries = feats("geo/countries.geojson")
        assertTrue("countries has features", countries.length() > 100)
        assertTrue("country carries NAME",
            countries.getJSONObject(0).getJSONObject("properties").has("NAME"))
        assertTrue("continents present", feats("geo/continents.geojson").length() in 5..7)
        assertTrue("regions present", feats("geo/regions.geojson").length() > 20)
        assertTrue("nomad present", feats("geo/nomad.geojson").length() > 0)
    }

    @Test fun explored_pin_radius_scales_with_zoom() {
        // Pins shrink when zoomed out (world) so 200+ don't overflow into a blob,
        // and grow when zoomed in — monotonic, clamped both ends.
        val f = com.diegonmarcos.cloudnav.maps.MapsExploredFragment.Companion
        val world = f.pinRadiusDp(1.0)   // below ZOOM_MIN → clamped small
        val country = f.pinRadiusDp(6.0) // ZOOM_MAX → clamped large
        val street = f.pinRadiusDp(15.0) // above band → still clamped large
        assertTrue("world pins are small (got $world)", world <= 3.5f)
        assertTrue("country pins are large (got $country)", country >= 8.5f)
        assertEquals("clamped above the band", country, street)
        assertTrue("monotonic across the ramp", f.pinRadiusDp(3.0) < f.pinRadiusDp(5.0))
    }

    @Test fun demo_generation_gives_every_day_a_city_and_revisits_all_of_them() {
        // Pure — the whole generation algorithm (cityForDayIndex), no DB. Every
        // one of the 2192 days must resolve to SOME city (full coverage), and
        // the cycle wraps past the whole 228-city list at least twice, so EVERY
        // city is a multi-visit city (the Explored revisit material) — per
        // direct request: "double visits in many cities".
        val days = MapsDemo.totalDays
        val stay = MapsDemo.stayDays
        val cities = MapsDemo.cityTemplates
        assertTrue("stay_days must be positive", stay >= 1)
        val daysPerCity = HashMap<String, Int>()
        for (i in 0 until days) {
            val c = MapsDemo.cityForDayIndex(i)
            assertTrue("every day resolves to a real city", cities.any { it.city == c.city })
            daysPerCity[c.city] = (daysPerCity[c.city] ?: 0) + 1
        }
        assertEquals("every city gets covered", cities.size, daysPerCity.size)
        // days/stay = 548 stays over 228 cities → every city has ≥2 separate
        // multi-day stays (≥ 2×stay days), many have 3.
        assertTrue("EVERY city is visited at least twice (2 separate stays)",
            daysPerCity.values.all { it >= 2 * stay })
        assertTrue("many cities get a third visit",
            daysPerCity.values.count { it >= 3 * stay } >= 50)
    }

    @Test fun explored_country_colors_are_deterministic_and_distinct() {
        val f = com.diegonmarcos.cloudnav.maps.MapsExploredFragment.Companion
        // Deterministic: same country → same colour, every call.
        assertEquals(f.countryColor("Brazil"), f.countryColor("Brazil"))
        // Valid hex.
        listOf("Brazil", "France", "Japan", "Argentina").forEach { c ->
            assertTrue("hex colour for $c", f.countryColor(c).matches(Regex("#[0-9A-F]{6}")))
        }
        // Different countries → different colours (for these real demo countries).
        val cols = listOf("Brazil", "France", "Japan", "Argentina", "Portugal", "United Kingdom")
            .map { f.countryColor(it) }
        assertEquals("no colour collisions among core demo countries", cols.size, cols.toSet().size)
        // Null/blank → the neutral place colour, never a crash.
        assertEquals(com.diegonmarcos.cloudnav.maps.MapsMapFragment.COLOR_PLACE, f.countryColor(null))
        assertEquals(com.diegonmarcos.cloudnav.maps.MapsMapFragment.COLOR_PLACE, f.countryColor("  "))
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

    @Test fun demo_seed_reseeds_after_a_stale_flag_or_a_reset() {
        // Regression test for a real bug: this demo dataset's SHAPE changed
        // three times (relative-to-today -> 14 fixed days -> full ~2192-day
        // generator) while reusing the same bare "seeded=true" flag. Any
        // earlier tap of "Load demo data" left that flag permanently set, so
        // re-tapping on a build with the corrected/expanded generator silently
        // no-op'd forever — the DB kept whatever partial/stale dataset an
        // older build had inserted. Fixed by keying the flag to a signature of
        // the ACTUAL dataset content, so a changed dataset always reseeds.
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val db = com.diegonmarcos.cloudnav.maps.MapsDb.get(ctx)
        val prefs = ctx.getSharedPreferences("maps_demo_prefs", android.content.Context.MODE_PRIVATE)
        // Isolate from any rows another instrumented test left behind (e.g.
        // ExploredRenderTest's seed) — start from a known-empty DB.
        db.clearAll(); prefs.edit().clear().commit()
        try {
            // Simulate a stale flag left by an OLDER build's different dataset shape.
            prefs.edit().putString("seeded_signature", "some-old-build-left-this-behind").commit()
            assertTrue("a stale/foreign signature must not match the current dataset", !MapsDemo.isSeeded(ctx))
            val inserted = MapsDemo.seed(ctx)
            assertTrue("must actually reseed instead of no-op'ing on the stale flag", inserted > 0)
            assertTrue("isSeeded now reflects the CURRENT dataset", MapsDemo.isSeeded(ctx))
            assertEquals("re-tapping Load-demo-data after a real seed is a true no-op", 0, MapsDemo.seed(ctx))

            // A DB reset without clearing the flag would leave Load-demo-data
            // silently no-op'ing on empty data forever — resetSeedFlag is the fix.
            MapsDemo.resetSeedFlag(ctx)
            assertTrue("cleared flag means not-seeded again", !MapsDemo.isSeeded(ctx))
        } finally {
            db.clearAll(); prefs.edit().clear().commit()
            assertEquals("test cleans up after itself", 0, db.stopCount())
        }
    }

    @Test fun daily_adapter_recycles_and_binds_correctly() {
        // Daily/Stops moved from LinearLayout-in-ScrollView (inflates every row
        // up front) to RecyclerView (only the visible rows) — the full
        // 1987-1992 demo trip is ~2192/several-thousand rows. This proves the
        // adapter itself (item count + bound content) is wired correctly.
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val entries = listOf(
            com.diegonmarcos.cloudnav.maps.DailyEntry(0L, "Home — Copacabana", "Copacabana", "Rio de Janeiro", "Brazil", -22.97, -43.18),
            com.diegonmarcos.cloudnav.maps.DailyEntry(86_400_000L, null, null, null, null, 0.0, 0.0),
        )
        var tapped: Long? = null
        val adapter = com.diegonmarcos.cloudnav.maps.DailyAdapter(entries) { tapped = it }
        assertEquals(2, adapter.itemCount)

        val parent = android.widget.FrameLayout(ctx)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Home — Copacabana", holder.place.text)
        assertTrue(holder.sub.text.toString().contains("Rio de Janeiro"))

        adapter.onBindViewHolder(holder, 1)
        assertEquals("Resolving…", holder.place.text)  // unresolved entry — no crash, clear placeholder

        holder.tile.performClick()
        assertEquals(86_400_000L, tapped)  // last-bound row (index 1) is what the click fires for
    }

    @Test fun stops_adapter_recycles_and_binds_correctly() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val stops = listOf(
            com.diegonmarcos.cloudnav.maps.MapsDb.RichStop(1L, 0L, 3_600_000L, -22.9711, -43.1822, "Home — Copacabana", "Copacabana", "Rio de Janeiro", "Brazil"),
        )
        var tappedDay: Long? = null
        val adapter = com.diegonmarcos.cloudnav.maps.StopsAdapter(stops) { tappedDay = it }
        assertEquals(1, adapter.itemCount)

        val parent = android.widget.FrameLayout(ctx)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.place.text.toString().startsWith("Home — Copacabana"))
        assertEquals("-22.97110, -43.18220", holder.coords.text)

        holder.tile.performClick()
        // Tap resolves to that stop's LOCAL calendar-day midnight (see MapsDailyFragment.localMidnight).
        assertEquals(com.diegonmarcos.cloudnav.maps.MapsDailyFragment.localMidnight(0L), tappedDay)
    }

    @Test fun explored_groups_by_city_from_daily_entries_not_raw_stops() {
        fun entry(dayMs: Long, city: String?, place: String?, lat: Double, lon: Double) =
            com.diegonmarcos.cloudnav.maps.DailyEntry(dayMs, place, null, city, "Brazil", lat, lon)

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
            com.diegonmarcos.cloudnav.maps.DailyEntry(dayMs, null, null, city, null, 0.0, 0.0)

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

    @Test fun import_parses_travel_data_trips_shape() {
        // The real travel-data.json shape: { trips: [ {dateIn,dateOut,lat,lng,city,country,nomadRegion,state} ] }.
        val json = """
            { "traveler": "X", "trips": [
              { "dateIn": "2011-08-06", "dateOut": "2015-01-13", "lat": -23.4932, "lng": -46.6382,
                "city": "Sao Paulo", "country": "Brazil", "nomadRegion": "Sao Paulo, Brazil", "state": "Sao Paulo" },
              { "dateIn": "2015-01-15", "dateOut": "2015-01-16", "lat": -22.9622, "lng": -46.9890,
                "city": "Valinhos", "country": "Brazil" }
            ] }
        """.trimIndent()
        val stops = com.diegonmarcos.cloudnav.maps.MapsImport.parseStops(json)
        assertEquals(2, stops.size)
        val sp = stops[0]
        assertEquals(-23.4932, sp.lat, 1e-4)
        assertEquals(-46.6382, sp.lon, 1e-4)              // "lng" mapped to lon
        assertEquals("Sao Paulo, Brazil", sp.place)       // nomadRegion preferred for place
        assertEquals("Sao Paulo", sp.city)
        assertEquals("Brazil", sp.country)
        assertTrue("end after start", sp.endedAt > sp.startedAt)
        // dateIn parsed to UTC midnight.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        assertEquals("2011-08-06", fmt.format(java.util.Date(sp.startedAt)))
    }

    @Test fun import_accepts_bare_array_and_lon_and_epoch_variants() {
        // A bare array (no "trips" wrapper), "lon" instead of "lng", and an
        // epoch-ms startedAt instead of an ISO dateIn — all must parse.
        val json = """
            [ { "lat": 48.8584, "lon": 2.2945, "startedAt": 1000000000000, "city": "Paris", "country": "France" } ]
        """.trimIndent()
        val stops = com.diegonmarcos.cloudnav.maps.MapsImport.parseStops(json)
        assertEquals(1, stops.size)
        assertEquals(2.2945, stops[0].lon, 1e-4)
        assertEquals(1000000000000L, stops[0].startedAt)
        assertEquals("Paris", stops[0].place)             // falls back to city when no place/nomadRegion
    }

    @Test fun import_skips_rows_without_coords_or_date_and_bad_json() {
        val json = """
            { "trips": [
              { "city": "NoCoords", "dateIn": "2020-01-01" },
              { "lat": 1.0, "lng": 2.0 },
              { "lat": 1.0, "lng": 2.0, "dateIn": "2020-05-05", "city": "Good" }
            ] }
        """.trimIndent()
        val stops = com.diegonmarcos.cloudnav.maps.MapsImport.parseStops(json)
        assertEquals("only the fully-formed row survives", 1, stops.size)
        assertEquals("Good", stops[0].city)
        assertTrue("malformed JSON yields empty, never crashes", com.diegonmarcos.cloudnav.maps.MapsImport.parseStops("not json {").isEmpty())
    }

    @Test fun style_picker_kind_detection_is_structural() {
        // The dark FAB style-picker labels each row by structural kind
        // (hybrid → imagery+labels, vectorStyleUrl → vector, else raster) —
        // same fields the picker's `when` reads. Guards that classification.
        fun kind(key: String): String {
            val s = MapStyles.get(key)
            return when { s.hybrid -> "hybrid"; s.vectorStyleUrl != null -> "vector"; else -> "raster" }
        }
        assertEquals("raster", kind("light"))
        assertEquals("raster", kind("dark"))
        assertEquals("raster", kind("satellite"))
        assertEquals("vector", kind("vector_light"))
        assertEquals("vector", kind("vector_dark"))
        assertEquals("hybrid", kind("satellite_hybrid"))
    }

    @Test fun explored_pins_get_real_nonzero_coordinates() {
        // Pins are built from ExploredCity.lat/lon; a 0,0 (null-island) bug would
        // put every pin in the ocean off Africa. Prove grouping yields the real
        // averaged coordinates the map pins render at.
        fun entry(dayMs: Long, city: String, lat: Double, lon: Double) =
            com.diegonmarcos.cloudnav.maps.DailyEntry(dayMs, "$city place", null, city, "X", lat, lon)
        val cities = com.diegonmarcos.cloudnav.maps.MapsExploredFragment.groupByCity(listOf(
            entry(1L, "Rio de Janeiro", -22.9711, -43.1822),
            entry(2L, "Rio de Janeiro", -22.9869, -43.2045),
            entry(3L, "Tokyo", 35.7148, 139.7967),
        ))
        val rio = cities.first { it.city == "Rio de Janeiro" }
        assertEquals(-22.979, rio.lat, 0.01)   // average of the two Rio days
        assertEquals(-43.193, rio.lon, 0.01)
        val tokyo = cities.first { it.city == "Tokyo" }
        assertTrue("Tokyo pin is in the eastern hemisphere, not null-island", tokyo.lon > 100.0)
        cities.forEach { assertTrue("no null-island pin", it.lat != 0.0 || it.lon != 0.0) }
    }

    @Test fun explored_country_and_place_grouping() {
        // COUNTRY mode groups cities by country at their centroid; PLACES mode
        // gives one pin per distinct place with its visit days. (The Explored
        // FAB switches between these + CITY.)
        fun entry(dayMs: Long, place: String, city: String, country: String, lat: Double, lon: Double) =
            com.diegonmarcos.cloudnav.maps.DailyEntry(dayMs, place, null, city, country, lat, lon)
        val daily = listOf(
            entry(1L, "Ipanema", "Rio de Janeiro", "Brazil", -22.98, -43.20),
            entry(2L, "Se", "Sao Paulo", "Brazil", -23.55, -46.63),
            entry(3L, "Ipanema", "Rio de Janeiro", "Brazil", -22.98, -43.20),  // revisit
            entry(4L, "Eiffel", "Paris", "France", 48.85, 2.29),
        )
        val exp = com.diegonmarcos.cloudnav.maps.MapsExploredFragment
        val cities = exp.groupByCity(daily)
        val countries = exp.groupByCountry(cities)
        assertEquals("two countries", 2, countries.size)
        val brazil = countries.first { it.country == "Brazil" }
        assertEquals("Brazil has 2 cities", 2, brazil.cities.size)
        // centroid between Rio and Sao Paulo
        assertEquals(-23.265, brazil.lat, 0.05)

        // PLACES groups ALL raw stops → a city with several distinct places
        // yields several place-pins (more than its one city-pin).
        fun stop(started: Long, place: String, city: String, country: String, lat: Double, lon: Double) =
            com.diegonmarcos.cloudnav.maps.MapsDb.RichStop(0L, started, started + 1, lat, lon, place, null, city, country)
        val stops = listOf(
            stop(1_000L, "Ipanema", "Rio de Janeiro", "Brazil", -22.98, -43.20),
            stop(2_000L, "Copacabana", "Rio de Janeiro", "Brazil", -22.97, -43.18),
            stop(3_000L, "Maracana", "Rio de Janeiro", "Brazil", -22.91, -43.23),
            stop(4_000L, "Se", "Sao Paulo", "Brazil", -23.55, -46.63),
        )
        val placesFromStops = exp.groupByPlaceFromStops(stops)
        assertEquals("4 distinct places across all stops", 4, placesFromStops.size)
        assertTrue("PLACES (4) must render more pins than CITIES (2)",
            placesFromStops.size > exp.groupByCity(
                stops.map { com.diegonmarcos.cloudnav.maps.DailyEntry(it.startedAt, it.placeName, null, it.city, it.country, it.lat, it.lon) },
            ).size)
    }

    @Test fun map_texture_mode_arg_only_for_overlay_screens() {
        // Explored requests textureMode so its pin overlay composites in the
        // same frame as the map (no shake); other screens keep the default.
        val overlayMap = com.diegonmarcos.cloudnav.maps.MapsMapFragment
            .newInstance(worldView = true, textureMode = true)
        assertTrue("overlay map must be texture mode", overlayMap.arguments!!.getBoolean("texture_mode"))
        val normalMap = com.diegonmarcos.cloudnav.maps.MapsMapFragment.newInstance()
        assertTrue("normal map stays SurfaceView", !normalMap.arguments!!.getBoolean("texture_mode"))
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

    @Test fun basemap_stale_pinned_key_is_ignored() {
        // "vector_en" existed in an older build and was later replaced by
        // vector_light/vector_dark/satellite_hybrid. A device that pinned it
        // back then still carries the string in prefs — resolve() must ignore
        // any key no longer in MapStyles.order and fall back to the family
        // resolution, not resolve a phantom default style forever.
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("maps_basemap_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = com.diegonmarcos.cloudnav.maps.MapsBasemapPrefs(ctx)
        prefs.explicitStyleKey = "vector_en"   // stale key from a removed build
        prefs.preferVectorFamily = true
        assertEquals("stale pin ignored → family resolution", "vector_dark", prefs.resolve("dark"))
        prefs.explicitStyleKey = null; ctx.getSharedPreferences("maps_basemap_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
