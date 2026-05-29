package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment

/**
 * Generic "tile grid" — the right-pane view shown when the user taps any
 * bottom-nav button or any drawer tab title. Each tile is a square button
 * with an icon + label that emits a `tileId` string to the host Activity.
 *
 * Used for:
 *  - Master Home index   — tiles for every section
 *  - Per-section index   — tiles for that section's sub-pages
 *
 * The Activity dispatches `tileId` to the matching navigation action:
 *   "section:<id>"  → switch to that section
 *   "page:<id>"     → swap to that page Fragment (within current section)
 *
 * Tiles encoded as parallel String arrays so the Fragment survives config
 * changes (Bundle restore) without losing state.
 */
class TileGridFragment : Fragment(R.layout.fragment_tile_grid) {

    /** Implemented by MainActivity. The id encoding is the caller's contract. */
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
                    val tile = inflater.inflate(R.layout.item_tile, row, false)
                    (tile.layoutParams as LinearLayout.LayoutParams).apply {
                        width  = 0
                        weight = 1f
                    }
                    tile.findViewById<TextView>(R.id.tile_label).text = labels[i + c]
                    tile.findViewById<ImageView>(R.id.tile_icon).setImageResource(
                        icons.getOrNull(i + c)?.takeIf { it != 0 } ?: R.drawable.ic_settings,
                    )
                    val id = ids[i + c]
                    tile.setOnClickListener {
                        (activity as? TileClickListener)?.onTileClicked(id)
                    }
                    row.addView(tile)
                } else {
                    // Pad the last row with empty weight=1 spacers so column
                    // widths match the populated rows.
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
