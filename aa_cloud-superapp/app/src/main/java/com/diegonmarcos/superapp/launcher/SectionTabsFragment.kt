package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.Collapsible
import com.diegonmarcos.superapp.system.ModePrefs
import com.google.android.material.tabs.TabLayout

/**
 * A section's pages behind ONE tab strip — the launcher's tabbed sections
 * (Suite = Cloud|Phone, Comms/Infos = Apps|Admin). Replaces the hand-written
 * `TabbedSectionFragment` + `SuiteCloudPhoneTabsFragment` pair: the tab list
 * is `build.json::ui.sections[].pages[]`, so no section or page id is spelled
 * out here.
 *
 * Phone — one pane, the selected tab renders into it. Identical to the old
 * two-fragment behaviour.
 *
 * Tablet ([MainActivity.isTwoPane]) — ONE PANE PER PAGE, all rendered at
 * once and side by side: Suite shows Cloud *and* Phone simultaneously. The
 * strip stays put so the chrome reads the same as the phone, and because
 * [TabLayout.MODE_FIXED] + [TabLayout.GRAVITY_FILL] give every tab 1/N of
 * the width against N equal-weight panes, tab *i* sits directly above the
 * pane it names — the strip doubles as each pane's header. Selection then
 * only marks the ACTIVE pane (the one [Collapsible] and the Apps/Admin mode
 * sync follow), since nothing needs swapping.
 *
 * Sections with more pages than [MAX_PANES] never reach here — the nav
 * controller routes them to the icon-rail + detail-pane layout instead.
 */
