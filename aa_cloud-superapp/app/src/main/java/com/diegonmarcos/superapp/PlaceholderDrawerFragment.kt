package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * Generic per-section drawer placeholder shown for sections that haven't yet
 * supplied their own drawer fragment in their libs:<x>/ module. Section
 * label injected via arguments.
 */
class PlaceholderDrawerFragment : Fragment(R.layout.fragment_placeholder_drawer) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.placeholder_label).text =
            requireArguments().getString(ARG_LABEL) ?: ""
    }
    companion object {
        private const val ARG_LABEL = "label"
        fun newInstance(label: String) = PlaceholderDrawerFragment().apply {
            arguments = bundleOf(ARG_LABEL to label)
        }
    }
}
