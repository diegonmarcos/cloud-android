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
 * MyTrips → Dashboard page. Headline rollups from the local MyMaps
 * tracker DB. Read-only; refreshes on every onResume.
 *
 * Sections:
 *   • Headline counters — Stops, Cities, Countries, Days tracked.
 *   • Today      — list of cities visited today (de-duped).
 *   • This week  — same for last 7 days.
 *   • Last month — same for last 30 days.
 *   • Most-visited cities (last 5y) — top 10.
 *
 * Push 3.5 can add charts (chart.js / MPAndroidChart) — for now the
 * text-only dashboard exposes the data and lets the user feel the
 * shape of the dataset.
 */
class MytripsDashboardFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)
        renderInto(ctx, root)
        return scroll
    }

    private fun renderInto(ctx: android.content.Context, root: LinearLayout) {
        val now = System.currentTimeMillis()
        val from5y  = now - 5L * 365L * 24L * 3600L * 1000L
        val from30d = now - 30L      * 24L * 3600L * 1000L
        val from7d  = now - 7L       * 24L * 3600L * 1000L
        val fromToday = startOfDayMs(now)

        val db = MapsDb.get(ctx)
        val all5y = db.stopsBetween(from5y, now)
        if (all5y.isEmpty()) {
            root.addView(header(ctx, "MyTrips"))
            root.addView(caption(ctx, "No Stops yet. Open MyMaps → Tracker → Start to begin logging your movements."))
            return
        }

        // ── Headline counters ─────────────────────────────────────
        val cities    = all5y.mapNotNull { it.city }.toSet()
        val countries = all5y.mapNotNull { it.country }.toSet()
        val days      = all5y.map {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.startedAt))
        }.toSet()

        root.addView(header(ctx, "MyTrips"))
        root.addView(grid4(ctx,
            "Stops" to all5y.size.toString(),
            "Cities" to cities.size.toString(),
            "Countries" to countries.size.toString(),
            "Days" to days.size.toString(),
        ))
        root.addView(caption(ctx, "Last 5 years"))

        // ── Today / Week / Month windows ──────────────────────────
        section(ctx, root, "Today",      all5y.filter { it.startedAt >= fromToday })
        section(ctx, root, "Last 7 days", all5y.filter { it.startedAt >= from7d })
        section(ctx, root, "Last 30 days", all5y.filter { it.startedAt >= from30d })

        // ── Top cities ───────────────────────────────────────────
        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(subhead(ctx, "Most-visited cities — last 5y"))
        val topCities = all5y.mapNotNull { it.city }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(10)
        if (topCities.isEmpty()) {
            root.addView(caption(ctx, "No cities resolved yet — enrichment runs as Stops accumulate."))
        } else {
            for ((city, n) in topCities) {
                root.addView(kv(ctx, city, "$n stops"))
            }
        }
    }

    /** Render a sub-section listing the de-duped city names in [stops]. */
    private fun section(
        ctx: android.content.Context,
        root: LinearLayout,
        title: String,
        stops: List<MapsDb.RichStop>,
    ) {
        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(subhead(ctx, title))
        if (stops.isEmpty()) {
            root.addView(caption(ctx, "—"))
            return
        }
        // De-dupe cities, preserve order seen (DESC time).
        val cities = LinkedHashSet<String>()
        for (s in stops) s.city?.let { cities.add(it) }
        if (cities.isEmpty()) {
            root.addView(caption(ctx, "${stops.size} stops — cities still resolving"))
            return
        }
        root.addView(TextView(ctx).apply {
            text = cities.joinToString("  ·  ")
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        root.addView(caption(ctx, "${stops.size} stops"))
    }

    /** 2x2 grid of headline counter tiles. */
    private fun grid4(ctx: android.content.Context, vararg cells: Pair<String, String>): View {
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val m = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, m, 0, m) }
        }
        for (row in cells.toList().chunked(2)) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, v) in row) r.addView(counterTile(ctx, k, v))
            grid.addView(r)
        }
        return grid
    }

    private fun counterTile(ctx: android.content.Context, k: String, v: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
        setBackgroundColor(0x18FFFFFFL.toInt())
        val m = dp(ctx, 4)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(m, m, m, m) }
        addView(TextView(ctx).apply {
            text = v
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        })
        addView(TextView(ctx).apply {
            text = k
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
    }

    private fun kv(ctx: android.content.Context, k: String, v: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = dp(ctx, 8); setPadding(0, pad, 0, pad)
        addView(TextView(ctx).apply {
            text = k
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(ctx).apply {
            text = v
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        })
    }

    private fun startOfDayMs(nowMs: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun header(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun subhead(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, 0, 0, dp(ctx, 4))
    }
    private fun caption(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
    }
    private fun spacer(ctx: android.content.Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MytripsDashboardFragment() }
}
