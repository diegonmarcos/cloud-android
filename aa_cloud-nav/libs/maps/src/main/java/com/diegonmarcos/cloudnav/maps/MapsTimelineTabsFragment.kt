package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout

/**
 * Maps → Timeline page — top-level wrapper that puts a `Daily | Stops`
 * tab row above a single FrameLayout host. Tab change replaces the
 * host's child fragment.
 *
 *   • Daily     — one row per calendar day showing where the user slept
 *                 that night (see [MapsDailyFragment]).
 *   • Stops     — flat reverse-chrono list of every Stop in the DB
 *                 (existing [MapsStopsFragment], unchanged).
 *   • Explored  — every place ever visited, pinned once each (all-time, no
 *                 window) — tap a pin for its full visit history (see
 *                 [MapsExploredFragment]).
 *
 * Tab labels live in code rather than build.json because there's no
 * other consumer; promoting them to ui.maps_timeline_tabs is fine
 * later but doesn't pay for itself today.
 */
class MapsTimelineTabsFragment : Fragment() {

    private var hostId: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val tabs = TabLayout(ctx).apply {
            addTab(newTab().setText("Daily"))
            addTab(newTab().setText("Stops"))
            addTab(newTab().setText("Explored"))
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val host = FrameLayout(ctx).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f,
            )
        }
        hostId = host.id
        root.addView(tabs)
        root.addView(host)

        fun show(position: Int) {
            val frag: Fragment = when (position) {
                1    -> MapsStopsFragment.newInstance()
                2    -> MapsExploredFragment.newInstance()
                else -> MapsDailyFragment.newInstance()  // 0 = Daily, default
            }
            childFragmentManager.beginTransaction()
                .replace(hostId, frag)
                .commitAllowingStateLoss()
        }

        if (s == null) show(0)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab)   { show(tab.position) }
            override fun onTabReselected(tab: TabLayout.Tab) {}
            override fun onTabUnselected(tab: TabLayout.Tab) {}
        })
        return root
    }

    companion object { fun newInstance() = MapsTimelineTabsFragment() }
}
