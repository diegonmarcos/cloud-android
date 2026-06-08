package com.diegonmarcos.superapp.maps

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
 * Maps → Timeline page. Day-grouped scroll of Stop rows from
 * [MapsDb.stopsBetween], newest day first. Each Stop shows:
 *   • Time (HH:mm)  · place name (or "Resolving…" while
 *     enrichment hasn't completed yet).
 *   • Neighborhood · City · Country  (whatever's resolved).
 *
 * Pull-to-refresh would be nice but we keep it minimal here — the
 * Tracker page's counters tell the user when new data lands; this
 * page rebuilds on every onResume. Push 3.5 can promote to RecyclerView
 * + DiffUtil if scroll grows past a few hundred stops.
 */
class MapsTimelineFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)
        renderInto(root)
        return scroll
    }

    private fun renderInto(root: LinearLayout) {
        val ctx = root.context
        // Window: last 5 years — matches the user's MyTrips spec.
        val now = System.currentTimeMillis()
        val fromMs = now - 5L * 365L * 24L * 3600L * 1000L
        val stops = MapsDb.get(ctx).stopsBetween(fromMs, now)

        if (stops.isEmpty()) {
            root.addView(header(ctx, "Timeline"))
            root.addView(caption(ctx, "No Stops collected yet. Start the tracker on the Tracker page and stay in one spot for at least the configured dwell time (default 5 min)."))
            return
        }

        // Group by yyyy-MM-dd (local). Stops are already in DESC
        // started_at order so groups land newest-day-first.
        val dayFmt   = SimpleDateFormat("yyyy-MM-dd EEE",        Locale.US)
        val titleFmt = SimpleDateFormat("EEE  ·  dd MMM yyyy",   Locale.US)
        val timeFmt  = SimpleDateFormat("HH:mm",                  Locale.US)
        var lastDay: String? = null
        for (stop in stops) {
            val key = dayFmt.format(Date(stop.startedAt))
            if (key != lastDay) {
                if (lastDay != null) root.addView(spacer(ctx, dp(ctx, 12)))
                root.addView(dayHeader(ctx, titleFmt.format(Date(stop.startedAt))))
                lastDay = key
            }
            root.addView(stopRow(ctx, stop, timeFmt.format(Date(stop.startedAt))))
        }
    }

    private fun dayHeader(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
    }

    private fun stopRow(
        ctx: android.content.Context,
        stop: MapsDb.RichStop,
        time: String,
    ): View {
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
            text = time + "  ·  " + (stop.placeName ?: "Resolving…")
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val parts = listOfNotNull(stop.neighborhood, stop.city, stop.country)
        if (parts.isNotEmpty()) {
            tile.addView(TextView(ctx).apply {
                text = parts.joinToString("  ·  ")
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        return tile
    }

    private fun header(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun caption(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
    }
    private fun spacer(ctx: android.content.Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MapsTimelineFragment() }
}
