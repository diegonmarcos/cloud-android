package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.mail.MailPages
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemePrefs

/**
 * Owns launcher navigation POLICY + state, separated from the Activity's view
 * MECHANISM. The Activity implements [NavHost] (fragment swaps, tab/bottom-nav
 * sync, chrome — operations that genuinely belong to the view layer) and this
 * controller orchestrates them: which fragment a section shows, the
 * horizontal-swipe walk-list, section-page routing, and the walk cursor.
 *
 * State here:
 *   • [walkIndex] / [inWalkNav] — the swipe walk-list cursor + its re-entrancy
 *     guard. currentSection/currentLabel stay on the host (their setter has a
 *     view side-effect — the Sirius star) and are read/written via [NavHost].
 */
class LauncherNavController(private val host: NavHost) {

    /** The two headings the Configs grid groups its tiles under. */
    private val GROUP_PAGES = "Pages"
    private val GROUP_ACTIONS = "Actions"

    /** Cursor into [Sections.swipeWalk]; authoritative for swipe stepping,
     *  re-synced when the user navigates by other means (tail of [goSection]). */
    var walkIndex: Int = 0
    /** Guards the walk re-sync from firing while a walk step drives goSection. */
    var inWalkNav: Boolean = false

    fun goHome() {
        val ctx = host.navContext()
        host.currentSection = "home"
        host.currentLabel = ctx.getString(R.string.section_home)
        host.setSectionTitle(host.currentLabel)
        // Cloud-Minimalist-Black launcher → terminal app list; else the 3D cube.
        val themePrefs = LauncherThemePrefs(ctx)
        val homePane: Fragment =
            if (host.isDefaultLauncher() && themePrefs.theme == LauncherTheme.CloudMinimalistBlack)
                MinimalistBlackFragment.newInstance()
            else
                Home3DFragment.newInstance()
        host.swapContent(homePane, clearBackStack = true)
        host.syncBottomNav("home")
        host.syncDrawerTab(0)
        host.invalidateMenu()
        host.applyLauncherChrome()
    }

