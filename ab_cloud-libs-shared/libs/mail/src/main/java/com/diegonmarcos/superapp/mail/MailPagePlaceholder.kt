package com.diegonmarcos.superapp.mail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * Generic placeholder for any Mail page. Title + upstream-class hint shown at
 * the top; optional children list is filled in for Settings (11 sub-pages)
 * and More (8 overflow items). Each child row is just a TextView that swaps
 * the host Fragment's content to the corresponding child placeholder.
 *
 * One Fragment class serves all 28 pages (1 main + 11 settings + 8 more + …)
 * — the actual FairEmail UI cherry-picks replace these slot-by-slot.
 */
class MailPagePlaceholder : Fragment(R.layout.fragment_mail_page) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        view.findViewById<TextView>(R.id.page_title).text = args.getString(ARG_TITLE)
        view.findViewById<TextView>(R.id.page_upstream).text =
            "upstream: ${args.getString(ARG_UPSTREAM) ?: "—"}"

        val children = args.getStringArray(ARG_CHILDREN) ?: emptyArray()
        val container = view.findViewById<LinearLayout>(R.id.page_children)
        val inflater = LayoutInflater.from(requireContext())
        for (child in children) {
            // Each child string is "label|upstream"
            val (label, upstream) = child.split('|', limit = 2).let {
                it[0] to (it.getOrNull(1) ?: "")
            }
            val row = inflater.inflate(android.R.layout.simple_list_item_2, container, false)
            row.findViewById<TextView>(android.R.id.text1).text = label
            row.findViewById<TextView>(android.R.id.text2).text = upstream
            row.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(id, newInstance(label, upstream, emptyArray()))
                    .addToBackStack(null)
                    .commit()
            }
            container.addView(row)
        }
    }

    companion object {
        private const val ARG_TITLE    = "title"
        private const val ARG_UPSTREAM = "upstream"
        private const val ARG_CHILDREN = "children"   // "label|upstream" strings

        fun newInstance(title: String, upstream: String, children: Array<String> = emptyArray()) =
            MailPagePlaceholder().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_UPSTREAM to upstream,
                    ARG_CHILDREN to children,
                )
            }
    }
}
