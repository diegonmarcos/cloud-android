package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.mail.MailHost
import com.diegonmarcos.superapp.mail.MailPages

/**
 * Top-level shell.
 *
 * Bottom nav: 5 buttons. Center is **Home** (master index). Tap any button
 * → right pane shows a [TileGridFragment] of that section's sub-pages.
 *
 * Drawer: paginated via two tabs — [Home] and [<currentSection>]. Tapping
 * either drawer tab also lands the right pane on the matching TileGrid:
 * the tab title is itself a link to the section index, mirroring the
 * bottom-nav behaviour.
 *
 * All navigation taxonomy is read from [Sections], which mirrors
 * `build.json::ui.sections`. There is no hardcoded list of sections.
 */
class MainActivity : AppCompatActivity(),
    HomeDrawerFragment.NavigationListener,
    TileGridFragment.TileClickListener,
    MailHost {

    private val TAG = "MainActivity"
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var drawerTabs: TabLayout
    private lateinit var drawerPageTabs: TabLayout

    private var currentSection: String = ""
    private var currentLabel:   String = ""

    /** Re-entrancy guards: both drawerTabs.selectTab() AND
     *  bottomNav.selectedItemId fire their selection listeners. When the
     *  selection change originated from goHome/goSection itself we must
     *  not bounce back into it (Material's setSelectedItemId fires the
     *  listener even on programmatic set → StackOverflowError otherwise). */
    private var suppressTabReentry:       Boolean = false
    private var suppressBottomNavReentry: Boolean = false

    /** Latest gesture/navigation-bar inset captured by the edge-to-edge
     *  listener — applyChrome adds it to the BottomNav clearance so
     *  content doesn't slide under the nav bar. */
    private var bottomSystemInset: Int = 0

    /** Global UI mode (apps | admin). Hot-swaps which aggregator
     *  tile-list renders in Communication/Infos/Suite/Tools. */
    private lateinit var modePrefs: ModePrefs
    private val currentMode: String get() = modePrefs.mode

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.i(TAG, "onCreate enter")
        super.onCreate(savedInstanceState)
        try {
            // Edge-to-edge: content draws beneath the status / nav bars,
            // we pad the AppBar and BottomNav to keep them visible. The
            // theme already declares transparent system bars + light-icon
            // tinting so the bars stay readable.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            applyWindowBlurIfSupported()

            setContentView(R.layout.activity_main)
            modePrefs = ModePrefs(this)
            currentLabel = getString(R.string.section_home)

            val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
            setSupportActionBar(toolbar)

            drawerLayout = findViewById(R.id.drawer_layout)
            bottomNav = findViewById(R.id.bottom_nav)
            drawerTabs = findViewById(R.id.drawer_tabs)
            drawerPageTabs = findViewById(R.id.drawer_page_tabs)

            applyEdgeToEdgeInsets()

            val toggle = ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close,
            )
            drawerLayout.addDrawerListener(toggle)
            toggle.syncState()

            drawerTabs.addTab(drawerTabs.newTab().setText(getString(R.string.drawer_tab_home)))
            drawerTabs.addTab(drawerTabs.newTab().setText(currentLabel))
            drawerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab)   = onDrawerTabPicked(tab.position)
                override fun onTabReselected(tab: TabLayout.Tab) = onDrawerTabPicked(tab.position)
                override fun onTabUnselected(tab: TabLayout.Tab) {}
            })

            drawerPageTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val pages = SectionPages.pagesFor(currentSection)
                    pages.getOrNull(tab.position)?.let { showDrawerSectionPage(it) }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            bottomNav.setOnItemSelectedListener { onBottomNavPicked(it) }

            if (savedInstanceState == null) {
                // Default landing per build.json::ui.default_section.
                val target = Sections.defaultSectionId()
                bottomNav.selectedItemId = idForSectionId(target) ?: R.id.nav_home
            }

            // Re-apply chrome after every back-stack change so that the
            // restored Fragment's ShellOverride takeover (or default) takes
            // effect — runOnCommit would have worked but it's mutually
            // exclusive with addToBackStack, so we use the listener instead.
            supportFragmentManager.addOnBackStackChangedListener {
                supportFragmentManager.findFragmentById(R.id.fragment_container)
                    ?.let { applyChrome(it) }
                // Toolbar's right-side Back action toggles visibility with
                // the back-stack depth; invalidate to redraw.
                invalidateOptionsMenu()
            }

            installNavSwipeGesture()

            Updater.start(applicationContext)
            Trace.i(TAG, "onCreate done")
        } catch (t: Throwable) {
            Trace.e(TAG, "onCreate FAILED", t)
            throw t
        }
    }

    // ── swipe-to-cycle bottom nav ────────────────────────────────────────

    private lateinit var navSwipeGesture: android.view.GestureDetector

    private fun installNavSwipeGesture() {
        val edgeIgnorePx = (24f * resources.displayMetrics.density)
        val minSwipePx   = (100f * resources.displayMetrics.density)
        navSwipeGesture = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onFling(
                    e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                    vX: Float, vY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    val absDx = Math.abs(dx); val absDy = Math.abs(dy)

                    // VERTICAL swipes — open / close the app drawer.
                    if (absDy > absDx * 1.4f && absDy > minSwipePx && Math.abs(vY) > 600f) {
                        return handleVerticalFling(dy)
                    }

                    // HORIZONTAL swipes — cycle the bottom nav.
                    // Drawer owns left-edge swipes; ignore those.
                    if (e1.x < edgeIgnorePx) return false
                    if (absDx < minSwipePx) return false
                    if (absDx < absDy * 1.4f) return false
                    if (Math.abs(vX) < 600f) return false
                    cycleBottomNav(direction = if (dx < 0) +1 else -1)
                    return true
                }
            })
    }

    /** Open the app drawer on swipe-up (only while on Home), close it on
     *  swipe-down. Returns true if the gesture was consumed. */
    private fun handleVerticalFling(dy: Float): Boolean {
        val sheetIsUp = supportFragmentManager.findFragmentByTag(AppDrawerSheetFragment.BACK_STACK_TAG) != null ||
                        supportFragmentManager.backStackEntryCount > 0 &&
                          (0 until supportFragmentManager.backStackEntryCount).any {
                              supportFragmentManager.getBackStackEntryAt(it).name ==
                                  AppDrawerSheetFragment.BACK_STACK_TAG
                          }
        return when {
            dy < 0 && currentSection == "home" && !sheetIsUp -> {
                openAppDrawerSheet(); true
            }
            dy > 0 && sheetIsUp -> {
                supportFragmentManager.popBackStack(
                    AppDrawerSheetFragment.BACK_STACK_TAG,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
                )
                true
            }
            else -> false
        }
    }

    private fun openAppDrawerSheet() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_up,  R.anim.fade_out,
                R.anim.fade_in,      R.anim.slide_out_down,
            )
            .add(R.id.fragment_container, AppDrawerSheetFragment.newInstance(),
                 AppDrawerSheetFragment.BACK_STACK_TAG)
            .addToBackStack(AppDrawerSheetFragment.BACK_STACK_TAG)
            .commit()
    }

    /** Step `direction` positions through the bottom-nav menu, wrapping
     *  at both ends. +1 = next item (left-swipe), -1 = prev (right-swipe). */
    private fun cycleBottomNav(direction: Int) {
        val menu = bottomNav.menu
        val count = menu.size()
        if (count <= 1) return
        var idx = -1
        for (i in 0 until count) {
            if (menu.getItem(i).itemId == bottomNav.selectedItemId) { idx = i; break }
        }
        if (idx < 0) idx = 0
        val next = ((idx + direction) % count + count) % count
        bottomNav.selectedItemId = menu.getItem(next).itemId
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::navSwipeGesture.isInitialized) navSwipeGesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ── bottom nav ────────────────────────────────────────────────────────

    private fun onBottomNavPicked(item: MenuItem): Boolean {
        if (suppressBottomNavReentry) return true
        val id = sectionIdForNavId(item.itemId) ?: return false
        if (id == "home") goHome() else goSection(id, Sections.byId(id)?.label ?: id)
        return true
    }

    private fun sectionIdForNavId(navId: Int): String? = when (navId) {
        R.id.nav_communication -> "communication"
        R.id.nav_infos         -> "infos"
        R.id.nav_home          -> "home"
        R.id.nav_suite         -> "suite"
        R.id.nav_tools         -> "tools"
        else -> null
    }

    private fun idForSectionId(id: String): Int? = when (id) {
        "communication" -> R.id.nav_communication
        "infos"         -> R.id.nav_infos
        "home"          -> R.id.nav_home
        "suite"         -> R.id.nav_suite
        "tools"         -> R.id.nav_tools
        else            -> null
    }

    // ── drawer tab navigation ─────────────────────────────────────────────

    private fun onDrawerTabPicked(position: Int) {
        if (suppressTabReentry) return
        if (position == 0) goHome()
        else {
            // The 2nd tab acts as the current-section "home link". Re-tap
            // also returns the right pane to the section TileGrid.
            goSection(currentSection.ifEmpty { Sections.defaultSectionId() }, currentLabel)
        }
    }

    // ── navigation actions ────────────────────────────────────────────────

    /** Land the right pane on the master Home TileGrid. */
    private fun goHome() {
        currentSection = "home"
        currentLabel = getString(R.string.section_home)
        supportActionBar?.title = currentLabel

        // Home = 3D rotating cube + pull-up hint. The legacy tile grid
        // (sections + actions) is reachable by pulling UP from this
        // fragment — see [AppDrawerSheetFragment]. Both islands remain
        // visible above this surface.
        swapContent(Home3DFragment.newInstance(), clearBackStack = true)

        syncBottomNav("home")
        syncDrawerTab(0)
        invalidateOptionsMenu()
    }

    /** Land the right pane on the given section's TileGrid (or placeholder
     *  if the section has no declared sub-pages). */
    private fun goSection(id: String, label: String) {
        if (id == "home") { goHome(); return }
        currentSection = id
        currentLabel = label
        supportActionBar?.title = label

        val section = Sections.byId(id)
        val content: Fragment = when {
            section == null -> SectionFragment.forSection(id, label)
            // Aggregator: if stack_* is declared for the current mode →
            // scrollable collapsable-card view. Otherwise fall back to the
            // tile-grid (still data-driven via tiles_* in build.json).
            section.isAggregator && Sections.aggregatorIsStack(section, currentMode) ->
                AggregatorStackFragment.newInstance(section.id, label, currentMode)

            section.isAggregator -> {
                val aggTiles = Sections.aggregatorTilesFor(section, currentMode).map { t ->
                    TileGridFragment.Tile(
                        // The tile id is the TARGET so onTileClicked's existing
                        // section:/page:/action: grammar handles it directly.
                        id      = t.target,
                        label   = t.label,
                        iconRes = Sections.iconResFor(this, t.iconName),
                    )
                }
                val titleSuffix = if (currentMode == "admin") " · Admin" else " · Apps"
                TileGridFragment.newInstance(label + titleSuffix, aggTiles)
            }
            section.pages.isNotEmpty() -> TileGridFragment.newInstance(
                title = label,
                tiles = section.pages.map { p ->
                    TileGridFragment.Tile(
                        id      = "page:${p.id}",
                        label   = p.label,
                        iconRes = p.iconName?.let { Sections.iconResFor(this, it) } ?: 0,
                    )
                },
            )
            section.defaultChildren.isNotEmpty() -> TileGridFragment.newInstance(
                title = label,
                tiles = section.defaultChildren.mapIndexed { i, lbl ->
                    TileGridFragment.Tile(
                        id = "stub:$id:$i",
                        label = lbl,
                        iconRes = 0,
                    )
                },
            )
            else -> SectionFragment.forSection(id, label)
        }
        swapContent(content, clearBackStack = true)

        syncBottomNav(id)
        syncDrawerTab(1)
        invalidateOptionsMenu()
    }

    private fun syncBottomNav(sectionId: String) {
        val navId = idForSectionId(sectionId) ?: return
        if (bottomNav.selectedItemId != navId) {
            suppressBottomNavReentry = true
            bottomNav.selectedItemId = navId
            suppressBottomNavReentry = false
        }
    }

    private fun syncDrawerTab(index: Int) {
        if (index == 1) drawerTabs.getTabAt(1)?.text = currentLabel
        if (drawerTabs.selectedTabPosition != index) {
            suppressTabReentry = true
            drawerTabs.getTabAt(index)?.let { drawerTabs.selectTab(it) }
            suppressTabReentry = false
        }
        showDrawerPage(if (index == 0) DrawerPage.HOME else DrawerPage.SECTION)
    }

    private fun swapContent(content: Fragment, clearBackStack: Boolean) {
        if (clearBackStack) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        // applyChrome before commit — runOnCommit is mutually exclusive with
        // addToBackStack and we want a single uniform path. The OnBackStack-
        // ChangedListener installed in onCreate handles re-apply on back.
        applyChrome(content)
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.fragment_container, content)
            .commit()
    }

    /** Glassmorphism: enable window background-blur on API 31+ so the
     *  translucent island cards (toolbar + bottom nav) frost what's behind
     *  them. On older Android the layout still renders as a translucent
     *  rounded panel — just without the blur layer. */
    private fun applyWindowBlurIfSupported() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching {
                window.setBackgroundBlurRadius(40)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                @Suppress("DEPRECATION")
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 20
                }
            }
        }
    }

    /** Edge-to-edge insets handler. Pads the AppBar (top inset → status
     *  bar) and the BottomNav (bottom inset → gesture nav). The
     *  drawer pulls insets onto its own panel via the framework default. */
    private fun applyEdgeToEdgeInsets() {
        val appBar    = findViewById<AppBarLayout>(R.id.app_bar)
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = sys.bottom)
            // BottomNav is taller now (56dp + sys.bottom) — push the
            // fragment_container up by the same amount so the last row
            // of tiles isn't hidden behind the nav.
            bottomSystemInset = sys.bottom
            supportFragmentManager.findFragmentById(R.id.fragment_container)
                ?.let { applyChrome(it) }
            insets
        }
    }

    /**
     * Take-over contract: a content Fragment that implements [ShellOverride]
     * can hide our shell chrome so it renders its own. Default (no interface
     * implementation) keeps everything visible.
     */
    private fun applyChrome(fragment: Fragment) {
        val override = fragment as? ShellOverride
        val ownsToolbar = override?.ownsToolbar() ?: false
        val ownsBottom  = override?.ownsBottomNav() ?: false
        findViewById<AppBarLayout>(R.id.app_bar).visibility =
            if (ownsToolbar) View.GONE else View.VISIBLE
        bottomNav.visibility = if (ownsBottom) View.GONE else View.VISIBLE

        val container = findViewById<View>(R.id.fragment_container)
        val lp = container.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val dp = resources.displayMetrics.density
        // BottomNav clearance = its 56dp + the gesture-nav inset, so the
        // tile grid never slides under the nav bar in edge-to-edge mode.
        lp.bottomMargin = if (ownsBottom) 0 else (56 * dp).toInt() + bottomSystemInset
        container.layoutParams = lp
    }

    // ── drawer pagination (internal pages) ────────────────────────────────

    private enum class DrawerPage { HOME, SECTION }

    private fun showDrawerPage(page: DrawerPage) {
        when (page) {
            DrawerPage.HOME -> {
                drawerPageTabs.visibility = View.GONE
                supportFragmentManager.beginTransaction()
                    .replace(R.id.drawer_content, HomeDrawerFragment.newInstance())
                    .commitAllowingStateLoss()
            }
            DrawerPage.SECTION -> {
                // Drawer's section pane is a flat list of every sub-page
                // (data-driven from build.json::ui.sections[X].pages[]),
                // so the chip-row above it is redundant — keep it hidden.
                drawerPageTabs.visibility = View.GONE
                val pages = SectionPages.pagesFor(currentSection)
                val frag: Fragment = if (pages.isEmpty()) {
                    PlaceholderDrawerFragment.newInstance(currentLabel)
                } else {
                    SectionMenuFragment.newInstance(currentSection)
                }
                supportFragmentManager.beginTransaction()
                    .replace(R.id.drawer_content, frag)
                    .commitAllowingStateLoss()
            }
        }
    }

    private fun bindPageTabs(pages: List<SectionPages.Page>) {
        val needsRebuild = drawerPageTabs.tabCount != pages.size ||
            (0 until drawerPageTabs.tabCount).any { i ->
                drawerPageTabs.getTabAt(i)?.text != pages[i].label
            }
        if (!needsRebuild) return
        drawerPageTabs.removeAllTabs()
        for (p in pages) drawerPageTabs.addTab(drawerPageTabs.newTab().setText(p.label), false)
        drawerPageTabs.getTabAt(0)?.let { drawerPageTabs.selectTab(it) }
    }

    private fun showDrawerSectionPage(page: SectionPages.Page) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.drawer_content, page.factory())
            .commitAllowingStateLoss()
    }

    // ── tile click dispatch ──────────────────────────────────────────────

    override fun onTileClicked(tileId: String) {
        when {
            tileId.startsWith("section:") -> {
                val id = tileId.removePrefix("section:")
                if (id == "home") goHome() else goSection(id, Sections.byId(id)?.label ?: id)
            }
            tileId.startsWith("page:") -> {
                val payload = tileId.removePrefix("page:")
                // Two forms: "<sectionId>/<pageId>" (deep-link from Home
                // grouped tiles) or just "<pageId>" (within current section).
                val parts = payload.split("/", limit = 2)
                if (parts.size == 2) {
                    openSectionPage(parts[0], parts[1], null)
                    return
                }
                val pid = parts[0]
                val frag = SectionPages.pagesFor(currentSection).firstOrNull { it.id == pid }?.factory?.invoke()
                    ?: return
                applyChrome(frag)
                supportFragmentManager.beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack(null)
                    .commit()
            }
            tileId.startsWith("action:") -> dispatchHomeAction(tileId.removePrefix("action:"))
            // Any URI with a scheme — http(s), obsidian://, intent://… — is
            // handed to Intent.parseUri which understands BOTH the simple
            // scheme:host form AND the rich intent://#Intent;…;end form
            // (package targeting + S.browser_fallback_url=…).
            tileId.startsWith("http://") || tileId.startsWith("https://") ||
                (tileId.contains("://") && !tileId.startsWith("section:") &&
                 !tileId.startsWith("page:") && !tileId.startsWith("action:") &&
                 !tileId.startsWith("stub:")) ->
                runCatching {
                    val intent = android.content.Intent.parseUri(
                        tileId, android.content.Intent.URI_INTENT_SCHEME,
                    )
                    startActivity(intent)
                }
            tileId.startsWith("stub:") ->
                findViewById<View>(R.id.fragment_container).snack(tileId)
        }
    }

    /** Action-tile dispatcher — `action_type` values come from
     *  build.json::ui.home_actions[].action_type. URL-shaped action_types
     *  (anything with a `://` separator) hand off to Intent.ACTION_VIEW so
     *  the system picks the installed handler — same scheme dispatch the
     *  tile click router uses. */
    private fun dispatchHomeAction(actionType: String) {
        val anchor = findViewById<View>(R.id.fragment_container)
        when {
            actionType == "check_updates" -> {
                Updater.checkNow(applicationContext)
                anchor.snack(R.string.check_updates_started)
            }
            actionType == "import_configs" -> {
                val frag = ImportConfigsFragment.newInstance()
                applyChrome(frag)
                supportFragmentManager.beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack(null)
                    .commit()
            }
            actionType.contains("://") -> runCatching {
                val intent = android.content.Intent.parseUri(
                    actionType, android.content.Intent.URI_INTENT_SCHEME,
                )
                startActivity(intent)
            }
            else -> anchor.snack("action:$actionType")
        }
    }

    // ── MailHost (libs:mail → shell bridge) ──────────────────────────────

    override fun openMailPage(pageId: String, args: Bundle?) {
        openSectionPage("mail", pageId, args)
    }

    /** Generic section-page navigation — used by drawer menu items and the
     *  MailHost bridge. The page fragment is resolved via the per-section
     *  factory in [SectionPages]; mail pages with args (e.g. MESSAGES carrying
     *  folder_id) go through [MailPages.fragmentFor]. */
    fun openSectionPage(sectionId: String, pageId: String, args: Bundle? = null) {
        if (currentSection != sectionId) {
            currentSection = sectionId
            currentLabel = Sections.byId(sectionId)?.label ?: sectionId
            supportActionBar?.title = currentLabel
            syncBottomNav(sectionId)
            syncDrawerTab(1)
        }
        val frag = when (sectionId) {
            "mail" -> MailPages.fragmentFor(pageId, args)
            else   -> SectionPages.pagesFor(sectionId).firstOrNull { it.id == pageId }
                ?.factory?.invoke()
                ?: SectionFragment.forSection(sectionId, pageId)
        }
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
        applyChrome(frag)
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(R.id.fragment_container, frag)
            .addToBackStack(null)
            .commit()
    }

    // ── HomeDrawerFragment delegate (data-driven from Sections) ──────────

    override fun onDrawerSectionSelected(sectionId: String, label: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        goSection(sectionId, label)
    }

    override fun onDrawerPageSelected(sectionId: String, pageId: String, label: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        when {
            pageId.startsWith("child-") ->
                goSection(sectionId, Sections.byId(sectionId)?.label ?: sectionId)
            // "<parent>/<sub>" compound id from the 2-level drawer. Open the
            // parent page — most parents already list their sub-pages inline
            // (e.g. MailPagePlaceholder for Settings shows all 11 sub-tabs).
            pageId.contains('/') ->
                openSectionPage(sectionId, pageId.substringBefore('/'), null)
            else ->
                openSectionPage(sectionId, pageId, null)
        }
    }

    override fun onDrawerActionSelected(actionType: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        dispatchHomeAction(actionType)
    }

    // ── toolbar (right-side Back action) ─────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_top, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_back)?.isVisible =
            supportFragmentManager.backStackEntryCount > 0 || currentSection != "home"
        // Mode toggle: icon-only — gear (ic_settings) for Admin, 4-square
        // app drawer (ic_mode_apps) for Apps. Distinct from Suite's
        // briefcase (ic_suite). Title becomes the long-press tooltip
        // so the user can still discover what the icon means.
        menu.findItem(R.id.action_mode_toggle)?.let { item ->
            if (currentMode == "admin") {
                item.setIcon(R.drawable.ic_settings)
                item.title = getString(R.string.mode_admin)
            } else {
                item.setIcon(R.drawable.ic_mode_apps)
                item.title = getString(R.string.mode_apps)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_mode_toggle) {
            modePrefs.toggle()
            invalidateOptionsMenu()
            // Rebuild current view so aggregator content refreshes to the new
            // mode. Toolbar label itself IS the feedback — no snackbar/toast.
            if (currentSection.isNotEmpty()) {
                val sec = Sections.byId(currentSection)
                if (sec?.isAggregator == true) goSection(currentSection, currentLabel)
            }
            return true
        }
        if (item.itemId == R.id.action_back) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else if (currentSection != "home") {
                goHome()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
