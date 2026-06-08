package com.diegonmarcos.superapp.maps

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

/**
 * "Daily" tab — one row per calendar day showing where the user slept
 * that night. The heuristic: for each day, find the Stop that was
 * ACTIVE at 03:00 local time of that day (most people are at their
 * sleeping place at 3 AM). First match per day wins; subsequent stops
 * on the same day are ignored here (they live in the Stops tab).
 *
 * 5-year window. Reverse-chronological (newest day first), matching
 * the Stops tab's order.
 */
class MapsDailyFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        val now = System.currentTimeMillis()
        val fromMs = now - 5L * 365L * 24L * 3600L * 1000L
        val stops = MapsDb.get(ctx).stopsBetween(fromMs, now)
        val daily = computeDailyLocations(stops)

        if (daily.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No daily locations yet. The Daily view shows where you slept each night — needs at least one Stop active around 03:00 local time to populate. Start the tracker on the Configs page and dwell somewhere overnight."
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            })
            return scroll
        }

        val dayFmt = SimpleDateFormat("EEE  ·  dd MMM yyyy", Locale.US)
        for (entry in daily) {
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
                setBackgroundColor(0x18FFFFFFL.toInt())
                val m = dp(ctx, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, m, 0, m) }
            }
            tile.addView(TextView(ctx).apply {
                text = dayFmt.format(Date(entry.dayMs))
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            })
            tile.addView(TextView(ctx).apply {
                text = entry.placeName ?: "Resolving…"
                setTextColor(0xFFFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            val parts = listOfNotNull(entry.neighborhood, entry.city, entry.country)
            if (parts.isNotEmpty()) {
                tile.addView(TextView(ctx).apply {
                    text = parts.joinToString("  ·  ")
                    setTextColor(0xAAFFFFFFL.toInt())
                    setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
            root.addView(tile)
        }
        return scroll
    }

    /** For each calendar day in [stops], pick the Stop that was active
     *  at 03:00 local time of that day. Returns reverse-chronological. */
    private fun computeDailyLocations(stops: List<MapsDb.RichStop>): List<DailyEntry> {
        if (stops.isEmpty()) return emptyList()
        val out = mutableMapOf<String, DailyEntry>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        for (stop in stops) {
            val s = stop.startedAt
            val e = stop.endedAt ?: System.currentTimeMillis()
            // Walk from the start day's 03:00 forward, day by day.
            cal.timeInMillis = s
            cal.set(Calendar.HOUR_OF_DAY, 3)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            // If 03:00 of the start day is BEFORE the stop began, this
            // stop didn't cover that day's 3 AM — skip to the next day.
            if (cal.timeInMillis < s) cal.add(Calendar.DAY_OF_MONTH, 1)
            while (cal.timeInMillis in s..e) {
                val key = dayFmt.format(Date(cal.timeInMillis))
                if (!out.containsKey(key)) {
                    out[key] = DailyEntry(
                        dayMs        = cal.timeInMillis,
                        placeName    = stop.placeName,
                        neighborhood = stop.neighborhood,
                        city         = stop.city,
                        country      = stop.country,
                    )
                }
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return out.entries.sortedByDescending { it.key }.map { it.value }
    }

    private data class DailyEntry(
        val dayMs: Long,
        val placeName: String?,
        val neighborhood: String?,
        val city: String?,
        val country: String?,
    )

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MapsDailyFragment() }
}
