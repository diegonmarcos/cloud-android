package com.diegonmarcos.cloudnav.configs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.NavShellChild
import com.diegonmarcos.cloudnav.maps.MapsConfigFragment
import com.google.android.material.tabs.TabLayout

/**
 * Configs tab. Two sub-pages via a TabLayout:
 *   • Tracker — the maps GPS tracker controls + providers + calibration
 *               ([MapsConfigFragment], lifted from libs:maps).
 *   • About   — the extensive, full-of-data device/app/permissions/battery/
 *               memory/network page ([DevControlFragment], ported from
 *               Cloud SuperApp's DevControl).
 *
 * Hides the shell search bar (settings page, not a search surface).
 */
class ConfigsFragment : Fragment(), NavShellChild {

    override fun wantsSearchBar(): Boolean = false

    private lateinit var container: FrameLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val tabs = TabLayout(requireContext()).apply {
            tabMode = TabLayout.MODE_FIXED
            addTab(newTab().setText("Tracker"))
            addTab(newTab().setText("About"))
        }
        this.container = FrameLayout(requireContext()).apply { id = View.generateViewId() }
        col.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(this.container, LinearLayout.LayoutParams(MATCH, MATCH))

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        if (savedInstanceState == null) show(0)
        return col
    }

    private fun show(position: Int) {
        val frag: Fragment = if (position == 0) MapsConfigFragment() else DevControlFragment()
        childFragmentManager.beginTransaction()
            .replace(container.id, frag)
            .commit()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
