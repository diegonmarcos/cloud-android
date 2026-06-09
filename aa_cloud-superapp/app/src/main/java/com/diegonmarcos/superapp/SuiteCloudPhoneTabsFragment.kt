package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout

/**
 * Suite section host — Cloud | Phone tabs at the top, swap child below.
 *
 *   • Cloud → the existing tile-group grid (GroupedTilesFragment), no
 *             behaviour change from the prior section page.
 *   • Phone → SuitePhoneAppsFragment — flat 6-col grid of the curated
 *             apps listed in build.json::sections[id=suite].phone_apps.
 *
 * Mirrors the AppDrawerSheetFragment's Cloud | Phone TabLayout pattern
 * for muscle-memory consistency: same MODE_FIXED, same lavender accent,
 * same default tab (Cloud) on entry.
 */
class SuiteCloudPhoneTabsFragment : Fragment() {

    private var hostId: Int = 0

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val tabs = TabLayout(ctx).apply {
            tabMode = TabLayout.MODE_FIXED
            setSelectedTabIndicatorColor(0xFFE9D8FD.toInt())
            setTabTextColors(0x88FFFFFF.toInt(), 0xFFFFFFFFL.toInt())
            addTab(newTab().setText("Cloud"))
            addTab(newTab().setText("Phone"))
            AppTabsStyle.apply(this)
        }
        val host = FrameLayout(ctx).apply {
            id = R.id.suite_tabs_host
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        hostId = host.id
        root.addView(tabs)
        root.addView(host)

        if (s == null && childFragmentManager.findFragmentById(host.id) == null) {
            showTab(0)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                Haptics.tap(tabs)
                showTab(tab.position)
            }
            override fun onTabReselected(tab: TabLayout.Tab) {}
            override fun onTabUnselected(tab: TabLayout.Tab) {}
        })
        return root
    }

    private fun showTab(position: Int) {
        val frag: Fragment = if (position == 1)
            SuitePhoneAppsFragment.newInstance()
        else
            GroupedTilesFragment.newInstance("suite")
        childFragmentManager.beginTransaction()
            .replace(hostId, frag)
            .commitAllowingStateLoss()
    }

    companion object { fun newInstance() = SuiteCloudPhoneTabsFragment() }
}