class SectionTabsFragment : Fragment(), Collapsible {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION_ID).orEmpty()

    /** Stable hosts, one per rendered pane — see `values/ids.xml`. */
    private val paneIds = intArrayOf(
        R.id.section_pane_0, R.id.section_pane_1, R.id.section_pane_2, R.id.section_pane_3,
    )

    /** Index of the tab the user last selected; the pane [Collapsible] talks
     *  to when several are on screen. */
    private var activePane = 0

    /**
     * [Collapsible] — a bottom-nav re-tap lands on this wrapper (it is the
     * visible top fragment), so forward it to the ACTIVE pane's child. Panes
     * the user isn't pointing at keep their own collapse state. Returns the
     * child's consumed-flag (false when it isn't [Collapsible]) so the
     * activity's re-tap handler can still fall back to its default.
     */
    override fun toggleAllCollapsed(): Boolean {
        val host = paneIds.getOrNull(activePane) ?: return false
        return (childFragmentManager.findFragmentById(host) as? Collapsible)
            ?.toggleAllCollapsed() ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val pages = Sections.byId(sectionId)?.pages.orEmpty()
        val twoPane = (activity as? MainActivity)?.isTwoPane() == true
        // One pane per page on a tablet; phones keep the single swapping pane.
        val paneCount = if (twoPane) pages.size.coerceIn(1, MAX_PANES) else 1

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val tabs = TabLayout(ctx).apply {
            pages.forEach { addTab(newTab().setText(it.label)) }
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Liquid-glass pill chrome — the same helper the strip used before,
            // so Suite / Infos / Labs still read as one consistent surface.
            AppTabsStyle.apply(this)
            equaliseTabs(this)
        }

        val panes = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        repeat(paneCount) { i ->
            panes.addView(FrameLayout(ctx).apply {
                id = paneIds[i]
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })
        }

        root.addView(tabs)
        root.addView(panes)

        val start = startIndex(pages)
        activePane = start
        if (paneCount > 1) {
            // Every page is on screen at once — fill each pane from its own
            // page and leave them alone; the tabs only move [activePane].
            pages.take(paneCount).forEachIndexed { i, p -> render(i, p.id) }
        } else {
            // Phone: one pane, so the selected tab is also the rendered page.
            pages.getOrNull(start)?.let { render(0, it.id) }
        }
        tabs.getTabAt(start)?.select()
        // Sync the mode for the landing tab explicitly. The listener below is
        // attached AFTER this, and selecting tab 0 is a no-op anyway, so
        // neither would fire onTabSelected for the page we start on.
        pages.getOrNull(start)?.let { (activity as? MainActivity)?.nav?.syncModeForPage(it.id) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val page = pages.getOrNull(tab.position) ?: return
                activePane = if (paneCount > 1) tab.position.coerceAtMost(paneCount - 1) else 0
                // An Apps/Admin tab also SETS the global mode, so the Home
                // grid, drawer and bottom-nav icon variants follow it. Fired
                // on SELECTION, not at render time: with every pane on screen
                // rendering both would call it twice and the last would win.
                (activity as? MainActivity)?.nav?.syncModeForPage(page.id)
                if (paneCount == 1) render(0, page.id)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        return root
    }

    /** Which tab starts selected: the page a deep link or walk stop asked
     *  for, else the persisted Apps/Admin mode when this section has a page
     *  named for it, else the first page. */
    private fun startIndex(pages: List<Sections.Page>): Int {
        val wanted = arguments?.getString(ARG_INITIAL_PAGE).orEmpty()
            .ifBlank { ModePrefs(requireContext()).mode }
        return pages.indexOfFirst { it.id == wanted }.takeIf { it >= 0 } ?: 0
    }

    /** Commit page [pageId] into pane [index], reusing the nav controller's
     *  page→Fragment routing so a pane shows exactly what opening that page
     *  on a phone would. */
    private fun render(index: Int, pageId: String) {
        val frag = (activity as? MainActivity)?.nav?.pageFragment(sectionId, pageId) ?: return
        childFragmentManager.beginTransaction()
            .replace(paneIds[index], frag)
            .commitAllowingStateLoss()
    }

    /**
     * One width for every tab, sized to seven characters — then shrink the text
     * rather than the tab if that will not fit.
     *
     * MODE_FIXED alone was not enough. It divides the strip evenly only while
     * the content fits; past that the tabs go back to sizing themselves, so a
     * strip reading MSGS / MY-RSS / CLOUD-RSS came out ragged, and Cloud's nine
     * pages made it worse. Ragged tabs also MOVE as you change section, which is
     * the part that actually reads as broken.
     *
     * So the slot is measured, not negotiated: the width of [WIDEST] at the tab
     * font. Seven characters because LNKTREE and CONFIGS are the longest labels
     * in build.json, so every existing tab fits its slot exactly once and the
     * strip is stable across every section.
     *
     * When N slots exceed the strip the FONT gives way, a step at a time down to
     * [MIN_SP], because a smaller word is still readable while a clipped one is
     * not. Only if even the floor overflows does the strip become scrollable —
     * the honest last resort: nothing is hidden, it just no longer fits at once.
     *
     * Runs in post(): the measurement needs the strip's real width, which
     * TabLayout does not have until it has laid its children out.
     */
    private fun equaliseTabs(tabs: TabLayout) {
        // A one-shot LAYOUT listener, not post(): post() on a not-yet-attached
        // view runs on attach, which can still be before the first layout pass —
        // width would be 0 and the whole thing would silently do nothing. This
        // fires only once there is a real width to measure against.
        tabs.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                if (v.width <= 0) return
                v.removeOnLayoutChangeListener(this)
                applyEqualTabs(tabs)
            }
        })
    }

    private fun applyEqualTabs(tabs: TabLayout) {
        val n = tabs.tabCount
        if (n == 0) return
        val avail = tabs.width - tabs.paddingStart - tabs.paddingEnd
        if (avail <= 0) return

        // AppTabsStyle replaced every tab's content with a PILL custom view, and
        // that pill is what the eye sees. Sizing TabLayout's own TabView does
        // nothing visible: MODE_FIXED already makes those equal, while the pill
        // inside stays WRAP_CONTENT and centred, so the strip still reads ragged.
        // The width has to go on the pill.
        val pills = (0 until n).mapNotNull { tabs.getTabAt(it)?.customView }
        if (pills.size != n) return
        val labels = pills.mapNotNull { findLabel(it) }
        if (labels.size != n) return

        val dm = tabs.resources.displayMetrics
        val padPx = PILL_PAD_DP * dm.density * 2      // makePill's 14dp each side
        val marginPx = PILL_MARGIN_DP * dm.density * 2 // and its 3dp each side
        val minPx = MIN_SP * dm.scaledDensity
        // MODE_FIXED hands every TabView an equal share; the pill must fit in one.
        val perTab = avail.toFloat() / n

        val paint = android.text.TextPaint().apply {
            typeface = labels[0].typeface
            letterSpacing = labels[0].letterSpacing   // 0.08 — real width, not nominal
        }
        var sizePx = labels[0].textSize
        var pillPx: Float
        while (true) {
            paint.textSize = sizePx
            pillPx = paint.measureText(WIDEST) + padPx
            if (pillPx + marginPx <= perTab || sizePx <= minPx) break
            sizePx -= dm.density        // ~1dp a step
        }

        // Past the floor there is genuinely no room: scroll rather than clip.
        val overflows = pillPx + marginPx > perTab
        tabs.tabMode = if (overflows) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED

        val width = pillPx.toInt()
        for (i in 0 until n) {
            labels[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
            labels[i].isSingleLine = true
            labels[i].maxLines = 1
            labels[i].ellipsize = android.text.TextUtils.TruncateAt.END
            pills[i].layoutParams = pills[i].layoutParams.apply { this.width = width }
            pills[i].minimumWidth = width
        }
        tabs.requestLayout()
    }

    /** First TextView inside a pill. AppTabsStyle wraps the label in a
     *  LinearLayout, but this walks rather than assuming that shape stays. */
    private fun findLabel(v: View?): android.widget.TextView? = when (v) {
        null -> null
        is android.widget.TextView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findLabel(v.getChildAt(it)) }
        else -> null
    }

    companion object {
        /** Seven characters: LNKTREE and CONFIGS are the longest tab labels in
         *  build.json, so this is the real ceiling rather than a guess. */
        private const val WIDEST = "CONFIGS"
        /** makePill's horizontal padding and margin, per side. */
        private const val PILL_PAD_DP = 14f
        private const val PILL_MARGIN_DP = 3f
        private const val MIN_SP = 9f

        /** Panes we have stable host ids for — see `values/ids.xml`. */
        const val MAX_PANES = 4

        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_INITIAL_PAGE = "initial_page"

        fun newInstance(sectionId: String, initialPage: String = ""): SectionTabsFragment =
            SectionTabsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_INITIAL_PAGE, initialPage)
                }
            }
    }
}
