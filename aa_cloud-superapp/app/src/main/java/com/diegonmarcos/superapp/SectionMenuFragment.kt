package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * Drawer's section pane — vertical list of every sub-page in the current
 * section. Mirrors `build.json::ui.sections[X].pages[]` via [Sections].
 *
 * Tapping a row hands off to [MainActivity.openSectionPage] so the right
 * content pane shows that page. Replaces the chip-row + first-page combo
 * we had before: simpler and discoverable — user sees the full sub-page
 * inventory at a glance.
 */
class SectionMenuFragment : Fragment(R.layout.fragment_section_menu) {

    private val sectionId: String get() = requireArguments().getString(ARG_SECTION_ID).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sec = Sections.byId(sectionId)
        view.findViewById<TextView>(R.id.menu_title).text = sec?.label?.uppercase() ?: sectionId

        val container = view.findViewById<LinearLayout>(R.id.menu_container)
        val inflater = LayoutInflater.from(requireContext())
        val pages = SectionPages.pagesFor(sectionId)
        for (page in pages) {
            val row = inflater.inflate(R.layout.item_section_menu, container, false)
            row.findViewById<TextView>(R.id.menu_label).text = page.label
            row.setOnClickListener {
                (activity as? MainActivity)?.openSectionPage(sectionId, page.id, null)
            }
            container.addView(row)
        }
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        fun newInstance(sectionId: String) = SectionMenuFragment().apply {
            arguments = bundleOf(ARG_SECTION_ID to sectionId)
        }
    }
}
