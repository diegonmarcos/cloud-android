package com.diegonmarcos.superapp

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

/**
 * Universal page-content view. Replaces the legacy "Placeholder for libs:X"
 * stub with an actually useful surface backed by build.json sample data.
 *
 *  • Reads samples for the key "<section>/<page>" via [Sections.pageSamples]
 *  • Renders a header (title + subtitle) and a vertical list of cards
 *  • Empty state if no samples are declared yet
 *
 * Each row uses an accent stripe tinted from the same palette as the
 * TileGrid — keeps the visual language consistent across the app.
 */
class PageContentFragment : Fragment(R.layout.fragment_page_content) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val sectionId = args.getString(ARG_SECTION_ID).orEmpty()
        val pageId    = args.getString(ARG_PAGE_ID).orEmpty()
        val title     = args.getString(ARG_TITLE).orEmpty()
        val subtitle  = args.getString(ARG_SUBTITLE).orEmpty()

        view.findViewById<TextView>(R.id.pc_title).text = title
        view.findViewById<TextView>(R.id.pc_subtitle).text = subtitle.ifBlank {
            getString(R.string.page_default_subtitle, sectionId, pageId)
        }

        val list = view.findViewById<LinearLayout>(R.id.pc_list)
        val empty = view.findViewById<MaterialCardView>(R.id.pc_empty_card)
        val emptyHint = view.findViewById<TextView>(R.id.pc_empty_hint)

        val key = "$sectionId/$pageId"
        val samples = Sections.pageSamples(key)
        if (samples.isEmpty()) {
            empty.isVisible = true
            emptyHint.text = getString(R.string.page_empty_hint, key)
            return
        }

        val palette = paletteForContext()
        val inflater = LayoutInflater.from(requireContext())
        samples.forEachIndexed { idx, sample ->
            val row = inflater.inflate(R.layout.item_page_row, list, false)
            row.findViewById<TextView>(R.id.pr_title).text    = sample.title
            row.findViewById<TextView>(R.id.pr_subtitle).text = sample.subtitle
            val accentColor = palette[abs((key + idx).hashCode()) % palette.size]
            row.findViewById<View>(R.id.pr_accent).backgroundTintList =
                ColorStateList.valueOf(accentColor)
            list.addView(row)
        }
    }

    private fun paletteForContext(): IntArray {
        val ctx = requireContext()
        return intArrayOf(
            ContextCompat.getColor(ctx, R.color.tile_blue_fg),
            ContextCompat.getColor(ctx, R.color.tile_green_fg),
            ContextCompat.getColor(ctx, R.color.tile_purple_fg),
            ContextCompat.getColor(ctx, R.color.tile_pink_fg),
            ContextCompat.getColor(ctx, R.color.tile_orange_fg),
            ContextCompat.getColor(ctx, R.color.tile_teal_fg),
            ContextCompat.getColor(ctx, R.color.tile_amber_fg),
            ContextCompat.getColor(ctx, R.color.tile_indigo_fg),
        )
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_PAGE_ID    = "page_id"
        private const val ARG_TITLE      = "title"
        private const val ARG_SUBTITLE   = "subtitle"

        fun newInstance(
            sectionId: String,
            pageId: String,
            title: String,
            subtitle: String = "",
        ) = PageContentFragment().apply {
            arguments = bundleOf(
                ARG_SECTION_ID to sectionId,
                ARG_PAGE_ID    to pageId,
                ARG_TITLE      to title,
                ARG_SUBTITLE   to subtitle,
            )
        }
    }
}