    /** [initialPage] — land on this page of [id] instead of stopping at the
     *  section's page grid. Used by walk stops and `page:<section>/<page>`
     *  deep links. Blank ⇒ the grid (phones), or the first real page seeded
     *  into the detail pane (tablets). */
    fun goSection(id: String, label: String, initialPage: String = "") {
        if (id == "home") { goHome(); return }
        val ctx = host.navContext()
        host.currentSection = id
        host.currentLabel = label
        host.setSectionTitle(label)
        // App Tabs LRU — skip the apptabs section itself.
        if (id != "apptabs") runCatching {
            host.recordSection(id, label, Sections.byId(id)?.iconName ?: "")
        }

        val section = Sections.byId(id)
        // Aggregators used to fork here into tab hosts (Cloud|Phone, Apps|Admin).
        // Those children are declared `pages` now, so aggregators take the same
        // page-grid branch as every other section and [aggregatorPage] renders
        // whichever one the user picks.
        val content: Fragment = when {
            section == null -> SectionFragment.forSection(id, label)
            // Single-page section (e.g. wg) — the section IS that page: open
            // it directly instead of rendering a pointless 1-tile grid. Opt-in
            // via build.json single_page; action pages still need the grid tap.
            section.singlePage && section.pages.size == 1 && section.pages.first().action.isBlank() -> {
                val pg = section.pages.first()
                if (id != "apptabs") runCatching {
                    host.recordPage(id, pg.id, pg.label, pg.iconName ?: "")
                }
                (SectionPages.pagesFor(id).firstOrNull { it.id == pg.id }?.factory?.invoke())
                    ?: SectionFragment.forSection(id, pg.id)
            }
            section.pages.isNotEmpty() -> {
                // Pages and Actions are shown as two labelled groups, off the
                // same `is_action` flag the bottom star splits its two arcs by.
                // The extras declared on this section's radial node (KDE
                // Connect, Animations, Copy Info) are merged in so the grid and
                // the star list the same actions — declared once in build.json.
                val own = section.pages.map { p ->
                    TileGridFragment.Tile(
                        id = if (p.action.isNotBlank()) p.action else "page:${p.id}",
                        label = p.label,
                        iconRes = p.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                        group = if (p.isAction) GROUP_ACTIONS else GROUP_PAGES)
                }
                val extra = com.diegonmarcos.superapp.onehand.CircularMenu.config().nodes
                    .firstOrNull { it.childKey == id }?.actions.orEmpty()
                    .map { TileGridFragment.Tile(it.target, it.label, Sections.iconResFor(ctx, it.iconName), GROUP_ACTIONS) }
                val actions = own.filter { it.group == GROUP_ACTIONS } + extra
                TileGridFragment.newInstance(
                    title = label,
                    // No actions in this section? Drop the headings entirely —
                    // a lone "PAGES" banner over every other grid is noise.
                    tiles = if (actions.isEmpty()) own.map { it.copy(group = "") }
                            else own.filter { it.group == GROUP_PAGES } + actions)
            }
            section.defaultChildren.isNotEmpty() -> TileGridFragment.newInstance(
                title = label,
                tiles = section.defaultChildren.mapIndexed { i, lbl ->
                    TileGridFragment.Tile(id = "stub:$id:$i", label = lbl, iconRes = 0) })
            else -> SectionFragment.forSection(id, label)
        }
        host.swapContent(content, clearBackStack = true)
        host.syncBottomNav(id)
        host.syncDrawerTab(1)
        host.invalidateMenu()

        // Keep the swipe walk cursor in sync when the user arrives by ANY means
        // other than a swipe. Skipped during walk nav (which sets it itself).
        if (!inWalkNav) {
            val stops = Sections.swipeWalk()
            val idx = stops.indexOfFirst {
                it.sheet == null && it.section == id &&
                    (it.page == null || it.page == initialPage)
            }
            if (idx >= 0) walkIndex = idx
        }

        // Land on a specific page when asked. On tablets, fall back to the
        // section's first real page so the 60% detail pane opens with content
        // instead of the "Select an item" placeholder — the master keeps
        // showing the page grid, which is the whole point of the split.
        val landing = initialPage.ifBlank {
            if (host.isTwoPane())
                section?.pages?.firstOrNull { it.action.isBlank() }?.id.orEmpty()
            else ""
        }
        if (landing.isNotBlank() && section?.pages?.any { it.id == landing } == true) {
            openSectionPage(id, landing)
        }
    }

    fun openSectionPage(sectionId: String, pageId: String, args: Bundle? = null) {
        // Establish the section grid as the back-stack BASE *first*, so Back from
        // this child returns to its parent section (e.g. Configs), not wherever it
        // was launched from (Home, the Home-Apps sheet, the Canopus arc menu).
        // This MUST run before the action dispatch below: an action-page (e.g.
        // Configs ▸ Constellation → action:constellation) otherwise rendered
        // straight over Home — "in the home-screen" — because the early return
        // skipped the base, leaving currentSection="home" so Back went Home and
        // the page never landed in its own section. No-op when already in the
        // section — preserves any existing in-section back stack.
        if (host.currentSection != sectionId) {
            goSection(sectionId, Sections.byId(sectionId)?.label ?: sectionId)
        }
        // Pages that declare an `action` dispatch it instead of opening a fragment.
        val pageAction = Sections.byId(sectionId)?.pages
            ?.firstOrNull { it.id == pageId }?.action.orEmpty()
        if (pageAction.isNotBlank()) { host.dispatchTarget(pageAction); return }
        if (sectionId != "apptabs") runCatching {
            val pageEntry = Sections.byId(sectionId)?.pages?.firstOrNull { it.id == pageId }
            host.recordPage(sectionId, pageId, pageEntry?.label ?: pageId, pageEntry?.iconName ?: "")
        }
        val section = Sections.byId(sectionId)
        val page = section?.pages?.firstOrNull { it.id == pageId }
        val frag = when {
            sectionId == "mail" -> MailPages.fragmentFor(pageId, args)
            section != null && page != null && page.facet -> aggregatorPage(section, page)
            else -> SectionPages.pagesFor(sectionId).firstOrNull { it.id == pageId }
                ?.factory?.invoke() ?: SectionFragment.forSection(sectionId, pageId)
        }
        host.closeDrawerIfOpen()
        // On tablets the page opens in the side-by-side DETAIL pane; the MASTER
        // (section grid) keeps owning the shell chrome, so a ShellOverride page
        // must NOT take it over there. Single-pane phones apply chrome as usual.
        if (!host.isTwoPane()) host.applyChrome(frag)
        host.pushContent(frag)
    }

