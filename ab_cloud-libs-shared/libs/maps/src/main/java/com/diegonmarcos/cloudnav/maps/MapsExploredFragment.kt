package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timeline → "Explored" tab — a world map of everywhere the user has been.
 *
 * A FAB (bottom-start) switches the aggregation level:
 *   • COUNTRY — one pin per country (at its cities' centroid). Tap → the list
 *     of cities visited in that country.
 *   • CITY (default) — one pin per city. Tap → every day the user was there.
 *   • PLACES — one pin per distinct place. Tap → that place's visit days.
 *
 * All pins are COLOURED BY COUNTRY (deterministic hue, [countryColor]). Data
 * is sourced from [computeDailyLocations] (Daily's one-place-per-day picks),
 * NOT raw per-Stop rows, so revisited spots collapse to one clean pin.
 */
class MapsExploredFragment : Fragment() {

    enum class Mode { COUNTRY, CITY, PLACES }

    private var mode = Mode.CITY
    private var daily: List<DailyEntry> = emptyList()

    // The live child map fragment. Pins are now NATIVE MapLibre CircleLayer
    // features (typed FeatureCollection — proven to render on this stack once the
    // string-geojson bug was fixed), NOT a Canvas overlay. Native pins are part
    // of the GL frame, so they never trail/shake during pan/zoom.
    private var mapFrag: MapsMapFragment? = null
    private var chip: TextView? = null

    // Pins are a toggleable layer alongside the choropleth fills.
    private var pinsVisible = true

    // Choropleth fill layers (visited continents/countries/regions/nomad).
    private var geo: MapsGeoLayers? = null
    // Default: paint visited COUNTRIES light gray (the original ask).
    private var geoLayers: Set<MapsGeoLayers.Layer> = setOf(MapsGeoLayers.Layer.COUNTRIES)
    // PIN = paint only visited areas; DEFAULT = colour the whole world by continent.
    private var paintMode: MapsGeoLayers.PaintMode = MapsGeoLayers.PaintMode.PIN

    // Current mode's pin-backing objects, index-aligned with the pins' ids, so
    // a pin tap resolves straight back to its country/city/place.
    private var countries: List<ExploredCountry> = emptyList()
    private var cities: List<ExploredCity> = emptyList()
    private var places: List<ExploredPlace> = emptyList()
    // country name → [lat, lon] of its capital (COUNTRY-mode pin sits here).
    private var capitals: Map<String, DoubleArray> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx)
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val stops = MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis())
        daily = computeDailyLocations(stops)
        cities = groupByCity(daily)
        countries = groupByCountry(cities)
        // PLACES groups ALL raw stops (not the one-per-day `daily`), so a city
        // with several distinct places shows several pins → PLACES renders many
        // more pins than CITY.
        places = groupByPlaceFromStops(stops)
        capitals = runCatching {
            val o = org.json.JSONObject(ctx.assets.open("geo/capitals.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k); doubleArrayOf(a.getDouble(0), a.getDouble(1)) }
        }.getOrDefault(emptyMap())

        // worldView: global overview — start showing the whole world, not the
        // user's last GPS fix. style "light" → vector_light (Timeline's default).
        val frag = MapsMapFragment.newInstance(fab = true, worldView = true, style = "light")
        mapFrag = frag
        childFragmentManager.beginTransaction().replace(mapHost.id, frag).commit()

        // Native pins: a tap resolves the pin id back to its country/city/place.
        frag.onPinClick = { id -> onPinTapped(id) }
        frag.onMapReady = { _ -> recomputePins(frame = true) }

        // Choropleth fill layers — paint visited areas. Visited countries come
        // from the same grouped data the pins use; regions/continents are
        // derived inside the manager. Reinstalled on every style (re)load.
        val geoMgr = MapsGeoLayers(ctx, frag)
        geo = geoMgr
        geoMgr.configure(
            cities.map { it.country },
            cities.map { doubleArrayOf(it.lat, it.lon) },   // visited points → visited States (PIP)
            geoLayers, paintMode,
        )
        frag.onStyleReady = { geoMgr.reinstall() }

        // Summary "island" chip (top-center) — TAP it to open the full
        // scrollable list of the current mode's countries / cities / places.
        val card = MaterialCardView(ctx).apply {
            radius = dp(22f); cardElevation = dp(8f); useCompatPadding = true
            setCardBackgroundColor(0xF2141A25.toInt())
            strokeColor = MapsStopsFragment.COL_ACCENT; strokeWidth = dp(1f).toInt()
            isClickable = true; isFocusable = true
            setOnClickListener { showListSheet() }
        }
        chip = TextView(ctx).apply {
            textSize = 13f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            setPadding(dp(16f).toInt(), dp(9f).toInt(), dp(16f).toInt(), dp(9f).toInt())
        }
        card.addView(chip)
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setMargins(dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt(), 0)
        })
        updateChip()

        // Single Layers FAB — small BLACK circle (matches the island), TOP-RIGHT.
        // One sheet controls the pins layer + the painted fills.
        root.addView(FloatingActionButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_mapmode)
            contentDescription = "Layers"
            size = FloatingActionButton.SIZE_MINI
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF141A25.toInt())
            imageTintList = android.content.res.ColorStateList.valueOf(MapsStopsFragment.COL_PRIMARY)
            setOnClickListener { showLayersSheet() }
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
                .apply { val m = dp(12f).toInt(); setMargins(m, m, m, m) }
        })
        return root
    }

    /** Full scrollable list of the current mode's items. Tap a row → recenter
     *  the map on it and open its detail sheet. */
    private fun showListSheet() {
        val ctx = context ?: return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = BottomSheetDialog(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141A25.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        val header = when (mode) {
            Mode.COUNTRY -> "🌐  ${countries.size} countries"
            Mode.CITY -> "🏙️  ${cities.size} cities"
            Mode.PLACES -> "📍  ${places.size} places"
        }
        col.addView(TextView(ctx).apply {
            text = header; textSize = 17f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        // rows: (label, lat, lon, onOpen)
        data class Row(val label: String, val lat: Double, val lon: Double, val open: () -> Unit)
        val rows: List<Row> = when (mode) {
            Mode.COUNTRY -> countries.map { c ->
                val days = c.cities.sumOf { it.visits.size }
                Row("${c.country}   ·   ${c.cities.size} cities · $days days", c.lat, c.lon) { showCountrySheet(c) }
            }
            Mode.CITY -> cities.map { c ->
                val blocks = visitBlocks(c.visits.map { it.dayMs })
                Row("${c.city}${c.country?.let { ", $it" } ?: ""}   ·   $blocks visits · ${c.visits.size} days", c.lat, c.lon) { showCitySheet(c) }
            }
            Mode.PLACES -> places.map { p ->
                Row("${p.name}${p.city?.let { ", $it" } ?: ""}   ·   ${p.days.size} days", p.lat, p.lon) { showPlaceSheet(p) }
            }
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { r ->
            body.addView(TextView(ctx).apply {
                text = r.label; textSize = 15f
                setTextColor(MapsStopsFragment.COL_PRIMARY)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    mapFrag?.recenter(r.lat, r.lon, 6.0)
                    r.open()
                }
            })
        }
        col.addView(ScrollView(ctx).apply { addView(body) })
        dialog.setContentView(col)
        dialog.show()
    }

    /** Rebuild the NATIVE pins for the current [mode] (one CircleLayer feature
     *  per country/city/place, coloured by country, id = "MODE:index"). The map
     *  renders them in-frame (no shake) and sizes them by zoom. Optionally frames
     *  the camera to fit them (first load only). */
    private fun recomputePins(frame: Boolean) {
        val frag = mapFrag ?: return
        if (!pinsVisible) { frag.clearPins(); return }
        val pins = when (mode) {
            // Country pin sits at the CAPITAL (falls back to the cities centroid).
            Mode.COUNTRY -> countries.mapIndexed { i, c ->
                val cap = capitals[c.country]
                MapsMapFragment.Pin(cap?.get(0) ?: c.lat, cap?.get(1) ?: c.lon, countryColor(c.country), "", "C:$i")
            }
            Mode.CITY -> cities.mapIndexed { i, c -> MapsMapFragment.Pin(c.lat, c.lon, countryColor(c.country), "", "T:$i") }
            Mode.PLACES -> places.mapIndexed { i, p -> MapsMapFragment.Pin(p.lat, p.lon, countryColor(p.country), "", "P:$i") }
        }
        android.util.Log.i("MapPins", "Explored.pins mode=$mode pins=${pins.size}")
        frag.setPins(pins)
        if (frame && pins.isNotEmpty()) frag.fitTo(pins)
    }

    /** A native pin was tapped — its id is "MODE:index" → open the detail sheet. */
    private fun onPinTapped(id: String) {
        val i = id.substringAfter(':').toIntOrNull() ?: return
        when (id.firstOrNull()) {
            'C' -> countries.getOrNull(i)?.let(::showCountrySheet)
            'T' -> cities.getOrNull(i)?.let(::showCitySheet)
            'P' -> places.getOrNull(i)?.let(::showPlaceSheet)
        }
    }

    /** ONE sheet for every layer — styled like the black "island" chip (rounded
     *  dark card, accent stroke): the PINS layer (off / Countries / Cities /
     *  Places), the PAINT mode (Pin vs Default), and the painted choropleth fills. */
    private fun showLayersSheet() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }
        fun header(t: String) = col.addView(TextView(ctx).apply {
            text = t; textSize = 15f
            setTextColor(MapsStopsFragment.COL_SECONDARY)
            setPadding(dp(8), dp(12), dp(8), dp(6))
        })
        fun row(label: String, selected: Boolean, mark: String, onTap: () -> Unit) =
            col.addView(TextView(ctx).apply {
                text = "$mark  $label"; textSize = 17f
                setTextColor(if (selected) MapsStopsFragment.COL_ACCENT else MapsStopsFragment.COL_PRIMARY)
                setPadding(dp(12), dp(13), dp(12), dp(13))
                isClickable = true
                setOnClickListener { onTap(); dialog.dismiss(); showLayersSheet() }
            })

        // ── PINS layer (single-select aggregation, or off) ──
        header("Pins")
        listOf(
            null to "Off",
            Mode.COUNTRY to "🌐  Countries  (${countries.size})",
            Mode.CITY to "🏙️  Cities  (${cities.size})",
            Mode.PLACES to "📍  Places  (${places.size})",
        ).forEach { (m, label) ->
            val sel = if (m == null) !pinsVisible else (pinsVisible && mode == m)
            row(label, sel, if (sel) "◉" else "○") {
                if (m == null) pinsVisible = false else { pinsVisible = true; mode = m }
                updateChip(); recomputePins(frame = false)
            }
        }

        // ── PAINT mode: Pin (visited only) vs Default (whole world by continent) ──
        header("Paint mode")
        listOf(
            MapsGeoLayers.PaintMode.PIN to "Pin — only where I've been",
            MapsGeoLayers.PaintMode.DEFAULT to "Default — every country by continent",
        ).forEach { (pm, label) ->
            val sel = paintMode == pm
            row(label, sel, if (sel) "◉" else "○") {
                if (paintMode != pm) { paintMode = pm; geo?.setPaintMode(pm) }
            }
        }

        // ── PAINTED fills (multi-select) ──
        header(if (paintMode == MapsGeoLayers.PaintMode.DEFAULT) "Painted layers" else "Painted layers (visited)")
        MapsGeoLayers.Layer.values().forEach { layer ->
            val on = layer in geoLayers
            row(layer.label, on, if (on) "☑" else "☐") {
                geoLayers = if (on) geoLayers - layer else geoLayers + layer
                geo?.setEnabled(geoLayers)
            }
        }

        // Island-styled container: rounded dark card + accent stroke.
        val card = MaterialCardView(ctx).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(0xF2141A25.toInt())
            strokeColor = MapsStopsFragment.COL_ACCENT; strokeWidth = dp(1)
            addView(ScrollView(ctx).apply { addView(col) })
        }
        dialog.setContentView(card)
        dialog.show()
    }

    private fun updateChip() {
        val c = chip ?: return
        if (cities.isEmpty()) {
            c.text = "No places yet — load demo data or start the tracker (Configs → Tracker)."
            return
        }
        val countryCount = cities.mapNotNull { it.country }.toSet().size
        c.text = when (mode) {
            Mode.COUNTRY -> "🌐  ${countries.size} countries · ${cities.size} cities — tap a pin"
            Mode.CITY -> "🏙️  ${cities.size} cities · $countryCount countries — tap a pin"
            Mode.PLACES -> "📍  ${places.size} places · ${cities.size} cities — tap a pin"
        }
    }

    // ── detail sheets ──────────────────────────────────────────────────
    private fun sheet(title: String, subtitle: String, rows: List<String>) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141A25.toInt())
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        col.addView(TextView(ctx).apply {
            text = title; textSize = 21f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = subtitle; textSize = 13f
            setTextColor(MapsStopsFragment.COL_ACCENT); setPadding(0, dp(3), 0, dp(12))
        })
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { r ->
            body.addView(TextView(ctx).apply {
                text = r; textSize = 14f
                setTextColor(MapsStopsFragment.COL_PRIMARY); setPadding(0, dp(7), 0, dp(7))
            })
        }
        col.addView(ScrollView(ctx).apply { addView(body) })
        dialog.setContentView(col)
        dialog.show()
    }

    private fun showCountrySheet(c: ExploredCountry) = sheet(
        "🌐  ${c.country}",
        "${c.cities.size} cit${if (c.cities.size == 1) "y" else "ies"} · ${c.cities.sumOf { it.visits.size }} days total",
        c.cities.sortedByDescending { it.visits.size }.map {
            val blocks = visitBlocks(it.visits.map { v -> v.dayMs }); val days = it.visits.size
            "${it.city}  ·  $blocks visit${if (blocks == 1) "" else "s"} · $days day${if (days == 1) "" else "s"}"
        },
    )

    private fun showCitySheet(city: ExploredCity) {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        val blocks = visitBlocks(city.visits.map { it.dayMs }); val days = city.visits.size
        sheet(
            "🏙️  ${city.city}" + (city.country?.let { ", $it" } ?: ""),
            "$blocks visit${if (blocks == 1) "" else "s"} · $days day${if (days == 1) "" else "s"} total",
            city.visits.sortedByDescending { it.dayMs }
                .map { fmt.format(Date(it.dayMs)) + (it.placeName?.let { p -> "  ·  $p" } ?: "") },
        )
    }

    private fun showPlaceSheet(p: ExploredPlace) {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        sheet(
            "📍  ${p.name}",
            listOfNotNull(p.city, p.country).joinToString(", ") + " · ${p.days.size} day${if (p.days.size == 1) "" else "s"}",
            p.days.sortedDescending().map { fmt.format(Date(it)) },
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ── test hooks ──────────────────────────────────────────────────────
    /** How many native pins the current mode built (Places must be > Cities). */
    fun debugPinModelCount(): Int = when (mode) {
        Mode.COUNTRY -> countries.size; Mode.CITY -> cities.size; Mode.PLACES -> places.size
    }
    /** Feature count the LIVE MapLibre pin SOURCE reports — proves the native
     *  CircleLayer actually renders the typed FeatureCollection on this stack. */
    fun debugPinSourceCount(): Int = mapFrag?.debugPinFeatureCount() ?: -1
    fun debugSetMode(m: Mode) { mode = m; updateChip(); recomputePins(frame = false) }
    /** Visited-only feature count the manager built for [layer] (needs assets). */
    fun debugGeoVisitedCount(layer: MapsGeoLayers.Layer): Int = geo?.visitedCount(layer) ?: -1
    /** Feature count the LIVE MapLibre fill SOURCE reports — proves the typed
     *  FeatureCollection registered on this stack (make-or-break for Approach A). */
    fun debugFillSourceCount(layer: MapsGeoLayers.Layer): Int =
        mapFrag?.debugFillFeatureCount("geo-src-${layer.id}") ?: -1

    // ── data model ─────────────────────────────────────────────────────
    data class CityVisit(val dayMs: Long, val placeName: String?)
    data class ExploredCity(
        val city: String, val country: String?,
        val lat: Double, val lon: Double, val visits: List<CityVisit>,
    )
    data class ExploredCountry(
        val country: String, val lat: Double, val lon: Double, val cities: List<ExploredCity>,
    )
    data class ExploredPlace(
        val name: String, val city: String?, val country: String?,
        val lat: Double, val lon: Double, val days: List<Long>,
    )

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** One [ExploredCity] per distinct, non-blank city. Pure/tested. */
        fun groupByCity(daily: List<DailyEntry>): List<ExploredCity> =
            daily.filter { !it.city.isNullOrBlank() }
                .groupBy { it.city }
                .map { (city, entries) ->
                    ExploredCity(
                        city = city!!,
                        country = entries.firstNotNullOfOrNull { it.country },
                        lat = entries.map { it.lat }.average(), lon = entries.map { it.lon }.average(),
                        visits = entries.map { CityVisit(it.dayMs, it.placeName) },
                    )
                }.sortedByDescending { it.visits.maxOf { v -> v.dayMs } }

        /** One [ExploredCountry] per distinct country, positioned at the
         *  centroid of its cities. Pure/tested. */
        fun groupByCountry(cities: List<ExploredCity>): List<ExploredCountry> =
            cities.filter { !it.country.isNullOrBlank() }
                .groupBy { it.country }
                .map { (country, cs) ->
                    ExploredCountry(
                        country = country!!,
                        lat = cs.map { it.lat }.average(), lon = cs.map { it.lon }.average(),
                        cities = cs,
                    )
                }.sortedByDescending { it.cities.sumOf { c -> c.visits.size } }

        /** One [ExploredPlace] per distinct place name across ALL raw stops
         *  (not the one-per-day picks) — so every place the user stopped at gets
         *  its own pin, and a city with N distinct places shows N pins. Pure/
         *  tested. */
        fun groupByPlaceFromStops(stops: List<MapsDb.RichStop>): List<ExploredPlace> =
            stops.filter { !it.placeName.isNullOrBlank() }
                .groupBy { it.placeName }
                .map { (name, group) ->
                    val g0 = group.first()
                    ExploredPlace(
                        name = name!!, city = g0.city, country = g0.country,
                        lat = group.map { it.lat }.average(), lon = group.map { it.lon }.average(),
                        days = group.map { MapsDailyFragment.localMidnight(it.startedAt) }.distinct(),
                    )
                }.sortedByDescending { it.days.size }

        /** Deterministic pin colour per COUNTRY — stable hue from the name,
         *  golden-angle spread, vivid on the dark basemap. Null/blank → the
         *  neutral place-blue. Pure/tested. */
        fun countryColor(country: String?): String {
            val c = country?.trim().orEmpty()
            if (c.isEmpty()) return MapsMapFragment.COLOR_PLACE
            var h = 0
            for (ch in c) h = h * 31 + ch.code
            val hue = ((h * 137) % 360 + 360) % 360
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.78f, 0.95f))
            return "#%06X".format(rgb and 0xFFFFFF)
        }

        /** Number of separate VISIT blocks (runs of consecutive days) from a list
         *  of day-timestamps — distinct from the total day count. A gap of more
         *  than ~1.5 days starts a new visit. Pure/tested. */
        fun visitBlocks(dayMsList: List<Long>): Int {
            if (dayMsList.isEmpty()) return 0
            val days = dayMsList.map { MapsDailyFragment.localMidnight(it) }.distinct().sorted()
            var blocks = 1
            for (k in 1 until days.size) if (days[k] - days[k - 1] > 36L * 3600_000L) blocks++
            return blocks
        }

        fun newInstance() = MapsExploredFragment()
    }
}
