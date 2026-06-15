package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Empty state shown in the right-hand DETAIL pane of the tablet
 * master-detail layout before the user has opened a page from the master
 * (section) grid. Pure code-built view — no layout XML — so it carries no
 * chrome and just prompts the user toward the master pane. Phones never
 * instantiate this (they use single-pane push navigation).
 */
class DetailPlaceholderFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            text = "Select an item"
            textSize = 15f
            setTextColor(0x66FFFFFF)
            gravity = Gravity.CENTER
        }
        return FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
        }
    }

    companion object {
        fun newInstance() = DetailPlaceholderFragment()
    }
}
