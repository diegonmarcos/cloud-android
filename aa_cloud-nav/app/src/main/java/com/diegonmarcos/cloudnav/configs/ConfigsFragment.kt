package com.diegonmarcos.cloudnav.configs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.maps.MapsConfigFragment
import com.google.android.material.tabs.TabLayout

/**
 * Configs tab. Six sub-pages via a TabLayout:
 *   • Tracker — GPS tracker controls + calibration + export
 *               ([MapsConfigFragment] section=tracker).
 *   • APIs    — Search / Reverse-geocoder / POI provider pickers + API keys
 *               ([MapsConfigFragment] section=apis).
 *   • Update  — the in-app GHCR self-updater ([UpdateConfigFragment]).
 *   • Cache   — real per-mechanism cache size + clear
 *               ([CacheConfigFragment]).
 *   • Layers  — per-layer visual settings, e.g. Terrain exaggeration
 *               ([LayersConfigFragment]).
 *   • About   — the extensive device/app/permissions/battery/memory/network
 *               page ([DevControlFragment], ported from Cloud SuperApp).
 *
 * A settings page, not a search surface — no search bar.
 */
class ConfigsFragment : Fragment() {

    private lateinit var container: FrameLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val tabs = TabLayout(requireContext()).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            addTab(newTab().setText("Tracker"))
            addTab(newTab().setText("APIs"))
            addTab(newTab().setText("Update"))
            addTab(newTab().setText("Cache"))
            addTab(newTab().setText("Layers"))
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
        val frag: Fragment = when (position) {
            0 -> MapsConfigFragment.newInstance(MapsConfigFragment.SECTION_TRACKER)
            1 -> MapsConfigFragment.newInstance(MapsConfigFragment.SECTION_APIS)
            2 -> UpdateConfigFragment()
            3 -> CacheConfigFragment()
            4 -> LayersConfigFragment()
            else -> DevControlFragment()
        }
        childFragmentManager.beginTransaction()
            .replace(container.id, frag)
            .commit()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
