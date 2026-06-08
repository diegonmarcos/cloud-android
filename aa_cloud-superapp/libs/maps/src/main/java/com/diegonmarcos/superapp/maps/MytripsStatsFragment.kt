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
 * MyTrips → Stats page. 5-year rollups grouped by year. Each year row
 * shows: # Stops, # Cities, # Countries, top 3 cities. Cheap to
 * compute from the existing Stop table — no extra DB schema.
 *
 * Push 3.5 can layer in a stacked-bar chart (year × top-N cities) and
 * a country heatmap. For Push 3 the goal is a credible text table.
 */
class MytripsStatsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        val now = System.currentTimeMillis()
        val from5y = now - 5L * 365L * 24L * 3600L * 1000L
        val stops = MapsDb.get(ctx).stopsBetween(from5y, now)

        root.addView(header(ctx, "Stats — last 5 years"))

        if (stops.isEmpty()) {
            root.addView(caption(ctx, "No Stops collected yet."))
            return scroll
        }

        // Group by yyyy via SimpleDateFormat (local TZ).
        val yearFmt = SimpleDateFormat("yyyy", Locale.US)
        val byYear  = stops.groupBy { yearFmt.format(Date(it.startedAt)) }
        for ((year, yearStops) in byYear.entries.sortedByDescending { it.key }) {
            root.addView(yearTile(ctx, year, yearStops))
        }
        return scroll
    }

    private fun yearTile(
        ctx: android.content.Context,
        year: String,
        stops: List<MapsDb.RichStop>,
    ): View {
        val cities    = stops.mapNotNull { it.city }
        val countries = stops.mapNotNull { it.country }.toSet()
        val topCities = cities.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(3)
            .joinToString("  ·  ") { "${it.key} (${it.value})" }

        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
            setBackgroundColor(0x18FFFFFFL.toInt())
            val m = dp(ctx, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, m, 0, m) }
        }
        tile.addView(TextView(ctx).apply {
            text = year
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        })
        tile.addView(TextView(ctx).apply {
            text = "${stops.size} stops  ·  ${cities.toSet().size} cities  ·  ${countries.size} countries"
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        if (topCities.isNotEmpty()) {
            tile.addView(TextView(ctx).apply {
                text = "Top: $topCities"
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
        return tile
    }

    private fun header(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(ctx, 12))
    }
    private fun caption(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
    }
    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MytripsStatsFragment() }
}
