package com.diegonmarcos.superapp

import com.diegonmarcos.superapp.core.Collapsible

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout

/**
 * Wrapper fragment that puts an "Apps · Admin" tab strip above an
 * aggregator section's content. Replaces the legacy drawer-header
 * swap-icon switcher for sections that carry both modes (Infos,
 * Labs / tools). Tab change updates the global [ModePrefs] so other
 * mode-aware surfaces stay in sync, and rebuilds the inner content
 * fragment so the new mode's tiles / stack panels show immediately.
 *
 * Sections without both apps and admin variants (Suite, Comms, …)
 * keep going straight to their content fragment via the existing
 * goSection dispatch — no wrapper, no tabs.
 */
class TabbedSectionFragment : Fragment(), Collapsible {

    /** Container id of the inner child host. Stored so the
     *  bottom-nav re-tap handler can find the live child fragment and
     *  forward [Collapsible.toggleAllCollapsed] to it. The id is
     *  generated at runtime by View.generateViewId() in
     *  [onCreateView]. */
    private var childContainerId: Int = 0

    /** [Collapsible] — bottom-nav re-tap on Infos / Labs now reaches
     *  this wrapper first (since [TabbedSectionFragment] is the
     *  visible top fragment). Delegate to whichever inner child
     *  fragment is currently mounted; if it doesn't implement
     *  Collapsible, the gesture is a no-op. Returns the child's own
     *  consumed-flag (or false when the child isn't Collapsible) so
     *  MainActivity's re-tap handler can decide whether to fall back
     *  to the default behaviour. */
    override fun toggleAllCollapsed(): Boolean {
        val child = childFragmentManager.findFragmentById(childContainerId)
        return (child as? Collapsible)?.toggleAllCollapsed() ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val sectionId = arguments?.getString(ARG_SECTION_ID).orEmpty()
        val label     = arguments?.getString(ARG_LABEL).orEmpty()

        val modePrefs = ModePrefs(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val tabs = TabLayout(ctx).apply {
            addTab(newTab().setText("Apps"))
            addTab(newTab().setText("Admin"))
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Liquid-glass pill chrome — same helper used by Home Apps
            // (Cloud | Phone) so Infos / Labs / Home Apps all read as
            // one consistent surface.
            AppTabsStyle.apply(this)
        }

        val childContainer = FrameLayout(ctx).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        childContainerId = childContainer.id

        root.addView(tabs)
        root.addView(childContainer)

        fun renderChildFor(mode: String) {
            val section = Sections.byId(sectionId) ?: return
            val child: Fragment = when {
                Sections.aggregatorIsStack(section, mode) ->
                    AggregatorStackFragment.newInstance(section.id, label, mode)
                section.tileGroups.isNotEmpty() && section.id == "suite" ->
                    SuiteCloudPhoneTabsFragment.newInstance()
                section.tileGroups.isNotEmpty() ->
                    GroupedTilesFragment.newInstance(section.id)
                else -> {
                    val aggTiles = Sections.aggregatorTilesFor(section, mode).map { t ->
                        TileGridFragment.Tile(
                            id      = t.target,
                            label   = t.label,
                            iconRes = Sections.iconResFor(ctx, t.iconName),
                        )
                    }
                    TileGridFragment.newInstance(label, aggTiles)
                }
            }
            childFragmentManager.beginTransaction()
                .replace(childContainer.id, child)
                .commitAllowingStateLoss()
        }

        // Sync the initial tab selection from the persisted mode.
        val initialMode = modePrefs.mode
        tabs.getTabAt(if (initialMode == "admin") 1 else 0)?.select()
        renderChildFor(initialMode)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val newMode = if (tab.position == 1) "admin" else "apps"
                if (modePrefs.mode != newMode) {
                    modePrefs.mode = newMode
                    // Other surfaces (bottom-nav icons, drawer header
                    // label) read modePrefs.mode — nudge the activity
                    // to re-render its chrome.
                    (activity as? MainActivity)?.notifyModeChanged()
                }
                renderChildFor(newMode)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        return root
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_LABEL      = "label"

        fun newInstance(sectionId: String, label: String): TabbedSectionFragment =
            TabbedSectionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_LABEL, label)
                }
            }
    }
}
