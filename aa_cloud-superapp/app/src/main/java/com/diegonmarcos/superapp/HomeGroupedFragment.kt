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
        // Per-mode icon selection: tiles can declare icon_apps /
        // icon_admin overrides in build.json::ui.home_groups; we pick
        // here so the right glyph lands on screen for the current mode.
        val mode = ModePrefs(ctx).mode

        data class Bucket(val title: String, val tiles: List<Triple<String, String, String>>)

        val buckets = mutableListOf<Bucket>()
        Sections.homeGroups().forEach { g ->
            buckets += Bucket(g.title, g.tiles.map { Triple(it.id, it.label, it.iconForMode(mode)) })
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
