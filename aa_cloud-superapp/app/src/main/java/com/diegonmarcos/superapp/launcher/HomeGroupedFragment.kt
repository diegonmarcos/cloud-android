package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ModePrefs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Grouped Home master view — auto-scales to fit one screen.
 *
 * Layout is a vertical LinearLayout with NO scroll. Each group contributes
 * (header + N tile rows); the rows-container gets layout_weight=N so the
 * available vertical space is shared by *tile row count* across all
 * groups. Headers are wrap_content; rows inside a group are weight=1.
 * Tiles inside a row are weight=1 (width=0) and match_parent height. Net
 * effect: the entire grid always fits between AppBar and BottomNav, and
 * adapts smoothly to phone vs. tablet vs. landscape.
 *
 * Data source: [Sections.homeGroups] from build.json::ui.home_groups, plus
 * a trailing "Actions" group from build.json::ui.home_actions.
 *
 * Tile clicks fan out via [TileGridFragment.TileClickListener] using the
 * same id grammar the rest of the app already understands:
 *   "section:<id>"      switch to that section
 *   "page:<sec>/<page>" deep-link (handled in MainActivity.onTileClicked)
 *   "action:<type>"     app-level action (dispatched in MainActivity)
 *
 * The actual grid-building logic lives in the companion object's
 * [buildInto], so this exact "All Apps" rendering can also be embedded
 * inline by the merged Suite→Cloud page ([GroupedTilesFragment]) instead
 * of navigating here as a separate "more" screen. [HomeGroupedFragment]
 * itself keeps working standalone as the real bottom-nav Home tab.
 */
class HomeGroupedFragment : Fragment(R.layout.fragment_home_grouped) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val root = view.findViewById<LinearLayout>(R.id.home_grouped_root)
        root.removeAllViews()
        buildInto(ctx, LayoutInflater.from(ctx), root)
    }

    companion object {
        // Data-driven from build.json::ui.tile_columns.
        private val COLS: Int get() = BuildConfig.UI_TILE_COLUMNS
        fun newInstance() = HomeGroupedFragment()

        /** Build the grouped "All Apps" grid (build.json::ui.home_groups +
         *  ui.home_actions) into [root]. Shared by the standalone Home tab
         *  (via [onViewCreated]) and by the merged Suite→Cloud page
         *  ([GroupedTilesFragment]), which embeds this exact rendering
         *  inline instead of navigating to a separate "more" screen. */
        fun buildInto(ctx: Context, inflater: LayoutInflater, root: LinearLayout) {
            val palette = tilePalette(ctx)
            // Per-mode icon selection. Section.iconForMode(mode) is the
            // SOURCE OF TRUTH — when a tile points at `section:X`, we look
            // the section up and resolve its mode-aware icon there, so the
            // home_groups tile + the bottom-nav item + the drawer entry all
            // render the SAME glyph for the same mode. For `page:S/P` tiles
            // and free-form tiles the tile's own iconName is used.
            val mode = ModePrefs(ctx).mode
            fun resolveTileIcon(tile: Sections.HomeTile): String {
                if (tile.id.startsWith("section:")) {
                    val sid = tile.id.removePrefix("section:")
                    Sections.byId(sid)?.let { return it.iconForMode(mode) }
                }
                return tile.iconForMode(mode)
            }

            data class Bucket(
                val title: String,
                val tiles: List<Triple<String, String, String>>,
                val scroll: String? = null,
            )

            val buckets = mutableListOf<Bucket>()
            Sections.homeGroups().forEach { g ->
                buckets += Bucket(
                    title  = g.title,
                    tiles  = g.tiles.map { Triple(it.id, it.label, resolveTileIcon(it)) },
                    scroll = g.scroll,
                )
            }
            Sections.homeActions().takeIf { it.isNotEmpty() }?.let { acts ->
                buckets += Bucket(
                    ctx.getString(R.string.home_group_actions),
                    acts.map { Triple("action:${it.actionType}", it.label, it.iconName) },
                )
            }

            // Fixed-height rows (in dp). With 5 cols this keeps icon + label
            // legible at all group sizes. Container ScrollView handles overflow
            // when total content exceeds the visible area.
            // Tightened from 96dp to 76dp to match Suite/GroupedTilesFragment's
            // compact density now that tile_columns is 6 (more tiles per
            // line → each tile is narrower → it can be shorter too without
            // squishing icons / labels).
            val rowHeightPx = (76 * ctx.resources.displayMetrics.density).toInt()

            for (bucket in buckets) {
                addGroupHeader(root, inflater, bucket.title)

                // All groups render as a full multi-row grid (COLS per row,
                // wrapping to as many rows as needed) — every icon visible,
                // row per row. The container ScrollView (fragment_home_grouped,
                // or the merged Suite→Cloud page's ScrollView) handles vertical
                // overflow. The build.json `scroll:"horizontal"` flag is
                // intentionally ignored now: the old single-row
                // HorizontalScrollView strip hid apps behind a left/right scroll.
                val rowsContainer = LinearLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    orientation = LinearLayout.VERTICAL
                }

                var i = 0
                while (i < bucket.tiles.size) {
                    val row = LinearLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx,
                        )
                        orientation = LinearLayout.HORIZONTAL
                        weightSum = COLS.toFloat()
                    }
                    var c = 0
                    while (c < COLS) {
                        if (i + c < bucket.tiles.size) {
                            val (id, label, iconName) = bucket.tiles[i + c]
                            val tileView = inflater.inflate(R.layout.item_tile, row, false)
                            // Tile width = column weight; height = fixed
                            // (matches the row), so labels render at their
                            // intended autoSize range without being squashed.
                            tileView.layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
                            ).apply {
                                val m = (4 * ctx.resources.displayMetrics.density).toInt()
                                setMargins(m, m, m, m)
                            }
                            val iconRes = Sections.iconResFor(ctx, iconName).takeIf { it != 0 }
                                ?: R.drawable.ic_settings
                            bindTile(ctx, tileView, id, label, iconRes, palette)
                            row.addView(tileView)
                        } else {
                            val spacer = View(ctx)
                            spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                            row.addView(spacer)
                        }
                        c++
                    }
                    rowsContainer.addView(row)
                    i += COLS
                }
                root.addView(rowsContainer)
            }
        }

        private fun addGroupHeader(container: LinearLayout, inflater: LayoutInflater, title: String) {
            val header = inflater.inflate(R.layout.item_home_group_header, container, false) as TextView
            header.text = title
            container.addView(header)
        }

        private fun bindTile(
            ctx: Context,
            tileView: View,
            tileId: String,
            label: String,
            iconRes: Int,
            palette: List<Pair<Int, Int>>,
        ) {
            // Plain monochrome look — matches every other tile surface in the
            // app (TileGridFragment, drawer items, action chips). The previous
            // hash-derived colored pill was visually noisy and didn't match
            // the rest of the design system; the white icons + transparent
            // pill let the grid read consistently with the section list.
            tileView.findViewById<TextView>(R.id.tile_label).text = label
            tileView.findViewById<FrameLayout>(R.id.tile_icon_bg).background = null
            val icon = tileView.findViewById<ImageView>(R.id.tile_icon)
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())

            val press = AnimationUtils.loadAnimation(ctx, R.anim.tile_press)
            tileView.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                v.startAnimation(press)
                (ctx as? TileGridFragment.TileClickListener)?.onTileClicked(tileId)
            }
        }

        private fun tilePalette(ctx: Context): List<Pair<Int, Int>> = emptyList()
    }
}
