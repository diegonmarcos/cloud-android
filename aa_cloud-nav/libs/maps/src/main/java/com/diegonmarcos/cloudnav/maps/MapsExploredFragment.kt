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

    // The live child map fragment — provides ONLY the basemap + camera. Pins
    // are drawn by our own Canvas overlay (see PinOverlay), NOT MapLibre's
    // GeoJSON/CircleLayer path, which was proven to never render features on
    // this stack (source registered + layer on top, yet 0 features tile).
    private var mapFrag: MapsMapFragment? = null
    private var chip: TextView? = null
    private var overlay: PinOverlay? = null
    private var overlayPins: List<OverlayPin> = emptyList()

    /** A single dot to draw: geo position, colour, and index back into the
     *  current mode's list (for tap → detail sheet). */
    class OverlayPin(val lat: Double, val lon: Double, val colorInt: Int, val index: Int)

    // Current mode's pin-backing objects, index-aligned with the pins' ids, so
    // a pin tap resolves straight back to its country/city/place.
    private var countries: List<ExploredCountry> = emptyList()
    private var cities: List<ExploredCity> = emptyList()
    private var places: List<ExploredPlace> = emptyList()

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

        // worldView: global overview — start showing the whole world, not the
        // user's last GPS fix (that put pins off-screen on other continents).
        // textureMode: composite the map + our pin overlay in one frame so pins
        // stay glued during pan/zoom (SurfaceView would let them trail/shake).
        // style "light" → vector_light (Timeline's default basemap).
        val frag = MapsMapFragment.newInstance(fab = true, worldView = true, textureMode = true, style = "light")
        mapFrag = frag
        childFragmentManager.beginTransaction().replace(mapHost.id, frag).commit()

        // Our own pin overlay ON TOP of the map (transparent, non-touchable so
        // the map keeps pan/zoom; taps come via the map's onMapClickAt).
        val pinOverlay = PinOverlay(ctx)
        overlay = pinOverlay
        root.addView(pinOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        frag.onCameraChanged = { pinOverlay.invalidate() }
        frag.onMapClickAt = { ll -> hitTest(ll) }
        frag.onMapReady = { _ ->
            recomputeOverlayPins(frame = true)
            pinOverlay.invalidate()
        }
        recomputeOverlayPins(frame = false)

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

        // Mode-switch FAB — small circle, TOP-RIGHT (mirrors the island chip's
        // top placement; the map's own locate/style FABs sit bottom-END).
        root.addView(FloatingActionButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_mapmode)
            contentDescription = "View mode"
            size = FloatingActionButton.SIZE_MINI
            setOnClickListener { showModeMenu() }
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
                Row("${c.country}   ·   ${c.cities.size} cities", c.lat, c.lon) { showCountrySheet(c) }
            }
            Mode.CITY -> cities.map { c ->
                Row("${c.city}${c.country?.let { ", $it" } ?: ""}   ·   ${c.visits.size} visits", c.lat, c.lon) { showCitySheet(c) }
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

    /** Rebuild [overlayPins] for the current [mode] and repaint the overlay.
     *  Optionally frames the camera to the pins (via the map's own fitTo). */
    private fun recomputeOverlayPins(frame: Boolean) {
        overlayPins = when (mode) {
            Mode.COUNTRY -> countries.mapIndexed { i, c -> OverlayPin(c.lat, c.lon, colorInt(c.country), i) }
            Mode.CITY -> cities.mapIndexed { i, c -> OverlayPin(c.lat, c.lon, colorInt(c.country), i) }
            Mode.PLACES -> places.mapIndexed { i, p -> OverlayPin(p.lat, p.lon, colorInt(p.country), i) }
        }
        android.util.Log.i("MapPins", "Explored.overlay mode=$mode pins=${overlayPins.size}")
        overlay?.invalidate()
        if (frame && overlayPins.isNotEmpty()) {
            mapFrag?.fitTo(overlayPins.map { MapsMapFragment.Pin(it.lat, it.lon, "#000000") })
        }
    }

    private fun colorInt(country: String?): Int =
        runCatching { android.graphics.Color.parseColor(countryColor(country)) }
            .getOrDefault(android.graphics.Color.parseColor(MapsMapFragment.COLOR_PLACE))

    /** Map tapped at [ll] → find the nearest overlay pin within a finger-radius
     *  (screen space) and open its detail sheet. */
    private fun hitTest(ll: org.maplibre.android.geometry.LatLng) {
        val frag = mapFrag ?: return
        val tap = frag.screenFor(ll.latitude, ll.longitude) ?: return
        val threshold = dp(22f)
        var bestI = -1; var bestD = Float.MAX_VALUE
        overlayPins.forEach { p ->
            val s = frag.screenFor(p.lat, p.lon) ?: return@forEach
            val d = kotlin.math.hypot((s.x - tap.x).toDouble(), (s.y - tap.y).toDouble()).toFloat()
            if (d < threshold && d < bestD) { bestD = d; bestI = p.index }
        }
        if (bestI < 0) return
        when (mode) {
            Mode.COUNTRY -> countries.getOrNull(bestI)?.let(::showCountrySheet)
            Mode.CITY -> cities.getOrNull(bestI)?.let(::showCitySheet)
            Mode.PLACES -> places.getOrNull(bestI)?.let(::showPlaceSheet)
        }
    }

    /** Transparent, non-touchable overlay that paints one dot per [overlayPins]
     *  entry by projecting its geo position to screen via the map. This is the
     *  actual pin renderer — MapLibre's GeoJSON layer path does not render on
     *  this stack, so we draw the dots ourselves. */
    private inner class PinOverlay(ctx: android.content.Context) : View(ctx) {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f)
        }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = dp(11f)
            setShadowLayer(dp(2f), 0f, 0f, 0xFF101418.toInt())
            textAlign = android.graphics.Paint.Align.CENTER
        }
        init { setWillNotDraw(false); isClickable = false; isFocusable = false }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val frag = mapFrag ?: return
            val r = dp(6f)
            val drawLabels = overlayPins.size <= 60   // countries: label; cities/places: dots only
            overlayPins.forEach { p ->
                val s = frag.screenFor(p.lat, p.lon) ?: return@forEach
                if (s.x < -r || s.y < -r || s.x > width + r || s.y > height + r) return@forEach
                fill.color = p.colorInt
                canvas.drawCircle(s.x, s.y, r, fill)
                canvas.drawCircle(s.x, s.y, r, stroke)
                if (drawLabels) {
                    val title = when (mode) {
                        Mode.COUNTRY -> countries.getOrNull(p.index)?.country
                        Mode.CITY -> cities.getOrNull(p.index)?.city
                        Mode.PLACES -> places.getOrNull(p.index)?.name
                    }
                    if (title != null) canvas.drawText(title, s.x, s.y - r - dp(4f), label)
                }
            }
        }
    }

    private fun showModeMenu() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141A25.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(18))
        }
        col.addView(TextView(ctx).apply {
            text = "View mode"; textSize = 16f
            setTextColor(MapsStopsFragment.COL_SECONDARY)
            setPadding(dp(8), dp(4), dp(8), dp(10))
        })
        listOf(
            Mode.COUNTRY to "🌐  Countries  (${countries.size})",
            Mode.CITY to "🏙️  Cities  (${cities.size})",
            Mode.PLACES to "📍  Places  (${places.size})",
        ).forEach { (m, label) ->
            col.addView(TextView(ctx).apply {
                text = (if (m == mode) "✓  " else "     ") + label
                textSize = 17f
                setTextColor(if (m == mode) MapsStopsFragment.COL_ACCENT else MapsStopsFragment.COL_PRIMARY)
                setPadding(dp(12), dp(14), dp(12), dp(14))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    if (m != mode) { mode = m; updateChip(); recomputeOverlayPins(frame = true) }
                }
            })
        }
        dialog.setContentView(col)
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
        "${c.cities.size} cit${if (c.cities.size == 1) "y" else "ies"} · ${c.cities.sumOf { it.visits.size }} day-visits",
        c.cities.sortedByDescending { it.visits.size }
            .map { "${it.city}  ·  ${it.visits.size} visit${if (it.visits.size == 1) "" else "s"}" },
    )

    private fun showCitySheet(city: ExploredCity) {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        sheet(
            "🏙️  ${city.city}" + (city.country?.let { ", $it" } ?: ""),
            "${city.visits.size} visit${if (city.visits.size == 1) "" else "s"}",
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

    /** Test hooks (the overlay is a plain View, so unlike the GL map it can be
     *  drawn to a Bitmap even on a headless emulator). */
    fun debugOverlayPinCount(): Int = overlayPins.size
    fun debugDrawOverlay(): android.graphics.Bitmap? {
        val o = overlay ?: return null
        if (o.width == 0 || o.height == 0) return null
        val b = android.graphics.Bitmap.createBitmap(o.width, o.height, android.graphics.Bitmap.Config.ARGB_8888)
        o.draw(android.graphics.Canvas(b))
        return b
    }

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

        fun newInstance() = MapsExploredFragment()
    }
}
