package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Maps → Stops page. Flat reverse-chronological list of every Stop in
 * the local DB — no day grouping (that's the Timeline/Daily page). Each
 * row shows full timestamp + lat/lon (debug-grade detail) + the resolved
 * address line.
 *
 * Light theme: dark text on a light surface. `setTextColor` is applied
 * AFTER `setTextAppearance` so the appearance's own (theme-derived) colour
 * can't override ours — that ordering bug was why the old white text was
 * invisible on the light background.
 */
class MapsStopsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(COL_SURFACE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        val now = System.currentTimeMillis()
        val fromMs = now - 5L * 365L * 24L * 3600L * 1000L
        val stops = MapsDb.get(ctx).stopsBetween(fromMs, now)

        root.addView(TextView(ctx).apply {
            text = "Stops (${stops.size})"
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextColor(COL_PRIMARY)
            setPadding(0, 0, 0, dp(ctx, 12))
        })

        if (stops.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No Stops collected yet."
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                setTextColor(COL_SECONDARY)
            })
            return scroll
        }

        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (stop in stops) {
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
                setBackgroundColor(COL_TILE)
                isClickable = true
                val m = dp(ctx, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, m, 0, m) }
                // Tap → the same full-screen day-map Daily opens (every place
                // visited that calendar day, pinned).
                setOnClickListener { openDayMap(MapsDailyFragment.localMidnight(stop.startedAt)) }
            }
            tile.addView(TextView(ctx).apply {
                text = tsFmt.format(Date(stop.startedAt))
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setTextColor(COL_ACCENT)
            })
            tile.addView(TextView(ctx).apply {
                text = "%.5f, %.5f".format(stop.lat, stop.lon)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setTextColor(COL_SECONDARY)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            tile.addView(TextView(ctx).apply {
                text = (stop.placeName ?: "Resolving…") + "   ·  tap for this day's map ▸"
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setTextColor(COL_PRIMARY)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            root.addView(tile)
        }
        return scroll
    }

    private fun openDayMap(dayMs: Long) {
        requireActivity().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, MapsDayMapFragment.newInstance(dayMs), "day-map")
            .addToBackStack("day-map")
            .commit()
    }

    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        // Light-mode palette (shared with MapsDailyFragment).
        const val COL_SURFACE   = 0xFFFFFFFF.toInt()
        const val COL_TILE      = 0x0F000000           // ~6% black — subtle card
        const val COL_PRIMARY   = 0xFF1A1C1A.toInt()   // near-black body
        const val COL_SECONDARY = 0xFF5C5F5C.toInt()   // grey caption
        const val COL_ACCENT    = 0xFF0B8043.toInt()   // maps-green subhead

        fun newInstance() = MapsStopsFragment()
    }
}
