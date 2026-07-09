package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row of the Daily list: the calendar day + its LONGEST-dwell place.
 *  Also [MapsExploredFragment]'s data source, grouped by city. Top-level
 *  (not nested in [MapsDailyFragment]) so both fragments share it plainly. */
data class DailyEntry(
    val dayMs: Long,
    val placeName: String?,
    val neighborhood: String?,
    val city: String?,
    val country: String?,
    val lat: Double,
    val lon: Double,
)

/** For each calendar day touched by any Stop, pick the Stop with the LONGEST
 *  overlap into that day. Reverse-chronological. Pure — no DB/Android
 *  dependency beyond the passed-in stops, so it's directly testable and
 *  reusable by both [MapsDailyFragment] and [MapsExploredFragment]. */
fun computeDailyLocations(stops: List<MapsDb.RichStop>): List<DailyEntry> {
    if (stops.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    data class Best(val anchorMs: Long, val stop: MapsDb.RichStop, val overlapMs: Long)
    val perDay = mutableMapOf<String, Best>()
    val cal = Calendar.getInstance()
    for (stop in stops) {
        val s = stop.startedAt
        val e = stop.endedAt ?: now
        if (e <= s) continue
        cal.timeInMillis = MapsDailyFragment.localMidnight(s)
        while (cal.timeInMillis <= e) {
            val dayStart = cal.timeInMillis
            val dayEnd   = dayStart + 24L * 3600_000L - 1L
            val overlap  = (minOf(dayEnd, e) - maxOf(dayStart, s)).coerceAtLeast(0)
            if (overlap > 0) {
                val key = dayFmt.format(Date(dayStart))
                val cur = perDay[key]
                if (cur == null || overlap > cur.overlapMs) {
                    perDay[key] = Best(dayStart, stop, overlap)
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
    return perDay.entries
        .sortedByDescending { it.key }
        .map { (_, b) ->
            DailyEntry(
                dayMs        = b.anchorMs,
                placeName    = b.stop.placeName,
                neighborhood = b.stop.neighborhood,
                city         = b.stop.city,
                country      = b.stop.country,
                lat          = b.stop.lat,
                lon          = b.stop.lon,
            )
        }
}

/**
 * "Daily" tab — one row per calendar day showing the user's primary
 * place that day (the LONGEST-dwell Stop overlapping that day). Tapping a
 * day switches Timeline to the Stops tab, filtered to that one day (via the
 * host [MapsTimelineTabsFragment]) — tapping a stop row there is what opens
 * the full-screen day map ([MapsDayMapFragment]).
 *
 * [computeDailyLocations] is also the data source for the Explored tab
 * ([MapsExploredFragment]) — Explored groups these same per-day city picks
 * by city, so it never shows raw per-stop noise.
 *
 * Light theme: dark text on a light surface (palette shared with
 * [MapsStopsFragment]); `setTextColor` applied AFTER `setTextAppearance`
 * so the appearance can't override the colour.
 */
class MapsDailyFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        val now = System.currentTimeMillis()
        // All-time — not a rolling "last N years" window. The demo dataset is a
        // deliberately historical (1987-1992) block precisely so it never
        // collides with real tracked data; a bounded "recent" window would hide
        // it (the bug this avoids — see MapsDemo's doc comment).
        val stops = MapsDb.get(ctx).stopsBetween(0L, now)
        val daily = computeDailyLocations(stops)

        if (daily.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No daily locations yet. Daily picks the LONGEST-dwell Stop per day — needs at least one Stop in the DB. Start the tracker on the Configs page, dwell ≥ 5 min in one spot, and refresh."
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                setTextColor(MapsStopsFragment.COL_SECONDARY)
            })
            return scroll
        }

        val dayFmt = SimpleDateFormat("EEE  ·  dd MMM yyyy", Locale.US)
        for (entry in daily) {
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
                setBackgroundColor(MapsStopsFragment.COL_TILE)
                isClickable = true
                val m = dp(ctx, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, m, 0, m) }
                setOnClickListener {
                    (parentFragment as? MapsTimelineTabsFragment)?.openStopsForDay(entry.dayMs)
                }
            }
            tile.addView(TextView(ctx).apply {
                text = dayFmt.format(Date(entry.dayMs))
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setTextColor(MapsStopsFragment.COL_ACCENT)
            })
            tile.addView(TextView(ctx).apply {
                text = entry.placeName ?: "Resolving…"
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setTextColor(MapsStopsFragment.COL_PRIMARY)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            val parts = listOfNotNull(entry.neighborhood, entry.city, entry.country)
            tile.addView(TextView(ctx).apply {
                text = if (parts.isNotEmpty()) parts.joinToString("  ·  ") + "    ·  tap for this day's stops ▸"
                       else "tap for this day's stops ▸"
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setTextColor(MapsStopsFragment.COL_SECONDARY)
                setPadding(0, dp(ctx, 2), 0, 0)
            })
            root.addView(tile)
        }
        return scroll
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance() = MapsDailyFragment()

        /** Device-local midnight (epoch ms) of the calendar day [ts] falls in —
         *  the day-bucketing convention shared with [MapsDayMapFragment] and
         *  [MapsStopsFragment]'s "open this stop's day" tap. */
        fun localMidnight(ts: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
