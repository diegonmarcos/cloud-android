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
 * the local DB — no day grouping (that's the Timeline page). Each row
 * shows full timestamp + lat/lon (debug-grade detail) + the resolved
 * address line.
 *
 * Practical use case: scrub the raw Stop log when timeline grouping
 * loses signal — e.g. a hotel in a foreign country where the
 * neighborhood string is the only useful disambiguator.
 */
class MapsStopsFragment : Fragment() {

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

        root.addView(TextView(ctx).apply {
            text = "Stops (${stops.size})"
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 12))
        })

        if (stops.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No Stops collected yet."
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            })
            return scroll
        }

        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (s in stops) {
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
                text = tsFmt.format(Date(s.startedAt))
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            })
            tile.addView(TextView(ctx).apply {
                text = "%.5f, %.5f".format(s.lat, s.lon)
                setTextColor(0x88FFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                typeface = android.graphics.Typeface.MONOSPACE
            })
            val resolved = s.placeName ?: "Resolving…"
            tile.addView(TextView(ctx).apply {
                text = resolved
                setTextColor(0xFFFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            root.addView(tile)
        }
        return scroll
    }

    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MapsStopsFragment() }
}
