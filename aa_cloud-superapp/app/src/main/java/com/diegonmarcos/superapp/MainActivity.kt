package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    com.diegonmarcos.superapp.devcontrol.DevControlBridge.ActivityHost,
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

            // Launcher-icon shortcut (long-press launcher icon) carries a
            // shortcut_action extra. Same target grammar as tile clicks.
            handleShortcutIntent(intent)

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
            installHomeLongPressFan()

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

    /**
     * Long-press of the Home bottom-nav slot opens the [HomeFanMenu] —
     * a folder-widget-style overlay with two icons (Configs, Tabs).
     * BottomNavigationView doesn't have a per-item long-press hook,
     * so we install a touch listener that watches for a press lasting
     * >500ms inside the home tab's hit-rect and, when it fires, throws
     * up the fan. The normal tap path still works because we never
     * consume the event (return false).
     */
    private fun installHomeLongPressFan() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: Runnable? = null
        var firedFor: View? = null
        bottomNav.setOnTouchListener { _, ev ->
            val homeView = findHomeNavView() ?: return@setOnTouchListener false
            val inHome = ev.x in homeView.x..(homeView.x + homeView.width)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (inHome) {
                    pending?.let { handler.removeCallbacks(it) }
                    firedFor = null
                    val r = Runnable {
                        if (firedFor == null) {
                            firedFor = homeView
                            homeView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            HomeFanMenu.show(homeView) { target ->
                                onTileClicked(target)
                            }
                        }
                    }
                    pending = r
                    handler.postDelayed(r, 480)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = null
                }
            }
            false   // never consume — normal tap still fires
        }
    }

    /** Best-effort hit-test: return the View of the Home menu item
     *  inside BottomNavigationView, or null if the internal hierarchy
     *  isn't what we expect. */
    private fun findHomeNavView(): View? {
        val menuView = bottomNav.getChildAt(0) as? ViewGroup ?: return null
        val items = bottomNav.menu
        for (i in 0 until items.size()) {
            if (items.getItem(i).itemId == R.id.nav_home) {
                return menuView.getChildAt(i)
            }
        }
        return null
    }

    // ── bottom nav ────────────────────────────────────────────────────────

    private fun onBottomNavPicked(item: MenuItem): Boolean {
        if (suppressBottomNavReentry) return true
        val id = sectionIdForNavId(item.itemId) ?: return false
        // Gemini-like haptic gesture: START on press, two LOW_TICK pulses
        // while the screen swaps, END when it settles. Same pattern
        // regardless of which nav slot was tapped.
        val anchor = bottomNav
        Haptics.gestureStart(anchor)
        anchor.postDelayed({ Haptics.segmentTick(anchor) }, 100)
        anchor.postDelayed({ Haptics.segmentTick(anchor) }, 180)
        anchor.postDelayed({ Haptics.gestureEnd(anchor) }, 260)
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
                    // If the page declares an `action`, the tile id IS
                    // that action so onTileClicked routes it directly
                    // (http://, intent://, action:check_updates, stub:…
                    // — same grammar tiles use). Else: open the sub-page
                    // via the normal section/page dispatcher.
                    TileGridFragment.Tile(
                        id      = if (p.action.isNotBlank()) p.action else "page:${p.id}",
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
        // Pad the SHELL LinearLayout itself with status-bar inset (top) +
        // gesture-nav inset (bottom). The LinearLayout then represents
        // the SAFE AREA — the AppBarLayout sits at the top of it, the
        // bottom_nav_island sits at the bottom of it. Neither island can
        // be eaten by a system bar because they're physically constrained
        // inside the padded region.
        val shell = findViewById<View>(R.id.shell_linear)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            bottomSystemInset = sys.bottom
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
        findViewById<View>(R.id.toolbar_island).visibility =
            if (ownsToolbar) View.GONE else View.VISIBLE
        // Hide the WHOLE bottom-nav-island card when a fragment owns its
        // own bottom chrome, so the fragment_container's
        // bottom_toTopOf constraint collapses to parent.bottom and the
        // fragment fills the full height (ConstraintLayout handles
        // GONE views by reducing them to their constrained edge).
        findViewById<View>(R.id.bottom_nav_island).visibility =
            if (ownsBottom) View.GONE else View.VISIBLE
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

    /**
     * Launch a URI tile target, honouring the `intent://#Intent;…;end`
     * convention's `S.browser_fallback_url` extra. The standard Android
     * stack does NOT auto-fall-back when the target app isn't installed
     * — Chrome implements that itself. We replicate that behaviour:
     *
     *   1. Parse the URI.
     *   2. Try startActivity.
     *   3. On ActivityNotFoundException (or any throwable) check for
     *      `browser_fallback_url` String extra and launch that as a
     *      plain ACTION_VIEW.
     *   4. If still nothing fires, surface the failure as a snack so
     *      taps don't fail silently.
     */
    private fun launchUri(uri: String) {
        if (uri.isBlank()) return

        // Custom scheme: app://<package>?fallback=<encoded-url>
        // → open the package's main launcher activity via PackageManager.
        //   getLaunchIntentForPackage. This actually fires the app's home
        //   screen (not ACTION_VIEW which most apps don't filter for).
        //   Falls back to the URL via ACTION_VIEW if the app isn't
        //   installed.
        if (uri.startsWith("app://")) {
            val u = android.net.Uri.parse(uri)
            val pkg = u.host ?: u.authority
            val fallback = u.getQueryParameter("fallback")
            val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launch); return }
            }
            if (!fallback.isNullOrBlank()) {
                val fb = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(fallback),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                runCatching { startActivity(fb); return }
            }
            findViewById<View>(R.id.fragment_container).snack("App not installed: $pkg")
            return
        }

        // http(s), obsidian://, intent://… — Intent.parseUri.
        val parsed: android.content.Intent? = runCatching {
            android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
        }.getOrNull()
        if (parsed == null) {
            findViewById<View>(R.id.fragment_container).snack("Bad URI: $uri")
            return
        }
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val primary = runCatching { startActivity(parsed); true }.getOrDefault(false)
        if (primary) return
        if (!fallback.isNullOrBlank()) {
            val fb = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(fallback),
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            val ok = runCatching { startActivity(fb); true }.getOrDefault(false)
            if (ok) return
        }
        findViewById<View>(R.id.fragment_container).snack("No app handles: $uri")
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
            // handed off via launchUri so the browser_fallback_url extra
            // (intent:// convention) actually fires when the target app
            // isn't installed.
            tileId.startsWith("http://") || tileId.startsWith("https://") ||
                (tileId.contains("://") && !tileId.startsWith("section:") &&
                 !tileId.startsWith("page:") && !tileId.startsWith("action:") &&
                 !tileId.startsWith("stub:")) -> launchUri(tileId)
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
            actionType.contains("://") -> launchUri(actionType)
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
        if (item.itemId == R.id.action_search) {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_up,  R.anim.fade_out,
                    R.anim.fade_in,      R.anim.slide_out_down,
                )
                .add(R.id.fragment_container, SearchSheetFragment.newInstance(),
                    SearchSheetFragment.BACK_STACK_TAG)
                .addToBackStack(SearchSheetFragment.BACK_STACK_TAG)
                .commit()
            return true
        }
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: android.content.Intent?) {
        val target = intent?.getStringExtra("shortcut_action") ?: return
        if (target == "action:open_search") {
            // No section: just slide up the SearchSheet.
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_up,  R.anim.fade_out,
                    R.anim.fade_in,      R.anim.slide_out_down,
                )
                .add(R.id.fragment_container, SearchSheetFragment.newInstance(),
                    SearchSheetFragment.BACK_STACK_TAG)
                .addToBackStack(SearchSheetFragment.BACK_STACK_TAG)
                .commit()
        } else {
            onTileClicked(target)
        }
        intent.removeExtra("shortcut_action")
    }

    // ── DevControlBridge.ActivityHost ────────────────────────────────────

    override fun onResume() {
        super.onResume()
        com.diegonmarcos.superapp.devcontrol.DevControlBridge.register(this)
    }

    override fun onPause() {
        com.diegonmarcos.superapp.devcontrol.DevControlBridge.unregister(this)
        super.onPause()
    }

    override fun onTileFromServer(target: String) = onTileClicked(target)

    override fun onActionFromServer(actionType: String) {
        dispatchHomeAction(actionType)
    }

    override fun firePresetHaptic(preset: String) {
        val v = bottomNav
        when (preset) {
            "tick"          -> Haptics.segmentTick(v)
            "start"         -> Haptics.gestureStart(v)
            "end"           -> Haptics.gestureEnd(v)
            "gemini_stream" -> {
                Haptics.gestureStart(v)
                v.postDelayed({ Haptics.segmentTick(v) }, 100)
                v.postDelayed({ Haptics.segmentTick(v) }, 180)
                v.postDelayed({ Haptics.gestureEnd(v) }, 260)
            }
        }
    }

    override fun stateSnapshot(): Map<String, String> = mapOf(
        "section" to currentSection,
        "label"   to currentLabel,
        "mode"    to currentMode,
    )
}