    /**
     * Render a FACET page (build.json `"facet": true`) — a child of an
     * aggregator section that shows the SECTION's own tile/stack data rather
     * than a [SectionPages] factory. The facet id is the `tiles_<id>` /
     * `stack_<id>` suffix, so which renderer wins is decided purely by which
     * lists build.json declares. Was the `mode`/`tab` fork in [goSection]
     * before pages absorbed both.
     */
    private fun aggregatorPage(section: Sections.Section, page: Sections.Page): Fragment {
        // A facet named after a ModePrefs mode also SETS it, so the Home grid,
        // drawer and bottom-nav icon variants follow the page the user just
        // opened — what the retired `mode:` target used to do.
        if (page.id == "apps" || page.id == "admin") host.applyMode(page.id)
        val title = "${section.label} · ${page.label}"
        return when {
            Sections.aggregatorIsStack(section, page.id) ->
                AggregatorStackFragment.newInstance(section.id, section.label, page.id)
            section.tileGroups.isNotEmpty() -> GroupedTilesFragment.newInstance(section.id)
            else -> {
                val ctx = host.navContext()
                TileGridFragment.newInstance(title, Sections.aggregatorTilesFor(section, page.id)
                    .map { t ->
                        TileGridFragment.Tile(
                            id = t.target, label = t.label,
                            iconRes = Sections.iconResFor(ctx, t.iconName))
                    })
            }
        }
    }

    // ── horizontal-swipe walk-list (build.json::ui.swipe_walk) ───────────
    /** Step the circular walk-list, wrapping. +1 = next (left-swipe). */
    fun walkStep(direction: Int) {
        val stops = Sections.swipeWalk()
        if (stops.isEmpty()) return
        val n = stops.size
        walkIndex = ((walkIndex + direction) % n + n) % n
        navigateWalkStop(stops[walkIndex])
    }

    /** Render one walk stop: a section page (optional `page`) or the
     *  Home-Apps overlay sheet. */
    fun navigateWalkStop(stop: Sections.WalkStop) {
        inWalkNav = true
        try {
            host.closeAppDrawerSheetIfOpen()
            if (stop.sheet != null) {
                if (host.currentSection != "home") goHome()
                host.openAppDrawerSheet(stop.sheet)
            } else {
                val label = Sections.byId(stop.section)?.label ?: stop.section
                goSection(stop.section, label, stop.page.orEmpty())
            }
        } finally {
            inWalkNav = false
        }
        host.tabHaptic()
    }

    /**
     * The view-mechanism surface the controller drives. Implemented by the
     * Activity (where the views, FragmentManager, drawer and chrome live).
     */
    interface NavHost {
        var currentSection: String
        var currentLabel: String
        fun navContext(): Context
        fun isDefaultLauncher(): Boolean
        /** True on tablets (sw600dp) where opened pages render in a side-by-side
         *  DETAIL pane and the section grid (master) stays visible. */
        fun isTwoPane(): Boolean
        fun setSectionTitle(label: String)
        fun swapContent(content: Fragment, clearBackStack: Boolean)
        fun pushContent(content: Fragment)
        fun applyChrome(fragment: Fragment)
        fun applyLauncherChrome()
        fun syncBottomNav(sectionId: String)
        fun syncDrawerTab(index: Int)
        fun invalidateMenu()
        fun openAppDrawerSheet(initialTab: String = "")
        fun closeAppDrawerSheetIfOpen()
        fun closeDrawerIfOpen()
        fun dispatchTarget(target: String)
        fun applyMode(mode: String)
        fun tabHaptic()
        fun recordSection(id: String, label: String, icon: String)
        fun recordPage(sectionId: String, pageId: String, label: String, icon: String)
        fun recordTarget(target: String, label: String, icon: String)
    }
}
