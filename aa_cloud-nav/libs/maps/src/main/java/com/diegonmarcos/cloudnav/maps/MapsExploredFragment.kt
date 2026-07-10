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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timeline → "Explored" tab — every CITY the user has ever been, one pin
 * each (all-time, no window). Sourced from [computeDailyLocations]
 * — the same one-representative-place-per-calendar-day picks Daily shows —
 * grouped by city, NOT raw per-Stop data, so a city visited on many days
 * (lots of Stops) still collapses to one clean pin instead of polluting the
 * map. Tapping a pin lists every day the user was in that city.
 */
class MapsExploredFragment : Fragment() {

    // worldView: this is a GLOBAL overview — the camera must start showing the
    // whole world, NOT the user's last GPS fix (which put pins on other
    // continents off-screen: the "empty map" bug).
    private val mapFragment = MapsMapFragment.newInstance(fab = true, worldView = true)
    private var cities: List<ExploredCity> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx)
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val stops = MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis())
        val daily = computeDailyLocations(stops)
        cities = groupByCity(daily)
        // One pin per city, COLOURED BY COUNTRY — deterministic hue per
        // country name, so all Brazilian cities share one colour, all French
        // another, etc. (countryColor is pure/tested).
        val pins = cities.mapIndexed { i, c ->
            MapsMapFragment.Pin(c.lat, c.lon, countryColor(c.country), title = c.city, id = i.toString())
        }

        mapFragment.onPinClick = { id -> cities.getOrNull(id.toIntOrNull() ?: -1)?.let { showVisitHistory(it) } }
        // Pins set BEFORE the map fragment commits: onStyleLoaded bakes the
        // pins field into the GeoJSON source at style-load time, so rendering
        // never depends on the async onMapReady callback racing the nested
        // child-fragment + vector-fetch chain. onMapReady only frames the camera.
        mapFragment.setPins(pins)
        mapFragment.onMapReady = { _ -> if (pins.isNotEmpty()) mapFragment.fitTo(pins) }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // Always-visible summary chip — so "nothing on screen" is never
        // ambiguous between "no data yet" and "a real rendering bug".
        val totalVisits = cities.sumOf { it.visits.size }
        val card = MaterialCardView(ctx).apply {
            radius = dp(22f); cardElevation = dp(8f); useCompatPadding = true
            setCardBackgroundColor(0xF2141A25.toInt())
            strokeColor = MapsStopsFragment.COL_ACCENT; strokeWidth = dp(1f).toInt()
        }
        val countryCount = cities.mapNotNull { it.country }.toSet().size
        card.addView(TextView(ctx).apply {
            text = if (cities.isEmpty())
                "No places yet — load demo data or start the tracker (Configs → Tracker)."
            else "🌍  ${cities.size} cities · $countryCount countries · $totalVisits day-visits — tap a pin"
            textSize = 13f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            setPadding(dp(16f).toInt(), dp(9f).toInt(), dp(16f).toInt(), dp(9f).toInt())
        })
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setMargins(dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt(), 0)
        })
        return root
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun showVisitHistory(city: ExploredCity) {
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
            text = "📍  ${city.city}" + (city.country?.let { c -> ", $c" } ?: "")
            textSize = 21f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = "${city.visits.size} visit${if (city.visits.size == 1) "" else "s"}"
            textSize = 13f; setTextColor(MapsStopsFragment.COL_ACCENT); setPadding(0, dp(3), 0, dp(12))
        })
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        city.visits.sortedByDescending { it.dayMs }.forEach { v ->
            body.addView(TextView(ctx).apply {
                text = fmt.format(Date(v.dayMs)) + (v.placeName?.let { "  ·  $it" } ?: "")
                textSize = 14f; setTextColor(MapsStopsFragment.COL_PRIMARY); setPadding(0, dp(7), 0, dp(7))
            })
        }
        col.addView(ScrollView(ctx).apply { addView(body) })
        dialog.setContentView(col)
        dialog.show()
    }

    /** One day the user was in a given [ExploredCity]. */
    data class CityVisit(val dayMs: Long, val placeName: String?)

    /** A deduplicated city pin: every day-visit ([CityVisit]) to it. */
    data class ExploredCity(
        val city: String,
        val country: String?,
        val lat: Double,
        val lon: Double,
        val visits: List<CityVisit>,
    )

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Pure (no DB/Android) grouping — exposed for testing. One pin per
         *  distinct, non-blank city name; blank/unresolved-city daily entries
         *  are dropped (nothing meaningful to pin or title). */
        fun groupByCity(daily: List<DailyEntry>): List<ExploredCity> {
            return daily.filter { !it.city.isNullOrBlank() }
                .groupBy { it.city }
                .map { (city, entries) ->
                    ExploredCity(
                        city = city!!,
                        country = entries.firstNotNullOfOrNull { it.country },
                        lat = entries.map { it.lat }.average(), lon = entries.map { it.lon }.average(),
                        visits = entries.map { CityVisit(it.dayMs, it.placeName) },
                    )
                }.sortedByDescending { it.visits.maxOf { v -> v.dayMs } }
        }

        /** Deterministic pin colour per COUNTRY — a stable hue derived from the
         *  country name (same country → same colour on every run and device),
         *  spread around the wheel via the golden angle so neighbouring names
         *  don't collide. Vivid saturation/value for legibility on the dark
         *  basemap. Null/blank country → the neutral place-blue. Pure/tested. */
        fun countryColor(country: String?): String {
            val c = country?.trim().orEmpty()
            if (c.isEmpty()) return MapsMapFragment.COLOR_PLACE
            var h = 0
            for (ch in c) h = h * 31 + ch.code   // stable across JVMs/devices
            val hue = ((h * 137) % 360 + 360) % 360   // golden-angle spread
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.78f, 0.95f))
            return "#%06X".format(rgb and 0xFFFFFF)
        }

        fun newInstance() = MapsExploredFragment()
    }
}
