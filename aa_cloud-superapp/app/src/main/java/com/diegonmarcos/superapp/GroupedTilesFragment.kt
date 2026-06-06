package com.diegonmarcos.superapp

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

        for (group in groups) {
            col.addView(groupHeader(ctx, group.title))
            col.addView(tileRow(ctx, group.tiles))
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

    /** A row of tiles in 5-column wrap (matches Home Apps groupSize). */
    private fun tileRow(ctx: android.content.Context, tiles: List<Sections.AggTile>): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val cols = 5
        val rows = (tiles.size + cols - 1) / cols
        for (r in 0 until rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            for (c in 0 until cols) {
                val idx = r * cols + c
                if (idx < tiles.size) {
                    row.addView(tileCell(ctx, tiles[idx]))
                } else {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
            }
            wrapper.addView(row)
        }
        return wrapper
    }

    private fun tileCell(ctx: android.content.Context, tile: Sections.AggTile): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = dp(6); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            background = ctx.getDrawable(android.R.drawable.list_selector_background)
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
