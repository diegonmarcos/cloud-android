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

    private val mapFragment = MapsMapFragment.newInstance(fab = true)
    private var cities: List<ExploredCity> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx)
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val stops = MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis())
        val daily = computeDailyLocations(stops)
        cities = groupByCity(daily)
        val pins = cities.mapIndexed { i, c -> MapsMapFragment.Pin(c.lat, c.lon, MapsMapFragment.COLOR_PLACE, title = c.city, id = i.toString()) }

        mapFragment.onPinClick = { id -> cities.getOrNull(id.toIntOrNull() ?: -1)?.let { showVisitHistory(it) } }
        mapFragment.onMapReady = { _ -> mapFragment.setPins(pins); if (pins.isNotEmpty()) mapFragment.fitTo(pins) }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // Always-visible summary — so "nothing on screen" is never ambiguous
        // between "no data yet" and "a real rendering bug".
        val totalVisits = cities.sumOf { it.visits.size }
        val card = MaterialCardView(ctx).apply {
            radius = dp(14f); cardElevation = dp(6f); useCompatPadding = true
        }
        card.addView(TextView(ctx).apply {
            text = if (cities.isEmpty())
                "No places yet — load demo data or start the tracker (Configs → Tracker)."
            else "${cities.size} cities · $totalVisits day-visits"
            textSize = 14f
            setPadding(dp(14).toInt(), dp(10).toInt(), dp(14).toInt(), dp(10).toInt())
        })
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(8).toInt(), dp(8).toInt(), dp(8).toInt(), 0)
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
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        col.addView(TextView(ctx).apply {
            text = city.city; textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = "${city.visits.size} visit${if (city.visits.size == 1) "" else "s"}"
            textSize = 13f; setTextColor(0xFF5C5F5C.toInt()); setPadding(0, dp(2), 0, dp(10))
        })
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        city.visits.sortedByDescending { it.dayMs }.forEach { v ->
            body.addView(TextView(ctx).apply {
                text = fmt.format(Date(v.dayMs)) + (v.placeName?.let { "  ·  $it" } ?: "")
                textSize = 14f; setPadding(0, dp(6), 0, dp(6))
            })
        }
        col.addView(ScrollView(ctx).apply { addView(body) })
        dialog.setContentView(col)
        dialog.show()
    }

    /** One day the user was in a given [ExploredCity]. */
    data class CityVisit(val dayMs: Long, val placeName: String?)

    /** A deduplicated city pin: every day-visit ([CityVisit]) to it. */
    data class ExploredCity(val city: String, val lat: Double, val lon: Double, val visits: List<CityVisit>)

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
                        lat = entries.map { it.lat }.average(), lon = entries.map { it.lon }.average(),
                        visits = entries.map { CityVisit(it.dayMs, it.placeName) },
                    )
                }.sortedByDescending { it.visits.maxOf { v -> v.dayMs } }
        }

        fun newInstance() = MapsExploredFragment()
    }
}
