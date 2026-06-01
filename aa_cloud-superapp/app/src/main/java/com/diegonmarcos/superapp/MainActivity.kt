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
    MailHost,
    SearchOpener {

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
            // Search trigger has moved INTO AppDrawerSheetFragment (the
            // Home Apps page). The toolbar is now hamburger | island
            // | back only, NOT a global search affordance — no
            // setOnClickListener here.

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
            // Use our asymmetric 3-dash hamburger glyph instead of the
            // default animated DrawerArrowDrawable. We still want the
            // toggle's open/close-drawer behaviour, so we attach a
            // navigation click that opens the drawer manually.
            toggle.isDrawerIndicatorEnabled = false
            toolbar.setNavigationIcon(R.drawable.ic_hamburger_asymmetric)
            toolbar.setNavigationOnClickListener {
                Haptics.tap(it)
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
            }
            // Bind the central Dynamic Island label — "{INITIALS} · {Mode}".
            // Updated whenever onPrepareOptionsMenu fires (mode toggle,
            // back-stack change, drawer open) AND after a profile edit.
            refreshDynamicIsland()

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
            bottomNav.setOnItemReselectedListener { item ->
                val tappedSection = sectionIdForNavId(item.itemId)
                Trace.i(TAG, "bnv-reselect: tapped=$tappedSection current=$currentSection")
                // RULE 1 — Home BNV always returns to the 3D Home screen.
                //   Even if BNV's selected id "matches" the visible fragment
                //   (e.g. Tabs/Config left BNV pointing at Home and the
                //   user is in a non-BNV section), Home means Home.
                if (tappedSection == "home" && currentSection != "home") {
                    fireGeminiPattern()
                    goHome()
                    return@setOnItemReselectedListener
                }
                // RULE 2 — Other BNV slots with a different currentSection:
                //   take the user there.
                if (tappedSection != null && tappedSection != currentSection) {
                    fireGeminiPattern()
                    goSection(tappedSection, Sections.byId(tappedSection)?.label ?: tappedSection)
                    return@setOnItemReselectedListener
                }
                // RULE 3 — True same-section re-tap.
                //   • App-drawer-sheet is up → pop the sheet to expose the
                //     3D cube underneath.
                //   • Otherwise → ask the current fragment's Collapsible
                //     handler to fold/unfold its panels.
                Haptics.tap(bottomNav)
                val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
                when (cur) {
                    is AppDrawerSheetFragment, is BusinessCardFragment -> {
                        // Both are home-overlay fragments — popping
                        // their back-stack entry restores the 3D cube
                        // underneath. Use plain pop() so we don't tear
                        // through other back-stack entries.
                        supportFragmentManager.popBackStack()
                    }
                    else -> (cur as? Collapsible)?.toggleAllCollapsed()
                }
            }

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
            installTooltipConsumer()

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
                    // Drawer (left pane) open → it owns ALL gestures inside
                    // it (vertical scroll of its menu, horizontal swipe-to-
                    // close). Don't fight it with cycle-section or
                    // app-drawer-sheet flings; let DrawerLayout handle
                    // everything until it's closed again.
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    val absDx = Math.abs(dx); val absDy = Math.abs(dy)

                    // VERTICAL swipes — open / close the app drawer.
                    if (absDy > absDx * 1.4f && absDy > minSwipePx && Math.abs(vY) > 600f) {
                        val consumed = handleVerticalFling(dy)
                        if (consumed) Haptics.tap(bottomNav)
                        return consumed
                    }

                    // HORIZONTAL swipes — cycle the bottom nav.
                    // Drawer owns left-edge swipes; ignore those.
                    if (e1.x < edgeIgnorePx) return false
                    if (absDx < minSwipePx) return false
                    if (absDx < absDy * 1.4f) return false
                    if (Math.abs(vX) < 600f) return false
                    // Some fragments (WebView in desktop view mode etc.)
                    // need to own horizontal flings — skip the cycle so
                    // the inner content can pan freely.
                    val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
                    if ((cur as? SuppressHorizontalSwipe)?.suppressHorizontalSwipe() == true) {
                        return false
                    }
                    // Horizontal swipe = section change → full Gemini pattern,
                    // matches what tapping the bottom-nav slot would do.
                    fireGeminiPattern()
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
    /**
     * iOS-style continuous press-drag-release on the Home slot:
     *   • Press + hold ≥ 380ms inside the Home tab area → fan opens
     *   • Without lifting, slide finger over Tabs (left) or Configs (right)
     *   • Release ON a bubble → fires its action
     *   • Release elsewhere → dismiss without commit
     *
     * BottomNavigationView's own click dispatcher is preserved for short
     * taps — we only start consuming MotionEvents AFTER the long-press
     * timer fires (return false until then).
     */
    private fun installHomeLongPressFan() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: Runnable? = null
        var fanCtrl: HomeFanMenu.Controller? = null
        var downX = 0f; var downY = 0f

        // BNV's inner BottomNavigationItemView captures touches before our
        // listener on the container fires, so we attach to the Home child
        // directly. post {} ensures the BNV has laid out its menu views.
        bottomNav.post {
            val homeView = findHomeNavView()
            if (homeView == null) {
                Trace.w(TAG, "fan install: no homeView — long-press disabled")
                return@post
            }
            Trace.i(TAG, "fan install: attaching listener to home item view")
            attachHomeFanTouchListener(homeView, handler,
                getPending = { pending }, setPending = { pending = it },
                getFanCtrl = { fanCtrl }, setFanCtrl = { fanCtrl = it },
                getDownX = { downX }, setDownX = { downX = it },
                getDownY = { downY }, setDownY = { downY = it })
        }
    }

    @Suppress("LongParameterList")
    private fun attachHomeFanTouchListener(
        homeView: View,
        handler: android.os.Handler,
        getPending: () -> Runnable?, setPending: (Runnable?) -> Unit,
        getFanCtrl: () -> HomeFanMenu.Controller?, setFanCtrl: (HomeFanMenu.Controller?) -> Unit,
        getDownX: () -> Float, setDownX: (Float) -> Unit,
        getDownY: () -> Float, setDownY: (Float) -> Unit,
    ) {
        homeView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Trace.i(TAG, "fan DOWN x=${ev.x} y=${ev.y}")
                    setDownX(ev.x); setDownY(ev.y)
                    getPending()?.let { handler.removeCallbacks(it) }
                    setFanCtrl(null)
                    val newPending = Runnable {
                        Trace.i(TAG, "fan FIRE — long-press timer reached")
                        homeView.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS)
                        setFanCtrl(HomeFanMenu.show(homeView) { target -> onTileClicked(target) })
                    }
                    setPending(newPending)
                    handler.postDelayed(newPending, 380)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val ctrl = getFanCtrl()
                    if (ctrl != null) {
                        ctrl.updateFinger(ev.rawX, ev.rawY)
                        true
                    } else {
                        if (Math.abs(ev.x - getDownX()) > 140 || Math.abs(ev.y - getDownY()) > 140) {
                            Trace.i(TAG, "fan MOVE — drift cancel dx=${ev.x - getDownX()} dy=${ev.y - getDownY()}")
                            getPending()?.let { handler.removeCallbacks(it) }
                            setPending(null)
                        }
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Trace.i(TAG, "fan UP/CANCEL fanCtrl=${getFanCtrl() != null}")
                    getPending()?.let { handler.removeCallbacks(it) }
                    setPending(null)
                    val c = getFanCtrl()
                    setFanCtrl(null)
                    if (c != null) {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) c.commit() else c.dismiss()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    /** Fallback for OEMs that ignore android:tooltipFrameBackground in
     *  the theme (Samsung One UI). For just the BottomNav item views +
     *  Toolbar action items, register an OnLongClickListener that
     *  returns true → View.performLongClick() then skips the tooltip
     *  branch entirely.
     *
     *  Why this DOESN'T cause the lag we saw earlier: setting
     *  OnLongClickListener doesn't make the framework wait the full
     *  long-press timeout on ACTION_UP — short taps still register
     *  immediately. The earlier lag came from OnGlobalLayoutListener
     *  walking the tree on every layout pass, NOT from this listener
     *  itself. Hooked via OnHierarchyChangeListener so we re-attach
     *  only on actual structural changes (BNV menu rebind, toolbar
     *  invalidate) — fires a handful of times, never per-frame. */
    private fun installTooltipConsumer() {
        val consumer = View.OnLongClickListener {
            // Returning true tells the framework the long-press is
            // handled, so it doesn't fall through to the tooltip path.
            Trace.i(TAG, "tooltip consumer fired on view id=${it.id}")
            true
        }
        fun applyToChildren(vg: ViewGroup) {
            for (i in 0 until vg.childCount) {
                vg.getChildAt(i).setOnLongClickListener(consumer)
            }
        }
        val hookListener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.setOnLongClickListener(consumer)
                Trace.i(TAG, "tooltip-consumer: child added to ${parent?.javaClass?.simpleName}")
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        }
        // BNV: items live inside the menu view (getChildAt(0)).
        bottomNav.post {
            (bottomNav.getChildAt(0) as? ViewGroup)?.let {
                it.setOnHierarchyChangeListener(hookListener)
                applyToChildren(it)
                Trace.i(TAG, "tooltip-consumer: attached to BNV menu view, n=${it.childCount}")
            }
        }
        // Toolbar action items live inside an inner ActionMenuView. We
        // can't easily reach it before the toolbar inflates the menu, so
        // attach via the toolbar itself + walk one level deep on each
        // child-add (the ActionMenuView is itself a direct toolbar child).
        val toolbar = findViewById<View>(R.id.toolbar) as? ViewGroup ?: return
        toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.setOnLongClickListener(consumer)
                if (child is ViewGroup) {
                    child.setOnHierarchyChangeListener(hookListener)
                    applyToChildren(child)
                }
                Trace.i(TAG, "tooltip-consumer: child added to toolbar (${child?.javaClass?.simpleName})")
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        applyToChildren(toolbar)
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

    private val haptHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onBottomNavPicked(item: MenuItem): Boolean {
        if (suppressBottomNavReentry) return true
        val id = sectionIdForNavId(item.itemId) ?: return false
        fireGeminiPattern()
        if (id == "home") goHome() else goSection(id, Sections.byId(id)?.label ?: id)
        return true
    }

    /** Gemini-like rhythm for any section-change action (bottom-nav tap,
     *  bottom-nav re-tap landing on a new section, horizontal swipe):
     *    t=0     press buzz (the click)
     *    ~250ms  PAUSE (thinking…)
     *    t=250…490ms: 4 LOW_TICKs at 80ms intervals — "the answer"
     *    t=560ms end buzz — "answer done"
     *  Posted on Handler(mainLooper) so the runnables persist even if the
     *  listener view goes away mid-transition. */
    private fun fireGeminiPattern() {
        haptHandler.removeCallbacksAndMessages(null)
        val anchor = bottomNav
        Haptics.gestureStart(anchor)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 250)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 330)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 410)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 490)
        haptHandler.postDelayed({ Haptics.gestureEnd(anchor) }, 560)
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
        Trace.i(TAG, "goHome  bs=${supportFragmentManager.backStackEntryCount}")
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
        Trace.i(TAG, "goSection id=$id label=$label bs=${supportFragmentManager.backStackEntryCount}")
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
        // Custom slide-fade animation gives the haptic SEGMENT_TICK
        // pulses something visual to ride on.
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fade_in,  R.anim.fade_out,
                R.anim.fade_in,  R.anim.fade_out,
            )
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
                // Drawer's section pane is a flat list of:
                //   • sub-pages (build.json::ui.sections[X].pages[]) for
                //     regular sections, OR
                //   • the aggregator's tiles + stack-panel titles for
                //     aggregator sections (Suite / Tools / Communication
                //     / Infos), where the page list is empty by design.
                // The chip-row above it is redundant in both cases.
                drawerPageTabs.visibility = View.GONE
                val sec = Sections.byId(currentSection)
                val pages = SectionPages.pagesFor(currentSection)
                val isAggregator = sec?.isAggregator == true
                val frag: Fragment = when {
                    pages.isNotEmpty() || isAggregator ->
                        SectionMenuFragment.newInstance(currentSection)
                    else -> PlaceholderDrawerFragment.newInstance(currentLabel)
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

        // http(s):// → open in OUR internal browser (Tabs section) in
        // DETAIL mode for the tapped URL. Tab is added to TabPrefs
        // inside TabsHostFragment on creation when ARG_OPEN_URL is
        // present.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            currentSection = "tabs"
            currentLabel = Sections.byId("tabs")?.label ?: "Tabs"
            supportActionBar?.title = currentLabel
            syncBottomNav("tabs")
            val frag = com.diegonmarcos.superapp.tabs.TabsHostFragment.newInstance(uri)
            applyChrome(frag)
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.fade_out,
                                      R.anim.fade_in, R.anim.fade_out)
                .replace(R.id.fragment_container, frag)
                .addToBackStack(null)
                .commit()
            return
        }

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
        Trace.i(TAG, "onTileClicked tileId=$tileId")
        Haptics.tap(bottomNav)
        // If the click came from the drawer (SectionMenuFragment tile row,
        // HomeDrawerFragment action row, etc.) close the drawer first so
        // the user sees the content surface, not the drawer overlay.
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
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
            // Drawer "Home Apps" entry → open the same pull-up sheet the
            // home-screen swipe-up gesture shows.
            actionType == "open_home_apps" -> {
                if (currentSection != "home") goHome()
                openAppDrawerSheet()
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
        // If the page declares an `action` in build.json, dispatch via the
        // same grammar tile clicks use (action: / http: / intent: …) —
        // otherwise the drawer opens a "Coming soon" placeholder for what
        // is meant to be an action tile. See Configs ▸ Update.
        val pageAction = Sections.byId(sectionId)?.pages
            ?.firstOrNull { it.id == pageId }?.action.orEmpty()
        if (pageAction.isNotBlank()) {
            onTileClicked(pageAction)
            return
        }
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

    override fun onDrawerBusinessCardOpen() {
        // Close the drawer first so the card surface comes into view,
        // then push BusinessCardFragment onto the content container.
        drawerLayout.closeDrawer(GravityCompat.START)
        val frag = BusinessCardFragment.newInstance()
        applyChrome(frag)
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(R.id.fragment_container, frag)
            .addToBackStack(null)
            .commit()
    }

    /** Update the toolbar Dynamic Island label — reads from ProfilePrefs
     *  + ModePrefs. Cheap; called on every state change that might
     *  affect the displayed values. */
    private fun refreshDynamicIsland() {
        val tv = findViewById<android.widget.TextView>(R.id.dynamic_island_label) ?: return
        val profile = ProfilePrefs(this)
        val modeLabel = if (modePrefs.mode == "admin") "Admin" else "Apps"
        tv.text = "${profile.initials} · $modeLabel"
    }

    override fun onDrawerModeToggle() {
        // Flip global Apps↔Admin. Don't close the drawer — the user just
        // tapped the identity row and may want to see the flip take
        // effect there, and may keep navigating from the drawer.
        Haptics.tap(drawerLayout)
        modePrefs.toggle()
        refreshDynamicIsland()
        // Refresh any visible aggregator so its tiles re-render for the
        // new mode.
        if (currentSection.isNotEmpty()) {
            val sec = Sections.byId(currentSection)
            if (sec?.isAggregator == true) goSection(currentSection, currentLabel)
        }
    }

    // ── toolbar (right-side Back action) ─────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_top, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_back)?.isVisible =
            supportFragmentManager.backStackEntryCount > 0 || currentSection != "home"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_back) {
            // Hierarchical "up": one tap = one level toward Home.
            //   • Any fragments on the back stack? → pop them ALL at once
            //     (the section root is the parent of every nested page,
            //     so going up from a deep detail lands directly at the
            //     section's aggregator/grid, not on intermediate pages).
            //   • Already at a section root (empty back stack)? → Home.
            //   • Already on Home? → no-op (the icon hides via
            //     onPrepareOptionsMenu, but defend the path anyway).
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack(
                    null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE,
                )
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
        if (target == "action:open_search") openSearchSheet()
        else onTileClicked(target)
        intent.removeExtra("shortcut_action")
    }

    /** Single source of truth for "show the search sheet". Used by the
     *  AppDrawerSheet's in-page search bar (via [SearchOpener]) and the
     *  launcher long-press → "Search" shortcut. */
    override fun openSearchSheet() {
        // Don't stack multiple SearchSheets if user double-taps.
        if (supportFragmentManager.findFragmentByTag(SearchSheetFragment.BACK_STACK_TAG) != null) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_up,  R.anim.fade_out,
                R.anim.fade_in,      R.anim.slide_out_down,
            )
            .add(R.id.fragment_container, SearchSheetFragment.newInstance(),
                SearchSheetFragment.BACK_STACK_TAG)
            .addToBackStack(SearchSheetFragment.BACK_STACK_TAG)
            .commit()
    }

    // ── DevControlBridge.ActivityHost ────────────────────────────────────

    override fun onResume() {
        super.onResume()
        com.diegonmarcos.superapp.devcontrol.DevControlBridge.register(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    override fun onPause() {
        com.diegonmarcos.superapp.devcontrol.DevControlBridge.unregister(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener(null)
        super.onPause()
    }

    private fun handleUpdateState(state: com.diegonmarcos.superapp.updater.UpdateProgress.State) {
        // Skip if the activity isn't in a state where it can commit
        // fragment transactions — otherwise we crash with
        // "Can not perform this action after onSaveInstanceState".
        if (supportFragmentManager.isStateSaved) return
        runCatching {
            val tag = UpdateOverlayFragment.TAG
            val existing = supportFragmentManager.findFragmentByTag(tag) as? UpdateOverlayFragment
            when (state) {
                is com.diegonmarcos.superapp.updater.UpdateProgress.State.Idle -> {
                    if (existing != null) {
                        supportFragmentManager.beginTransaction()
                            .remove(existing).commitAllowingStateLoss()
                    }
                }
                else -> {
                    if (existing == null) {
                        val frag = UpdateOverlayFragment.newInstance()
                        supportFragmentManager.beginTransaction()
                            .add(R.id.fragment_container, frag, tag)
                            .commitAllowingStateLoss()
                    } else {
                        existing.applyState(state)
                    }
                    if (state is com.diegonmarcos.superapp.updater.UpdateProgress.State.Done) {
                        findViewById<View>(R.id.fragment_container)?.postDelayed({
                            com.diegonmarcos.superapp.updater.UpdateProgress.reset()
                        }, 1200)
                    }
                }
            }
        }
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
