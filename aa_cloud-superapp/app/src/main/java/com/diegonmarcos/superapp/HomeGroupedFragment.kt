package com.diegonmarcos.superapp

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
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
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
 */
class HomeGroupedFragment : Fragment(R.layout.fragment_home_grouped) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val root = view.findViewById<LinearLayout>(R.id.home_grouped_root)
        root.removeAllViews()

        val inflater = LayoutInflater.from(ctx)
        val palette  = tilePalette(ctx)
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
                getString(R.string.home_group_actions),
                acts.map { Triple("action:${it.actionType}", it.label, it.iconName) },
            )
        }

        // Fixed-height rows (in dp). With 5 cols this keeps icon + label
        // legible at all group sizes. Container ScrollView handles overflow
        // when total content exceeds the visible area.
        val rowHeightPx = (96 * resources.displayMetrics.density).toInt()

        for (bucket in buckets) {
            addGroupHeader(root, inflater, bucket.title)

            // Horizontal-scroll groups: render all tiles in a single
            // HorizontalScrollView strip, each tile sized to 1/COLS of
            // the screen width so the row's first 5 tiles are visible
            // and the rest scroll into view per-tile. A small "→"
            // hint sits below the row to flag that more tiles exist.
            if (bucket.scroll == "horizontal") {
                val tileWidthPx = resources.displayMetrics.widthPixels / COLS -
                    (8 * resources.displayMetrics.density).toInt()
                val hStrip = android.widget.HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx,
                    )
                }
                val stripRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        rowHeightPx,
                    )
                }
                hStrip.addView(stripRow)
                for ((idTile, labelTile, iconTile) in bucket.tiles) {
                    val tileView = inflater.inflate(R.layout.item_tile, stripRow, false)
                    tileView.layoutParams = LinearLayout.LayoutParams(
                        tileWidthPx, ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        val m = (4 * resources.displayMetrics.density).toInt()
                        setMargins(m, m, m, m)
                    }
                    val iconRes = Sections.iconResFor(ctx, iconTile).takeIf { it != 0 }
                        ?: R.drawable.ic_settings
                    bindTile(tileView, idTile, labelTile, iconRes, palette)
                    stripRow.addView(tileView)
                }
                root.addView(hStrip)
                // Small "more →" hint — only when there's actually
                // overflow (more tiles than columns can show), so a
                // 5-tile scroll-flagged group doesn't lie about having
                // more apps than are visible.
                if (bucket.tiles.size > COLS) {
                    root.addView(android.widget.TextView(ctx).apply {
                        text = "swipe → ${bucket.tiles.size} apps"
                        gravity = android.view.Gravity.CENTER
                        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                        setTextColor(0x88FFFFFF.toInt())
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                        val v = (2 * resources.displayMetrics.density).toInt()
                        setPadding(0, v, 0, v * 3)
                    })
                }
                continue
            }

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
                            val m = (4 * resources.displayMetrics.density).toInt()
                            setMargins(m, m, m, m)
                        }
                        val iconRes = Sections.iconResFor(ctx, iconName).takeIf { it != 0 }
                            ?: R.drawable.ic_settings
                        bindTile(tileView, id, label, iconRes, palette)
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

        val press = AnimationUtils.loadAnimation(requireContext(), R.anim.tile_press)
        tileView.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            v.startAnimation(press)
            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tileId)
        }
    }

    private fun tilePalette(ctx: Context): List<Pair<Int, Int>> = emptyList()

    @ColorInt
    private fun Context.col(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        // Data-driven from build.json::ui.tile_columns.
        private val COLS: Int get() = BuildConfig.UI_TILE_COLUMNS
        fun newInstance() = HomeGroupedFragment()
    }
}
