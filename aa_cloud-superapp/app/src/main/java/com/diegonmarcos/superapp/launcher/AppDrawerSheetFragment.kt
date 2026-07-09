package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.ShimmerBorderView
import com.diegonmarcos.superapp.apps.PhoneAppClassifier
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.search.SearchOpener

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
// libs:browser moved to ea_cloud-browser — no browser imports here.

/**
 * Android-launcher-style "all apps" drawer revealed by pulling up from
 * [Home3DFragment]. Content:
 *   • Top spacer — double the gap to the activity-level search-bar island
 *     above (user feedback: needed breathing room).
 *   • TileGrid of every section + home actions below.
 * Pull-down (or back press) closes the sheet and restores the 3D cube.
 */
class AppDrawerSheetFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_gradient_black_purple)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Search bar — glassmorphism + shimmer chrome reused from the
        //    old activity toolbar. Same fixed-height pattern the original
        //    toolbar used (?attr/actionBarSize on both children) so the
        //    wrap_content FrameLayout doesn't enter a measure cycle and
        //    blow up to fill the whole screen.
        val barHeight = dp(56)
        val searchIsland = FrameLayout(ctx).apply {
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_liquid_glass)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            ).apply { setMargins(dp(12), dp(6), dp(12), dp(10)) }
            isClickable = true; isFocusable = true
        }
        val searchInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val hpad = dp(16)
            setPadding(hpad, 0, hpad, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            )
        }
        // App icon — LEFT.
        searchInner.addView(ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            val sz = dp(24)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
        })
        // Search placeholder — CENTRE (weighted so it takes the gap).
        searchInner.addView(TextView(ctx).apply {
            text = "Search"
            setTextColor(0x99FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        // AI sparkle (Gemini-style 4-point star + accent) — RIGHT.
        searchInner.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ai_sparkle)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(20)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = dp(12) }
        })
        searchIsland.addView(searchInner)
        searchIsland.addView(ShimmerBorderView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            )
        })
        searchIsland.setOnClickListener {
            Haptics.tap(it)
            (activity as? SearchOpener)?.openSearchSheet()
        }
        // addView deferred — bodyTabs (Cloud | Phone) goes ABOVE this so
        // the user picks the surface first, then the shared search +
        // chip strip apply to whichever tab is active.

        // Browser-tab chip strip removed — tabs live in Cloud-Browser (ea_cloud-browser).

        // ── Body tabs (Cloud | Phone) — data-driven from build.json::ui.home_apps_tabs.
        // Cloud = HomeGroupedFragment (cloud-services tile grid derived
        // from build.json::ui.home_groups). Phone = PhoneAppsFragment
        // (Android-launcher folder grid of installed apps, classified by
        // PhoneAppClassifier against build.json::ui.phone_folders).
        // Re-opening the sheet always lands on the first tab (Cloud),
        // matching the user's One UI muscle memory.
        val tabs = HomeAppsTabs.loadFromBuildConfig()
        val bodyTabs = com.google.android.material.tabs.TabLayout(ctx).apply {
            tabMode = com.google.android.material.tabs.TabLayout.MODE_FIXED
            setSelectedTabIndicatorColor(0xFFE9D8FD.toInt())
            setTabTextColors(0x88FFFFFF.toInt(), 0xFFFFFFFFL.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            for (tab in tabs) addTab(newTab().setText(tab.label))
            // Pill-style chrome — matches the browser-tab chip strip below
            // and the broader glassmorphism + lavender language. Applies
            // AFTER addTab so it can iterate the populated tab list.
            AppTabsStyle.apply(this)
        }

        val host = FrameLayout(ctx).apply {
            id = R.id.app_drawer_grid_host
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        // ── Final mount order (top → bottom):
        //   1. Cloud | Phone TabLayout — pick surface first.
        //   2. Search island — shared chrome.
        //   3. Body host — HomeGroupedFragment or PhoneAppsFragment.
        root.addView(bodyTabs)
        root.addView(searchIsland)
        root.addView(host)

        fun showTab(id: String) {
            val frag: androidx.fragment.app.Fragment = when (id) {
                "phone" -> PhoneAppsFragment.newInstance()
                else    -> HomeGroupedFragment.newInstance()  // "cloud" + default
            }
            childFragmentManager.beginTransaction()
                .replace(host.id, frag)
                .commit()
        }

        bodyTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                Haptics.tap(bodyTabs)
                showTab(tabs.getOrNull(tab.position)?.id ?: "cloud")
            }
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        if (s == null && childFragmentManager.findFragmentById(host.id) == null) {
            // Land on the requested tab (default = first / Cloud). When it's
            // not the first tab, drive it through TabLayout.select() so the
            // chip highlight + body swap happen together via onTabSelected;
            // the first tab needs an explicit showTab since select(0) is a
            // no-op (already the default selection).
            val requested = arguments?.getString(ARG_INITIAL_TAB)?.takeIf { it.isNotBlank() }
                ?: tabs.firstOrNull()?.id ?: "cloud"
            val idx = tabs.indexOfFirst { it.id == requested }.coerceAtLeast(0)
            if (idx > 0) bodyTabs.getTabAt(idx)?.select()
            else showTab(tabs.firstOrNull()?.id ?: "cloud")
        }
        return root
    }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Tag used both for the back-stack entry name and for activity-side
         *  presence detection. Don't rename without updating MainActivity. */
        const val BACK_STACK_TAG = "app_drawer"
        private const val ARG_INITIAL_TAB = "initial_tab"

        /** [initialTab] = home_apps_tabs id to open on ("cloud" | "phone").
         *  Blank/unknown → first tab (Cloud), preserving the prior default. */
        fun newInstance(initialTab: String = ""): AppDrawerSheetFragment =
            AppDrawerSheetFragment().apply {
                if (initialTab.isNotBlank()) {
                    arguments = Bundle().apply { putString(ARG_INITIAL_TAB, initialTab) }
                }
            }
    }
}
