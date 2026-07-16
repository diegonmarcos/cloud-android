package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.Trace

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * Generic placeholder Fragment for any of the 6 sections. Created with
 * [forSection] using a sectionId string ("mail", "feed", "chat", "cal",
 * "vault", "wg"). Each instance shows the section label + the drawer's
 * currently-selected child label (set later from MainActivity).
 *
 * Real per-section Fragments will eventually live in libs:<x>/ (e.g.
 * MailFragment in libs:mail) — this stub stays around as the default
 * for any section not yet backed by a real module.
 */
class SectionFragment : Fragment(R.layout.fragment_section_placeholder) {

    init { setHasOptionsMenu(true) }

    private val sectionId: String get() = requireArguments().getString(ARG_SECTION_ID) ?: ""
    private val sectionLabel: String get() = requireArguments().getString(ARG_SECTION_LABEL) ?: ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Trace.d("SectionFragment", "onViewCreated section=$sectionId label=$sectionLabel")
        view.findViewById<TextView>(R.id.section_title).text = sectionLabel
        view.findViewById<TextView>(R.id.section_subtitle).text =
            "Placeholder for libs:$sectionId — real UI lands when the module grows code."
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.section_top, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val name = when (item.itemId) {
            R.id.action_search   -> "Search"
            R.id.action_refresh  -> "Refresh"
            R.id.action_settings -> "Settings"
            else -> return super.onOptionsItemSelected(item)
        }
        Toast.makeText(requireContext(), "$sectionLabel → $name", Toast.LENGTH_SHORT).show()
        return true
    }

    companion object {
        private const val ARG_SECTION_ID    = "section_id"
        private const val ARG_SECTION_LABEL = "section_label"

        fun forSection(id: String, label: String) = SectionFragment().apply {
            arguments = bundleOf(ARG_SECTION_ID to id, ARG_SECTION_LABEL to label)
        }
    }
}
