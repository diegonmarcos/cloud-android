package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timeline → "Explored" tab — every place the user has ever been, pinned on
 * one map (all-time, no window — the whole point is "everyplace I've been").
 * Places visited more than once collapse to a single pin; tapping it opens a
 * sheet listing every visit (date + how long).
 *
 * Grouping key is (place name if resolved, else "lat,lon" rounded to ~100m) +
 * city — [ExploredPlace.key] — so "Home — Copacabana" visited on three
 * different days is one pin with a 3-entry visit history, while two
 * different unresolved stops a few blocks apart in the same city stay
 * separate pins instead of merging.
 */
class MapsExploredFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(fab = true)
    private var places: List<ExploredPlace> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx)
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val stops = MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis())
        places = groupByPlace(stops)
        val pins = places.mapIndexed { i, p -> MapsMapFragment.Pin(p.lat, p.lon, MapsMapFragment.COLOR_PLACE, title = p.title, id = i.toString()) }

        mapFragment.onPinClick = { id -> places.getOrNull(id.toIntOrNull() ?: -1)?.let { showVisitHistory(it) } }
        mapFragment.onMapReady = { _ -> mapFragment.setPins(pins); if (pins.isNotEmpty()) mapFragment.fitTo(pins) }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }
        return root
    }

    private fun showVisitHistory(place: ExploredPlace) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        col.addView(TextView(ctx).apply {
            text = place.title; textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = "${place.visits.size} visit${if (place.visits.size == 1) "" else "s"}"
            textSize = 13f; setTextColor(0xFF5C5F5C.toInt()); setPadding(0, dp(2), 0, dp(10))
        })
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy · HH:mm", Locale.US)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        place.visits.sortedByDescending { it.startedAt }.forEach { v ->
            body.addView(TextView(ctx).apply {
                val mins = ((v.endedAt ?: v.startedAt) - v.startedAt) / 60_000L
                text = fmt.format(Date(v.startedAt)) + if (mins > 0) "  ·  ${mins} min" else ""
                textSize = 14f; setPadding(0, dp(6), 0, dp(6))
            })
        }
        col.addView(ScrollView(ctx).apply { addView(body) })
        dialog.setContentView(col)
        dialog.show()
    }

    /** One visit to a resolved-or-not place. */
    data class Visit(val startedAt: Long, val endedAt: Long?)

    /** A deduplicated pin: every [Visit] the user ever made to this place. */
    data class ExploredPlace(val key: String, val title: String, val lat: Double, val lon: Double, val visits: List<Visit>)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Pure (no DB/Android) grouping — exposed for testing. Dedupe key:
         *  the resolved place name when known (so "Home — Copacabana" merges
         *  across every visit regardless of GPS jitter), else the rounded
         *  lat/lon (~3 decimals, ~100m) so nearby-but-distinct unresolved
         *  stops don't wrongly merge. City is folded into the key too, so a
         *  same-named place in two different cities never collapses together. */
        fun groupByPlace(stops: List<MapsDb.RichStop>): List<ExploredPlace> {
            return stops.groupBy { s ->
                val name = s.placeName?.trim()?.takeIf { it.isNotEmpty() }
                val locator = name ?: "%.3f,%.3f".format(s.lat, s.lon)
                "${s.city.orEmpty()}|$locator"
            }.map { (key, group) ->
                val first = group.first()
                val title = first.placeName?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(first.neighborhood, first.city).joinToString(", ").ifBlank { "Unnamed place" }
                ExploredPlace(
                    key = key, title = title,
                    lat = group.map { it.lat }.average(), lon = group.map { it.lon }.average(),
                    visits = group.map { Visit(it.startedAt, it.endedAt) },
                )
            }.sortedByDescending { it.visits.maxOf { v -> v.startedAt } }
        }

        fun newInstance() = MapsExploredFragment()
    }
}
