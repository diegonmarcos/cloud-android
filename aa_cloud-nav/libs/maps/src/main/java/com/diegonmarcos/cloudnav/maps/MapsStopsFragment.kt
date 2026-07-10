package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Maps → Stops page. Flat reverse-chronological list of Stops — no day
 * grouping (that's the Timeline/Daily page). Each row shows full timestamp +
 * lat/lon (debug-grade detail) + the resolved address line.
 *
 * All-time by default; when opened via a Daily row's tap (see
 * [MapsTimelineTabsFragment.openStopsForDay]) it's scoped to just that one
 * calendar day instead ([ARG_DAY_MS]).
 *
 * RecyclerView-backed — the full 1987-1992 demo trip is several thousand
 * Stops; a LinearLayout-in-ScrollView would inflate every row up front (slow
 * to open, janky scroll). RecyclerView only inflates what's on screen.
 *
 * Light theme: dark text on a light surface. `setTextColor` is applied
 * AFTER `setTextAppearance` so the appearance's own (theme-derived) colour
 * can't override ours — that ordering bug was why the old white text was
 * invisible on the light background.
 */
class MapsStopsFragment : Fragment() {

    private val dayFilterMs: Long? get() = arguments?.getLong(ARG_DAY_MS, -1L)?.takeIf { it >= 0L }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_SURFACE)
        }

        val dayMs = dayFilterMs
        val (fromMs, toMs) = if (dayMs != null) dayMs to (dayMs + 24L * 3600_000L - 1L)
                             else 0L to System.currentTimeMillis()
        // All-time when unfiltered — see MapsDailyFragment's comment: a
        // "last N years" bound would hide the deliberately historical
        // (1987-1992) demo dataset.
        val stops = MapsDb.get(ctx).stopsBetween(fromMs, toMs)

        val dayFmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        root.addView(TextView(ctx).apply {
            text = if (dayMs != null) "Stops on ${dayFmt.format(Date(dayMs))} (${stops.size})" else "Stops (${stops.size})"
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextColor(COL_PRIMARY)
            val p = dp(ctx, 16)
            setPadding(p, p, p, dp(ctx, 12))
        })

        if (stops.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No Stops collected yet."
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                setTextColor(COL_SECONDARY)
                val p = dp(ctx, 16); setPadding(p, 0, p, p)
            })
            return root
        }

        root.addView(RecyclerView(ctx).apply {
            val side = dp(ctx, 16); setPadding(side, 0, side, side); clipToPadding = false
            layoutManager = LinearLayoutManager(ctx)
            adapter = StopsAdapter(stops) { dayMs2 -> openDayMap(dayMs2) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
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
        private const val ARG_DAY_MS = "day_ms"

        /** [dayMs] non-null → scope to just that calendar day (local midnight,
         *  see [MapsDailyFragment.localMidnight]); null → all-time (default). */
        fun newInstance(dayMs: Long? = null) = MapsStopsFragment().apply {
            arguments = Bundle().apply { if (dayMs != null) putLong(ARG_DAY_MS, dayMs) }
        }
    }
}

/** Row view holder for [StopsAdapter] — top-level (not nested) so it stays
 *  visible for testing, avoiding the cross-file companion-nested-member
 *  resolution quirk this codebase already hit once (see git history). */
class StopViewHolder(val tile: LinearLayout, val ts: TextView, val coords: TextView, val place: TextView) :
    RecyclerView.ViewHolder(tile)

/** Feeds [MapsDb.RichStop] rows into a [RecyclerView] — view recycling
 *  instead of inflating all several-thousand rows up front (the full
 *  1987-1992 demo trip). */
class StopsAdapter(private val items: List<MapsDb.RichStop>, private val onTap: (Long) -> Unit) :
    RecyclerView.Adapter<StopViewHolder>() {

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopViewHolder {
        val ctx = parent.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(10); setPadding(pad, pad, pad, pad)
            setBackgroundColor(MapsStopsFragment.COL_TILE)
            isClickable = true
            val m = dp(4)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, m, 0, m) }
        }
        val ts = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(MapsStopsFragment.COL_ACCENT)
        }
        val coords = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextColor(MapsStopsFragment.COL_SECONDARY)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val place = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        tile.addView(ts); tile.addView(coords); tile.addView(place)
        return StopViewHolder(tile, ts, coords, place)
    }

    override fun onBindViewHolder(holder: StopViewHolder, position: Int) {
        val stop = items[position]
        holder.ts.text = tsFmt.format(Date(stop.startedAt))
        holder.coords.text = "%.5f, %.5f".format(stop.lat, stop.lon)
        holder.place.text = (stop.placeName ?: "Resolving…") + "   ·  tap for this day's map ▸"
        holder.tile.setOnClickListener { onTap(MapsDailyFragment.localMidnight(stop.startedAt)) }
    }

    override fun getItemCount() = items.size
}
