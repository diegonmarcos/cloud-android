package com.diegonmarcos.superapp

import android.content.Context
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlin.math.abs

/**
 * Grouped Home view — replaces the legacy flat all-sections TileGrid.
 *
 * Data source: [Sections.homeGroups], itself baked from
 * `build.json::ui.home_groups`. Each group renders as
 *   • An uppercase coloured header (Communication / Infos / Others / C3)
 *   • A 3-column grid of [item_tile] cards (same look as TileGridFragment)
 * Append a trailing group of Home actions (Update tile etc.).
 *
 * Tile clicks fan out to the parent Activity via
 * [TileGridFragment.TileClickListener] so the existing dispatcher
 * (section: / page: / action:) handles them.
 */
class HomeGroupedFragment : Fragment(R.layout.fragment_home_grouped) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val root = view.findViewById<LinearLayout>(R.id.home_grouped_root)
        root.removeAllViews()

        val inflater = LayoutInflater.from(ctx)
        val palette  = tilePalette(ctx)

        Sections.homeGroups().forEach { group ->
            addGroupHeader(root, inflater, group.title)
            addTileGrid(
                container = root,
                inflater  = inflater,
                tiles     = group.tiles.map { Triple(it.id, it.label, it.iconName) },
                palette   = palette,
            )
        }

        // Trailing actions — same pattern as before, but inside the
        // grouped layout so the Home view ends with an "Actions" group.
        val actions = Sections.homeActions()
        if (actions.isNotEmpty()) {
            addGroupHeader(root, inflater, getString(R.string.home_group_actions))
            addTileGrid(
                container = root,
                inflater  = inflater,
                tiles     = actions.map { Triple("action:${it.actionType}", it.label, it.iconName) },
                palette   = palette,
            )
        }
    }

    private fun addGroupHeader(container: LinearLayout, inflater: LayoutInflater, title: String) {
        val header = inflater.inflate(R.layout.item_home_group_header, container, false) as TextView
        header.text = title
        container.addView(header)
    }

    private fun addTileGrid(
        container: LinearLayout,
        inflater: LayoutInflater,
        tiles: List<Triple<String, String, String>>,
        palette: List<Pair<Int, Int>>,
    ) {
        val ctx = requireContext()
        val cols = COLS
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            var c = 0
            while (c < cols) {
                if (i + c < tiles.size) {
                    val (id, label, iconName) = tiles[i + c]
                    val tileView = inflater.inflate(R.layout.item_tile, row, false)
                    (tileView.layoutParams as LinearLayout.LayoutParams).apply {
                        width  = 0
                        weight = 1f
                    }
                    val iconRes = Sections.iconResFor(ctx, iconName).takeIf { it != 0 } ?: R.drawable.ic_settings
                    bindTile(tileView, id, label, iconRes, palette)
                    row.addView(tileView)
                } else {
                    val spacer = View(ctx)
                    spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    row.addView(spacer)
                }
                c++
            }
            container.addView(row)
            i += cols
        }
    }

    private fun bindTile(
        tileView: View,
        tileId: String,
        label: String,
        iconRes: Int,
        palette: List<Pair<Int, Int>>,
    ) {
        val slot = abs(tileId.hashCode()) % palette.size
        val (bg, fg) = palette[slot]

        tileView.findViewById<TextView>(R.id.tile_label).text = label

        tileView.findViewById<FrameLayout>(R.id.tile_icon_bg).background = GradientDrawable().apply {
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
            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tileId)
        }
    }

    private fun tilePalette(ctx: Context): List<Pair<Int, Int>> = listOf(
        ctx.col(R.color.tile_blue_bg)   to ctx.col(R.color.tile_blue_fg),
        ctx.col(R.color.tile_green_bg)  to ctx.col(R.color.tile_green_fg),
        ctx.col(R.color.tile_purple_bg) to ctx.col(R.color.tile_purple_fg),
        ctx.col(R.color.tile_pink_bg)   to ctx.col(R.color.tile_pink_fg),
        ctx.col(R.color.tile_orange_bg) to ctx.col(R.color.tile_orange_fg),
        ctx.col(R.color.tile_teal_bg)   to ctx.col(R.color.tile_teal_fg),
        ctx.col(R.color.tile_amber_bg)  to ctx.col(R.color.tile_amber_fg),
        ctx.col(R.color.tile_indigo_bg) to ctx.col(R.color.tile_indigo_fg),
    )

    @ColorInt
    private fun Context.col(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        private const val COLS = 3
        fun newInstance() = HomeGroupedFragment()
    }
}
