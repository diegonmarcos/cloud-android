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
 * Maps → Timeline page — top-level wrapper that puts a `Daily | Stops |
 * Explored` tab row above a single FrameLayout host. Tab change replaces the
 * host's child fragment.
 *
 *   • Daily     — one row per calendar day showing where the user slept
 *                 that night (see [MapsDailyFragment]). Tapping a row calls
 *                 [openStopsForDay] on this host, switching to Stops filtered
 *                 to that one day.
 *   • Stops     — flat reverse-chrono list of Stops (existing
 *                 [MapsStopsFragment]); all-time by default, or scoped to a
 *                 single day when arrived at via [openStopsForDay]. Tapping a
 *                 row opens the full-screen day map ([MapsDayMapFragment]).
 *   • Explored  — every CITY ever visited (sourced from Daily's per-day
 *                 picks, not raw Stops — keeps the map from being polluted by
 *                 same-city noise), one pin each (all-time, no window) — tap
 *                 a pin for its full day-by-day visit history (see
 *                 [MapsExploredFragment]).
 *
 * Tab labels live in code rather than build.json because there's no
 * other consumer; promoting them to ui.maps_timeline_tabs is fine
 * later but doesn't pay for itself today.
 */
class MapsTimelineTabsFragment : Fragment() {

    private var hostId: Int = 0
    private lateinit var tabs: TabLayout

    /** One-shot day filter consumed by the next Stops-tab render — set by
     *  [openStopsForDay], cleared once [showTab] reads it, so a later manual
     *  tap on the Stops tab header shows the unfiltered all-time list again. */
    private var pendingStopsDayMs: Long? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        tabs = TabLayout(ctx).apply {
            addTab(newTab().setText("Explored"))
            addTab(newTab().setText("Daily"))
            addTab(newTab().setText("Stops"))
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

        if (s == null) showTab(0)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab)   { showTab(tab.position) }
            override fun onTabReselected(tab: TabLayout.Tab) {}
            override fun onTabUnselected(tab: TabLayout.Tab) {}
        })
        return root
    }

    /** Called by a Daily row's tap — switches to Stops (tab 2), scoped to [dayMs]. */
    fun openStopsForDay(dayMs: Long) {
        pendingStopsDayMs = dayMs
        tabs.getTabAt(2)?.select() ?: showTab(2)  // select() no-ops if already on tab 2
    }

    private fun showTab(position: Int) {
        val frag: Fragment = when (position) {
            1 -> MapsDailyFragment.newInstance()
            2 -> {
                val dayMs = pendingStopsDayMs
                pendingStopsDayMs = null
                MapsStopsFragment.newInstance(dayMs)
            }
            else -> MapsExploredFragment.newInstance()  // 0 = Explored, default
        }
        childFragmentManager.beginTransaction()
            .replace(hostId, frag)
            .commitAllowingStateLoss()
    }

    companion object { fun newInstance() = MapsTimelineTabsFragment() }
}
