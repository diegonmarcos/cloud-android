package com.diegonmarcos.superapp

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import kotlin.math.abs

/**
 * Drive-style tile grid. Each tile is a MaterialCardView with a tinted
 * circular icon container + label below. Background + foreground colours
 * cycle through a curated palette (see colors.xml), keyed by tile id so
 * the same tile always lands on the same colour. Click ripple + a quick
 * "press" scale animation give the grid its tactile feel.
 *
 * Generic API: see [Tile]. The Activity dispatches the tile's id string
 * to the matching nav action via [TileClickListener].
 */
class TileGridFragment : Fragment(R.layout.fragment_tile_grid) {

    fun interface TileClickListener {
        fun onTileClicked(tileId: String)
    }

    data class Tile(val id: String, val label: String, @DrawableRes val iconRes: Int)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args  = requireArguments()
        val title = args.getString(ARG_TITLE).orEmpty()
        val ids    = args.getStringArray(ARG_TILE_IDS)    ?: emptyArray()
        val labels = args.getStringArray(ARG_TILE_LABELS) ?: emptyArray()
        val icons  = args.getIntArray(ARG_TILE_ICONS)     ?: IntArray(0)

        view.findViewById<TextView>(R.id.tile_grid_title).text = title

        val empty = view.findViewById<TextView>(R.id.tile_grid_empty)
        val grid  = view.findViewById<LinearLayout>(R.id.grid_container)
        grid.removeAllViews()

        if (ids.isEmpty()) {
            empty.isVisible = true
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        val palette  = tilePalette(requireContext())
        val cols = COLS
        var i = 0
        while (i < ids.size) {
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            var c = 0
            while (c < cols) {
                if (i + c < ids.size) {
                    val tileView = inflater.inflate(R.layout.item_tile, row, false)
                    (tileView.layoutParams as LinearLayout.LayoutParams).apply {
                        width  = 0
                        weight = 1f
                    }
                    bindTile(
                        tileView    = tileView,
                        tileId      = ids[i + c],
                        label       = labels[i + c],
                        iconRes     = icons.getOrNull(i + c)?.takeIf { it != 0 } ?: R.drawable.ic_settings,
                        palette     = palette,
                    )
                    row.addView(tileView)
                } else {
                    val spacer = View(requireContext())
                    spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    row.addView(spacer)
                }
                c++
            }
            grid.addView(row)
            i += cols
        }
    }

    /** Wire one tile: tint icon-circle background + icon foreground from the
     *  palette, set label + click handler with a "press" anim. */
    private fun bindTile(
        tileView: View,
        tileId: String,
        label: String,
        @DrawableRes iconRes: Int,
        palette: List<Pair<Int, Int>>,
    ) {
        val slot = abs(tileId.hashCode()) % palette.size
        val (bg, fg) = palette[slot]

        tileView.findViewById<TextView>(R.id.tile_label).text = label

        val iconBg = tileView.findViewById<FrameLayout>(R.id.tile_icon_bg)
        iconBg.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
        }

        val icon = tileView.findViewById<ImageView>(R.id.tile_icon)
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(fg)

        val press = AnimationUtils.loadAnimation(requireContext(), R.anim.tile_press)
        tileView.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            v.startAnimation(press)
            (activity as? TileClickListener)?.onTileClicked(tileId)
        }
    }

    private fun tilePalette(ctx: android.content.Context): List<Pair<Int, Int>> = listOf(
        ctx.color(R.color.tile_blue_bg)   to ctx.color(R.color.tile_blue_fg),
        ctx.color(R.color.tile_green_bg)  to ctx.color(R.color.tile_green_fg),
        ctx.color(R.color.tile_purple_bg) to ctx.color(R.color.tile_purple_fg),
        ctx.color(R.color.tile_pink_bg)   to ctx.color(R.color.tile_pink_fg),
        ctx.color(R.color.tile_orange_bg) to ctx.color(R.color.tile_orange_fg),
        ctx.color(R.color.tile_teal_bg)   to ctx.color(R.color.tile_teal_fg),
        ctx.color(R.color.tile_amber_bg)  to ctx.color(R.color.tile_amber_fg),
        ctx.color(R.color.tile_indigo_bg) to ctx.color(R.color.tile_indigo_fg),
    )

    @ColorInt
    private fun android.content.Context.color(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        private const val COLS = 3
        private const val ARG_TITLE       = "title"
        private const val ARG_TILE_IDS    = "tile_ids"
        private const val ARG_TILE_LABELS = "tile_labels"
        private const val ARG_TILE_ICONS  = "tile_icons"

        fun newInstance(title: String, tiles: List<Tile>) = TileGridFragment().apply {
            arguments = bundleOf(
                ARG_TITLE       to title,
                ARG_TILE_IDS    to tiles.map { it.id }.toTypedArray(),
                ARG_TILE_LABELS to tiles.map { it.label }.toTypedArray(),
                ARG_TILE_ICONS  to tiles.map { it.iconRes }.toIntArray(),
            )
        }
    }
}
