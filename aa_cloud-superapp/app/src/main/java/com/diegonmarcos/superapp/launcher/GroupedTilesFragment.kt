package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.ui.Haptics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Aggregator section that renders themed sub-groups stacked vertically.
 * Used when [Sections.Section.tileGroups] is non-empty (Suite currently).
 *
 * Layout:
 *   ┌─ Group title ────────────────
 *   │  [tile] [tile] [tile] [tile] [tile]
 *   └──────────────────────────────
 *   ┌─ Next group title ───────────
 *   │  [tile] [tile]
 *   └──────────────────────────────
 *
 * Tile clicks bubble up through the same TileGridFragment.TileClickListener
 * the activity already implements, so deep-link grammar (section: / page: /
 * action: / http(s):) routes identically to the flat aggregator path.
 */
class GroupedTilesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val sectionId = arguments?.getString(ARG_SECTION_ID).orEmpty()
        val section = Sections.byId(sectionId)
        val groups  = section?.tileGroups.orEmpty()

        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFillViewport = true
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(col)

        // Suite is the only section merging in the generic Home-tab "All
        // Apps" grid + a "Recently Used" smart folder below its Quickmarks
        // — other GroupedTilesFragment users (Home, Labs, Configs, …) keep
        // their plain grouped-tiles rendering unchanged.
        if (sectionId == "suite") col.addView(groupHeader(ctx, "Quickmarks"))

        for (group in groups) {
            col.addView(groupHeader(ctx, group.title))
            col.addView(tileRow(ctx, group.tiles))
        }

        if (sectionId == "suite") {
            // ── All Apps — the same grouped-by-purpose rendering the real
            //    Home tab (HomeGroupedFragment) uses, embedded inline
            //    instead of navigating to a separate "more" screen.
            col.addView(groupHeader(ctx, "All Apps"))
            HomeGroupedFragment.buildInto(ctx, inflater, col)

            // ── Smart Folders → Recently Used. Cloud tiles have no
            //    existing pin/favorite/most-used signal the way Phone
            //    apps do — this is the one real per-tile signal
            //    available (RecentCloudTiles, recorded centrally from
            //    MainActivity.onTileClicked), so it's the sole Smart
            //    Folders subsection for now.
            val allTiles = Sections.all().flatMap { it.tileGroups }.flatMap { it.tiles }
                .associateBy { it.target }
            val recent = RecentCloudTiles.recent(ctx).mapNotNull { allTiles[it] }
            if (recent.isNotEmpty()) {
                col.addView(groupHeader(ctx, "Smart Folders"))
                col.addView(groupHeader(ctx, "Recently Used"))
                col.addView(tileRow(ctx, recent))
            }
        }
        return scroll
    }

    private fun groupHeader(ctx: android.content.Context, title: String): View =
        TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(4), dp(12), 0, dp(4))
        }

    /** One horizontally-scrollable strip per group — tiles stay on a
     *  single line regardless of count; the user scrolls right for
     *  overflow. Replaces the previous 5-column wrap so groups like
     *  Suite/Data Apps (6 tiles now) don't break onto a second row. */
    private fun tileRow(ctx: android.content.Context, tiles: List<Sections.AggTile>): View {
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (tile in tiles) row.addView(tileCell(ctx, tile))
        scroll.addView(row)
        return scroll
    }

    private fun tileCell(ctx: android.content.Context, tile: Sections.AggTile): View {
        // Fixed-width cells so the horizontal scroll row shows ~6
        // tiles at a time on a typical phone width (matches Home Apps'
        // tile_columns = 6) and the rest stay reachable by swiping.
        val cellWidth = dp(60)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = dp(6); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(cellWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true; isFocusable = true
            // No cell background — matches TileGridFragment's bare tile
            // look. The previous list_selector_background was the default
            // amber/yellow highlight from the platform list theme, which
            // showed up only on Suite (the sole GroupedTilesFragment user
            // today).
            setOnClickListener {
                Haptics.tap(it)
                (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tile.target)
            }
        }
        val iconRes = Sections.iconResFor(ctx, tile.iconName)
        if (iconRes != 0) {
            cell.addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                val sz = dp(32)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
        }
        cell.addView(TextView(ctx).apply {
            text = tile.label
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        return cell
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SECTION_ID = "section_id"

        fun newInstance(sectionId: String): GroupedTilesFragment = GroupedTilesFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION_ID, sectionId) }
        }
    }
}
