package com.diegonmarcos.superapp

import com.diegonmarcos.superapp.core.SuppressVerticalSwipe

import com.diegonmarcos.superapp.core.SuppressHorizontalSwipe

import com.diegonmarcos.superapp.core.Collapsible

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
import com.diegonmarcos.superapp.wallet.BackHandler
import com.diegonmarcos.superapp.wallet.WalletHost

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
    WalletHost,
    SearchOpener,
    com.diegonmarcos.superapp.apptabs.AppTabsHost {

    /** [AppTabsHost] — card-tap callback from libs:apptabs. Routes a
     *  recorded LRU entry back to its original destination. Sections
     *  go through goSection; pages through openSectionPage; external
     *  apps fire a normal launch intent. */
    override fun onAppTabPicked(entry: com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry) {
        when (entry) {
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.SectionEntry ->
                goSection(entry.sectionId, entry.label)
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.PageEntry ->
                openSectionPage(entry.sectionId, entry.pageId)
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry -> runCatching {
                packageManager.getLaunchIntentForPackage(entry.packageName)?.let { startActivity(it) }
            }
        }
    }

    /** [WalletHost] — delegates straight to the existing drawer-
     *  business-card navigation so there's still only ONE path into
     *  the Virtual Business Card surface, whether the user gets here
     *  via the drawer identity row or by tapping the pinned vCard in
     *  the wallet deck. */
    override fun onOpenVcard() = onDrawerBusinessCardOpen()

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
    /** Real status-bar inset captured from the OS the moment
     *  [applyEdgeToEdgeInsets]'s listener fires. Samsung One UI reports
     *  a taller bar than `android.R.dimen.status_bar_height` (because of
     *  the camera cutout), so the resource lookup under-sizes the launcher
     *  strip's negative topMargin — the strip lands BELOW the bar zone
     *  where the toolbar island covers it. Using this cached real value
     *  in [applyLauncherChrome] keeps the strip flush with y=0. */
    private var topSystemInset: Int = 0

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
            // Force the status + navigation bars to fully transparent at
            // runtime — Samsung One UI ignores the theme attributes
            // (statusBarColor / enforceStatusBarContrast) on some builds
            // and paints a blue-gray default tint over the supposedly-
            // transparent surface. Setting these programmatically wins.
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            applyWindowBlurIfSupported()

            setContentView(R.layout.activity_main)
            modePrefs = ModePrefs(this)
            currentLabel = getString(R.string.section_home)

            val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
            setSupportActionBar(toolbar)
            // Kill the title surface entirely. supportActionBar?.title
            // is set throughout the codebase (goHome, goSection, …),
            // so the only reliable way to hide it is at the action-bar
            // level. The Dynamic Island carries identity instead.
            supportActionBar?.setDisplayShowTitleEnabled(false)
            toolbar.title = ""
            // Search trigger has moved INTO AppDrawerSheetFragment (the
            // Home Apps page). The toolbar is now hamburger | island
            // | back only, NOT a global search affordance — no
            // setOnClickListener here.

            drawerLayout = findViewById(R.id.drawer_layout)
            // DrawerLayout with fitsSystemWindows="true" otherwise paints
            // a `?attr/colorPrimaryDark` (blue-gray) scrim across the
            // status-bar area, hiding our galaxy beneath. Force-clear it.
            drawerLayout.setStatusBarBackground(null)
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
            // Tap the island → fire whatever `ui.dynamic_island_action`
            // resolves to. Default is `app:com.termux.nix` (open the
            // Nix-on-Droid Termux app); legacy `notifications` opens
            // the Notification Centre. Parsed at runtime so editing
            // build.json + rebuilding is the only edit needed.
            findViewById<View>(R.id.dynamic_island)?.apply {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Haptics.tap(it)
                    dispatchDynamicIslandAction()
                }
            }

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

            // Apply mode-aware bottom-nav icons on init. Sections with
            // icon_apps/icon_admin overrides will swap glyphs; the rest
            // keep their bottom_nav.xml static icon.
            refreshBottomNavIconsForMode()

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

            // Warm-up the Phone tab data path on a low-priority Thread
            // so by the time the user swipes up to Home Apps → Phone,
            // the LauncherApps enumeration + icon load + folder
            // classification (~600ms) is already done. If the user
            // races the warm-up, PhoneAppsFragment.onCreateView falls
            // back to a synchronous load — same blocking shape as
            // before warm-up existed.
            PhoneAppsFragment.warmUp(this)

            // Launcher-icon shortcut (long-press launcher icon) carries a
            // shortcut_action extra. Same target grammar as tile clicks.
            handleShortcutIntent(intent)

            // Re-apply chrome after every back-stack change so that the
            // restored Fragment's ShellOverride takeover (or default) takes
            // effect — runOnCommit would have worked but it's mutually
            // exclusive with addToBackStack, so we use the listener instead.
            supportFragmentManager.addOnBackStackChangedListener {
                val frag = supportFragmentManager.findFragmentById(R.id.fragment_container)
                frag?.let { applyChrome(it) }
                // When the back stack drains AND the resulting visible
                // fragment is the originally-committed Home3DFragment,
                // sync currentSection back to "home". popBackStack()
                // itself doesn't go through goHome(), so without this
                // the field stays stale and onPrepareOptionsMenu keeps
                // showing the Back arrow at the Home root.
                if (supportFragmentManager.backStackEntryCount == 0 && frag is Home3DFragment) {
                    currentSection = "home"
                    currentLabel = getString(R.string.section_home)
                    supportActionBar?.title = currentLabel
                }
                // Toolbar's right-side Back action toggles visibility with
                // the back-stack depth; invalidate to redraw.
                invalidateOptionsMenu()
            }

            installNavSwipeGesture()
            installHomeLongPressFan()
            installTooltipConsumer()
            installHamburgerJitter()

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

                    // VERTICAL swipes — ONLY meaningful when on the Home
                    // section (where swipe-up opens AppDrawerSheet) and
                    // the sheet itself isn't already up. Off-home, the
                    // activity-level detector MUST defer entirely to
                    // the fragment so list scrolling, pull-to-refresh,
                    // sheet drag-close, WebView pan, etc. all own
                    // their own vertical gestures cleanly. Short-circuit
                    // BEFORE evaluating the vertical-fling thresholds
                    // so the detector doesn't even claim to have seen
                    // a vertical gesture off-home — eliminating the
                    // conflict the user reported.
                    val sheetIsUp = supportFragmentManager
                        .findFragmentByTag(AppDrawerSheetFragment.BACK_STACK_TAG) != null
                    if (currentSection == "home" && !sheetIsUp &&
                        absDy > absDx * 1.4f && absDy > minSwipePx && Math.abs(vY) > 600f) {
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

    /** Open the app drawer on swipe-up while on Home. Swipe-down is
     *  intentionally NOT bound — it was conflicting with normal in-app
     *  navigation (scrolling lists, pull-to-refresh, sheet-close all
     *  fighting for the same gesture). The Home Apps sheet still
     *  closes via the back button or back gesture. Returns true if
     *  the gesture was consumed. */
    private fun handleVerticalFling(dy: Float): Boolean {
        // Some fragments (browser/WebView in detail mode etc.) need to
        // own vertical flings — let the inner content scroll, pull-to-
        // refresh, or fire its own swipe-up navigation without the
        // activity stealing the gesture to open the app drawer sheet.
        val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if ((cur as? SuppressVerticalSwipe)?.suppressVerticalSwipe() == true) {
            return false
        }
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
            // swipe-down (dy > 0) was here to close the sheet — removed
            // because it was firing during normal scroll inside the sheet
            // and inside section pages, making lists feel sticky and
            // dismissing the sheet unintentionally. Back button / system
            // back-gesture remain as the explicit close path.
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

    // ── hamburger jitter ─────────────────────────────────────────────────
    //
    // The drawer-toggle hamburger sits in the same spot for hours of use
    // and never has anything to "say". Give it a tiny lifelike tic — a
    // random, short, low-amplitude shake every 3–5 seconds — so the
    // toolbar feels alive without being annoying. Animation is the
    // ImageButton's transform only (translation + rotation), nothing
    // about hit-test or click handling changes.
    //
    // Lifecycle: scheduled in onResume, cancelled in onPause — no work
    // happens while the activity is backgrounded.

    private val jitterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var jitterRunnable: Runnable? = null
    private val jitterRng = java.util.Random()

    /** Hook the runnable into the lifecycle-tracked toolbar. Idempotent. */
    private fun installHamburgerJitter() {
        // Nothing to do here at construction time — the scheduling is
        // driven by onResume / onPause so the loop pauses cleanly when
        // the activity is backgrounded. Kept as a stub for symmetry with
        // installNavSwipeGesture / installHomeLongPressFan / install-
        // TooltipConsumer.
    }

    private fun scheduleHamburgerJitter() {
        cancelHamburgerJitter()
        val toolbar: com.google.android.material.appbar.MaterialToolbar =
            findViewById(R.id.toolbar) ?: return
        // Wait one layout pass so the navigation ImageButton is in the
        // toolbar's child list.
        toolbar.post {
            val navIcon = findToolbarNavIcon(toolbar) ?: return@post
            postNextJitter(navIcon)
        }
    }

    private fun cancelHamburgerJitter() {
        jitterRunnable?.let { jitterHandler.removeCallbacks(it) }
        jitterRunnable = null
    }

    private fun findToolbarNavIcon(toolbar: ViewGroup): View? {
        // The drawer-toggle ImageButton is the toolbar's first child whose
        // drawable IS the navigation icon. Defending against future child
        // ordering by matching on (ImageButton, drawable == nav icon).
        val navDrawable =
            (toolbar as? com.google.android.material.appbar.MaterialToolbar)?.navigationIcon
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (v is android.widget.ImageButton) {
                if (navDrawable == null || v.drawable == navDrawable) return v
            }
        }
        return null
    }

    private fun postNextJitter(view: View) {
        // Random 3–5 second pause between tics.
        val delayMs = 3000L + jitterRng.nextInt(2000)
        jitterRunnable = Runnable {
            playHamburgerJitter(view)
            postNextJitter(view)
        }
        jitterHandler.postDelayed(jitterRunnable!!, delayMs)
    }

    private fun playHamburgerJitter(view: View) {
        // Three flavours rolled randomly so it doesn't read as a loop:
        //   0 → side-shake (translateX)
        //   1 → head-shake (rotation)
        //   2 → mixed jitter (both)
        val flavour = jitterRng.nextInt(3)
        val durMs = 240L + jitterRng.nextInt(160) // 240–400ms
        val rotPeak = (jitterRng.nextFloat() * 8f + 4f) * if (jitterRng.nextBoolean()) 1f else -1f
        val xPeak = (jitterRng.nextFloat() * 4f + 2f) * if (jitterRng.nextBoolean()) 1f else -1f
        val animators = mutableListOf<android.animation.ObjectAnimator>()
        if (flavour != 1) {
            animators += android.animation.ObjectAnimator.ofFloat(
                view, "translationX",
                0f, xPeak, -xPeak * 0.6f, xPeak * 0.25f, 0f,
            ).apply { duration = durMs }
        }
        if (flavour != 0) {
            animators += android.animation.ObjectAnimator.ofFloat(
                view, "rotation",
                0f, rotPeak, -rotPeak * 0.6f, rotPeak * 0.25f, 0f,
            ).apply { duration = durMs }
        }
        android.animation.AnimatorSet().apply {
            playTogether(*animators.toTypedArray())
            start()
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

        // Pick the home pane fragment based on launcher state + theme:
        //   • SuperApp is the active launcher AND theme = MinimalistBlack
        //     → terminal-style monospace app list (MinimalistBlackFragment)
        //   • Otherwise → existing 3D cube home (Home3DFragment).
        // Either way, applyLauncherChrome() handles the surrounding
        // chrome (system bar / status strip visibility).
        val themePrefs = LauncherThemePrefs(this)
        val homePane: androidx.fragment.app.Fragment =
            if (isDefaultLauncher() && themePrefs.theme == LauncherTheme.CloudMinimalistBlack)
                MinimalistBlackFragment.newInstance()
            else
                Home3DFragment.newInstance()
        swapContent(homePane, clearBackStack = true)

        syncBottomNav("home")
        syncDrawerTab(0)
        invalidateOptionsMenu()
        applyLauncherChrome()
    }

    /** Apply launcher-mode chrome based on (isDefaultLauncher, theme):
     *
     *   Cloud Theme + launcher    → hide system status bar, show our
     *                               LauncherStatusStripView (Time +
     *                               Battery), hide nothing else.
     *   Minimalist Black + launcher → hide system status bar AND hide
     *                               toolbar island + bottom nav (pure
     *                               terminal screen).
     *   Not launcher              → show system status bar, hide our
     *                               strip, leave toolbar + bottom nav
     *                               at their normal visibility.
     *
     * Idempotent — safe to call from onResume, theme changes, and
     * goHome / goSection. */
    fun applyLauncherChrome() {
        val isLauncher = isDefaultLauncher()
        val theme = LauncherThemePrefs(this).theme
        val strip = findViewById<View>(R.id.launcher_status_strip)
        val toolbarIsland = findViewById<View>(R.id.toolbar_island)
        val bottomNavIsland = findViewById<View>(R.id.bottom_nav_island)

        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (isLauncher) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            // Samsung One UI re-shows the system bars on the slightest
            // movement unless we pin the "swipe to reveal transiently"
            // behaviour. Without this, the system status bar paints back
            // over our strip the moment the user touches anything.
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        when {
            isLauncher && theme == LauncherTheme.Cloud -> {
                // OWN the status-bar zone — when in launcher mode the
                // system bars are HIDDEN (`controller.hide(statusBars())`
                // above), so the OS dispatches sys.top = 0 to the insets
                // listener and shell_linear's paddingTop stays 0. That
                // means the strip's frame already sits at screen y=0; we
                // do NOT want a negative topMargin (that would push it
                // off-screen above the visible area — the bug the user
                // saw in c062214). Pick the strip height: use the cached
                // real top inset only if it was ever observed > 0 (i.e.
                // the listener fired while the system bars happened to
                // be momentarily visible — typically the very first
                // dispatch before hide() takes effect), otherwise fall
                // back to the resource baseline. Both give the inner row
                // enough vertical room for 13sp text + 15dp battery icon.
                strip?.let {
                    val barH = if (topSystemInset > 0) topSystemInset else statusBarHeightPx()
                    val lp = it.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        lp.topMargin = 0
                        lp.height = barH
                        it.layoutParams = lp
                    }
                    // Drop the legacy py=3dp padding the view set in init
                    // so the text isn't squeezed against the bottom edge.
                    it.setPadding(it.paddingLeft, 0, it.paddingRight, 0)
                    it.visibility = View.VISIBLE
                }
                // Push the toolbar island BELOW the strip with a 6dp
                // breathing gap. Without this the island's vertical
                // span overlaps the strip and the strip's bottom
                // hairline visually touches the dynamic-island pill.
                val barH = if (topSystemInset > 0) topSystemInset else statusBarHeightPx()
                setToolbarIslandTopMargin(toolbarIsland, barH + dp(6))
                toolbarIsland?.visibility = View.VISIBLE
                bottomNavIsland?.visibility = View.VISIBLE
            }
            isLauncher && theme == LauncherTheme.CloudMinimalistBlack -> {
                strip?.visibility = View.GONE
                toolbarIsland?.visibility = View.GONE
                bottomNavIsland?.visibility = View.GONE
            }
            isLauncher && theme == LauncherTheme.CloudPowerSaving -> {
                // Power Saving — Samsung / Apple / Pixel-style. No
                // chrome at all: no top strip, no toolbar island, no
                // bottom-nav island. Window background flipped to
                // pure black for OLED self-emission savings (the
                // oled_black feature flag drives this). Background
                // services paused via BackgroundOrchestrator below;
                // WireGuard stays up.
                strip?.visibility = View.GONE
                toolbarIsland?.visibility = View.GONE
                bottomNavIsland?.visibility = View.GONE
                window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
            }
            else -> {
                strip?.visibility = View.GONE
                // Restore the XML default — system status bar is visible
                // here, DrawerLayout pads the content by sys.top, so a
                // 6dp gap from THAT padded edge is already correct.
                setToolbarIslandTopMargin(toolbarIsland, dp(6))
                toolbarIsland?.visibility = View.VISIBLE
                bottomNavIsland?.visibility = View.VISIBLE
            }
        }
        // Apply the theme's background_pause / wireguard_required
        // policy. Reads features map declared in build.json::ui
        // .launcher_themes[theme].features so this stays declarative
        // — Power Saving's background_pause=true tears down the Maps
        // tracker + WorkManager periodic jobs; flipping back to Cloud
        // is a no-op (producers re-arm on their own lifecycle).
        runCatching {
            BackgroundOrchestrator.applyForTheme(this, LauncherThemes.featuresFor(theme))
        }
        // Profile-level WireGuard policy — Guest profile pins the
        // tunnel OFF so a borrowed phone can't see private infra.
        runCatching {
            val profile = LauncherProfilePrefs(this).profile
            val behavior = LauncherProfiles.behaviorFor(profile)
            if (behavior.wireguardOff) {
                WgState.requestTunnelDown(this)
            }
        }
    }

    /** dp → px convenience for chrome math. */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Parse [BuildConfig.UI_DYNAMIC_ISLAND_ACTION] and dispatch. Formats:
     *   • `app:<packageName>`  → launch that app's main activity.
     *   • `notifications`      → open the Notification Centre.
     *   • `shortcut:<id>`      → fire a shortcut-style intent (currently
     *     unused, reserved for future bindings like `shortcut:wallet`).
     *  Tolerant of an uninstalled target — shows a short Toast instead of
     *  silently falling back, so the user's binding stays authoritative. */
    private fun dispatchDynamicIslandAction() {
        val action = BuildConfig.UI_DYNAMIC_ISLAND_ACTION.ifBlank { "notifications" }
        val parts = action.split(":", limit = 2)
        when (parts.getOrNull(0)) {
            "app" -> {
                val pkg = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    startActivity(intent)
                } else {
                    android.widget.Toast.makeText(this, "$pkg not installed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            "notifications" -> openNotificationCenter()
            "shortcut"      -> parts.getOrNull(1)?.let { handleShortcutById(it) }
            else            -> {}
        }
    }

    /** Best-effort shortcut dispatch — re-uses [handleShortcutIntent]'s
     *  vocabulary by synthesising an intent with the shortcut_action extra.
     *  Lets `ui.dynamic_island_action = "shortcut:wallet"` reach the same
     *  code path as the launcher-icon long-press shortcuts. */
    private fun handleShortcutById(id: String) {
        val syn = android.content.Intent().apply { putExtra("shortcut_action", id) }
        handleShortcutIntent(syn)
    }

    /** Apply a topMargin to the toolbar island. Tolerant of a null
     *  view + non-margin layout params (returns silently). */
    private fun setToolbarIslandTopMargin(island: View?, marginPx: Int) {
        val lp = island?.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != marginPx) {
            lp.topMargin = marginPx
            island.layoutParams = lp
        }
    }

    /** Called by [LauncherConfigFragment] right after writing a new
     *  theme into [LauncherThemePrefs]. Re-applies the launcher chrome
     *  and, if the user is currently on Home, rebuilds the home pane
     *  so a Minimalist Black ↔ Cloud swap takes effect immediately. */
    fun notifyLauncherThemeChanged() {
        applyLauncherChrome()
        if (currentSection == "home") goHome()
    }

    /** System status bar pixel height. Reads the platform's
     *  android.R.dimen.status_bar_height resource so we match Samsung /
     *  Pixel / OEM-specific values without guessing. Falls back to 24dp
     *  on the rare device that doesn't expose the dimen. */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
               else (24 * resources.displayMetrics.density).toInt()
    }

    /** True when the SuperApp's MainActivity is currently the resolved
     *  default Home Screen handler. Mirrored from
     *  [LauncherConfigFragment.isDefaultLauncher] so the chrome
     *  decision flow can stay local to the activity. */
    private fun isDefaultLauncher(): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolved = packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == packageName
    }

    /** Land the right pane on the given section's TileGrid (or placeholder
     *  if the section has no declared sub-pages). */
    private fun goSection(id: String, label: String) {
        Trace.i(TAG, "goSection id=$id label=$label bs=${supportFragmentManager.backStackEntryCount}")
        if (id == "home") { goHome(); return }
        currentSection = id
        currentLabel = label
        supportActionBar?.title = label
        // Record into App Tabs LRU — skips the apptabs section itself so
        // bouncing back from the Tabs shelf doesn't recursively log.
        if (id != "apptabs") runCatching {
            val sec = Sections.byId(id)
            com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                .recordSection(id, label, sec?.iconName ?: "")
        }

        val section = Sections.byId(id)
        val content: Fragment = when {
            section == null -> SectionFragment.forSection(id, label)
            // Aggregator with BOTH Apps and Admin variants — wrap with
            // a TabbedSectionFragment so the page carries its own
            // "Apps · Admin" tab strip at the top. Replaces the old
            // global drawer swap-icon switcher for these sections
            // (Infos, Labs today). Predicate matches tile-shaped OR
            // stack-shaped variants — covers every section that has a
            // meaningful mode distinction.
            section.isAggregator && (
                (section.tilesApps.isNotEmpty()  && section.tilesAdmin.isNotEmpty()) ||
                (section.stackApps.isNotEmpty()  && section.stackAdmin.isNotEmpty())
            ) -> TabbedSectionFragment.newInstance(section.id, label)
            // Aggregator: if stack_* is declared for the current mode →
            // scrollable collapsable-card view. Otherwise fall back to the
            // tile-grid (still data-driven via tiles_* in build.json).
            section.isAggregator && Sections.aggregatorIsStack(section, currentMode) ->
                AggregatorStackFragment.newInstance(section.id, label, currentMode)

            // Aggregator with themed sub-groups (Suite today) → render
            // them as titled rows in a GroupedTilesFragment instead of
            // flattening into one grid. Same data, same TileClickListener
            // contract, just structured.
            //
            // Suite is the ONE aggregator that also has a phone_apps
            // curated list — wrap the tile grid in a Cloud | Phone
            // TabLayout host so the user can swipe between the cloud
            // services view (default) and a flat grid of their phone
            // apps. Other aggregators bypass the tabs and go straight
            // to GroupedTilesFragment.
            section.isAggregator && section.tileGroups.isNotEmpty() && section.id == "suite" ->
                SuiteCloudPhoneTabsFragment.newInstance()
            section.isAggregator && section.tileGroups.isNotEmpty() ->
                GroupedTilesFragment.newInstance(section.id)

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
        val galaxy = findViewById<View>(R.id.galaxy_backdrop)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            bottomSystemInset = sys.bottom
            topSystemInset = sys.top
            // The galaxy view needs to extend INTO the padded inset zones
            // so the stars / comets fill the status + nav bar area too.
            // Negative margins push it past the shell's padding boundary;
            // shell_linear has clipChildren=false + clipToPadding=false so
            // the overflow renders instead of being chopped.
            (galaxy?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.also { lp ->
                lp.topMargin = -sys.top
                lp.bottomMargin = -sys.bottom
                galaxy.layoutParams = lp
            }
            // Re-apply the launcher chrome now that the REAL top inset is
            // known — the first applyLauncherChrome() call in onCreate ran
            // before this listener fired, so it used the stale-but-safe
            // resource fallback. Refresh now with the actual Samsung /
            // Pixel / OEM-reported height so the strip sits at y=0.
            applyLauncherChrome()
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
        // DETAIL mode for the tapped URL. Tab is added to BrowserTabPrefs
        // inside BrowserHostFragment on creation when ARG_OPEN_URL is
        // present.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            currentSection = "browser"
            currentLabel = Sections.byId("browser")?.label ?: "Tabs"
            supportActionBar?.title = currentLabel
            syncBottomNav("browser")
            val frag = com.diegonmarcos.superapp.browser.BrowserHostFragment.newInstance(uri)
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
                runCatching {
                    val label = runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                        .recordExternalApp(pkg, label)
                    startActivity(launch); return
                }
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
            // Cloud Notification Center — same surface the top-bar bell
            // icon opens. Wiring it here means any data-driven entry
            // (tile target / drawer action) can route to it without code.
            actionType == "open_notification_center" -> openNotificationCenter()
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
        // Pages that declare an `action` in build.json (e.g. config/update
        // → "action:check_updates", config/import → "action:import_configs")
        // must dispatch the action instead of opening a fragment. Before
        // we did this here, only the drawer's onDrawerPageSelected honoured
        // the action — Home Apps tiles using `page:config/import` ended up
        // at SectionFragment's "Coming soon" placeholder. Now both surfaces
        // route the same way.
        val pageAction = Sections.byId(sectionId)?.pages
            ?.firstOrNull { it.id == pageId }?.action.orEmpty()
        if (pageAction.isNotBlank()) {
            onTileClicked(pageAction)
            return
        }
        // Record into App Tabs LRU — skips the apptabs section so opening
        // the shelf itself doesn't pollute the list.
        if (sectionId != "apptabs") runCatching {
            val pageEntry = Sections.byId(sectionId)?.pages?.firstOrNull { it.id == pageId }
            com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).recordPage(
                sectionId, pageId,
                label    = pageEntry?.label ?: pageId,
                iconName = pageEntry?.iconName ?: "",
            )
        }
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

    /** Slide the Notification Centre down from the top. Reuses the
     *  search-sheet animation set so transitions stay consistent. */
    private fun openNotificationCenter() {
        if (supportFragmentManager.findFragmentByTag(NotificationCenterFragment.BACK_STACK_TAG) != null) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_up,  R.anim.fade_out,
                R.anim.fade_in,      R.anim.slide_out_down,
            )
            .add(R.id.fragment_container, NotificationCenterFragment.newInstance(),
                NotificationCenterFragment.BACK_STACK_TAG)
            .addToBackStack(NotificationCenterFragment.BACK_STACK_TAG)
            .commit()
    }

    /** Dynamic Island is now an [IslandWaveView] (purely decorative
     *  animated triple-sine waveform — Samsung-music-island vibe).
     *  The old "{INITIALS} · {Mode}" text label is gone, so this is
     *  intentionally a no-op kept on the existing call sites for any
     *  future re-introduction of text overlay. */
    private fun refreshDynamicIsland() { /* no-op — see IslandWaveView */ }

    /** Called by [TabbedSectionFragment] after it has written the new
     *  mode into [ModePrefs]. Refreshes every other surface that
     *  reads from ModePrefs (drawer island label, bottom-nav icons,
     *  options menu) — but does NOT re-trigger [goSection] because
     *  the tab strip already rendered its own new child fragment;
     *  rebuilding the section would unmount the tab fragment itself. */
    fun notifyModeChanged() {
        refreshDynamicIsland()
        refreshBottomNavIconsForMode()
        invalidateOptionsMenu()
    }

    override fun onDrawerModeToggle() {
        // Flip global Apps↔Admin. Don't close the drawer — the user just
        // tapped the identity row and may want to see the flip take
        // effect there, and may keep navigating from the drawer.
        Haptics.tap(drawerLayout)
        modePrefs.toggle()
        refreshDynamicIsland()
        // Bottom-nav icons can be mode-aware (Section.iconForMode); rebind
        // here so Infos/Tools switch glyphs immediately without waiting
        // for a process restart. Same data path drives the home_groups
        // tiles + drawer rows.
        refreshBottomNavIconsForMode()
        // Refresh any visible aggregator so its tiles re-render for the
        // new mode.
        if (currentSection.isNotEmpty()) {
            val sec = Sections.byId(currentSection)
            if (sec?.isAggregator == true) goSection(currentSection, currentLabel)
        }
    }

    /** Rebind bottom-nav menu items to their per-mode icons AND to
     *  their data-driven labels. Reads Section.iconForMode + Section.label
     *  from build.json::ui.sections — bottom_nav.xml is now only the
     *  structural anchor (item ids + fallback static icons); the human-
     *  visible label is whatever sections[id=X].label says today. Lets
     *  us rename Tools→Labs purely in build.json without touching
     *  res/values/strings.xml. Safe to call repeatedly. */
    private fun refreshBottomNavIconsForMode() {
        val mode = modePrefs.mode
        for (i in 0 until bottomNav.menu.size()) {
            val item = bottomNav.menu.getItem(i)
            val sectionId = sectionIdForNavId(item.itemId) ?: continue
            val section = Sections.byId(sectionId) ?: continue
            val iconRes = Sections.iconResFor(this, section.iconForMode(mode))
            if (iconRes != 0) item.setIcon(iconRes)
            if (section.label.isNotBlank()) item.title = section.label
        }
    }

    // ── toolbar (right-side Back action) ─────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_top, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // action_back: anywhere except a clean Home (Home with no
        // back-stack entries is the "root" — back is meaningless there).
        val atHomeRoot = currentSection == "home" &&
            supportFragmentManager.backStackEntryCount == 0
        menu.findItem(R.id.action_back)?.isVisible = !atHomeRoot
        // action_wallet: ONLY at the Home root (mirror of action_back).
        // Slots into the same top-right toolbar position so the user
        // gets a single context-appropriate action there at all times.
        menu.findItem(R.id.action_wallet)?.isVisible = atHomeRoot
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_back -> {
                // First, give the visible fragment a chance to handle
                // it internally — Wallet's Compose state machine (cards
                // Selected/Full/Config) wants Back to unwind one
                // Compose step at a time before popping the activity
                // back-stack. Only fragments implementing BackHandler
                // participate; everything else falls through.
                val top = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if ((top as? BackHandler)?.tryHandleBack() == true) {
                    return true
                }
                // ONE LEVEL up — matches system Back. Previously this
                // popped the entire back stack (jump to section root),
                // but that aggressive behaviour skipped past parents
                // for cross-section pushes (e.g. Wallet → vCard →
                // toolbar Back used to jump all the way to Home,
                // bypassing Wallet). Single-pop is predictable and
                // composes — tap twice to skip two levels.
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else if (currentSection != "home") {
                    goHome()
                }
                return true
            }
            R.id.action_wallet -> {
                // Open the Wallet section page. The Virtual Business
                // Card surface is now reachable from inside Wallet
                // (tap the pinned vCard at the top of the deck) — same
                // NavigationListener wiring as the drawer-header
                // identity row, just routed through one extra hop.
                openSectionPage("wallet", "cards")
                return true
            }
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

    /** Drives the music-playing indicator above the Dynamic Island.
     *  Initialised lazily on first onResume so we don't allocate the
     *  MediaSessionManager binder + listener until the activity is
     *  actually visible. */
    private var nowPlayingMonitor: NowPlayingMonitor? = null
    private var musicControlsPopup: MusicControlsPopup? = null

    override fun onResume() {
        super.onResume()
        com.diegonmarcos.superapp.devcontrol.DevControlBridge.register(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
        scheduleHamburgerJitter()
        // Re-apply launcher chrome — user may have toggled us as the
        // default Home handler from system Settings while we were
        // backgrounded, and the system status bar / our strip need to
        // sync. Idempotent.
        applyLauncherChrome()
        // Music-playing mini-island. NotificationListener perm gates
        // the MediaSessionManager query; when it's not granted the
        // monitor silently reports no sessions and the island stays
        // GONE. Starting on every onResume + stopping on onPause
        // means the listener doesn't burn cycles while backgrounded.
        // Tap → open the app whose controller is currently PLAYING
        // (NowPlayingMonitor.currentPackage). Falls back to a Toast
        // when the package has no launch intent (rare — Android
        // surface apps without a launcher activity).
        val island = findViewById<android.widget.FrameLayout>(R.id.music_playing_island)
        val islandIcon = findViewById<android.widget.ImageView>(R.id.music_playing_icon)
        val islandTitle = findViewById<android.widget.TextView>(R.id.music_playing_title)
        if (island != null && nowPlayingMonitor == null) {
            nowPlayingMonitor = NowPlayingMonitor(this) { playingPackage, title ->
                island.visibility = if (playingPackage != null)
                    android.view.View.VISIBLE else android.view.View.GONE
                // Icon — load the playing app's launcher icon; fall back
                // to null when the package has no application icon
                // (rare; e.g. system surfaces that publish a media
                // session without an APK icon).
                if (playingPackage != null) {
                    runCatching {
                        islandIcon?.setImageDrawable(
                            packageManager.getApplicationIcon(playingPackage))
                    }.onFailure { islandIcon?.setImageDrawable(null) }
                    // Title — the song name from MediaMetadata. When
                    // the app publishes neither TITLE nor DISPLAY_TITLE,
                    // show the app label as a sensible fallback so the
                    // strip never reads blank.
                    val text = title ?: runCatching {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(playingPackage, 0)
                        ).toString()
                    }.getOrDefault("Playing")
                    islandTitle?.text = text
                    // Marquee only animates when the TextView is selected
                    // or focused. setSelected(true) is the canonical
                    // "kick the scroll off without stealing focus"
                    // pattern. Re-applied on every title change so the
                    // animation restarts from the start of the new
                    // string instead of continuing mid-scroll.
                    islandTitle?.isSelected = false
                    islandTitle?.isSelected = true
                } else {
                    islandIcon?.setImageDrawable(null)
                    islandTitle?.text = ""
                }
            }
            island.setOnClickListener {
                // Tap → Samsung-style controls popup anchored under the
                // mini-island (album art + title/artist + progress +
                // prev/play-pause/next). The popup's own app-icon tap
                // is what opens the playing app — the island itself no
                // longer launches the app directly.
                val pkg = nowPlayingMonitor?.currentPackage ?: return@setOnClickListener
                val ctrl = nowPlayingMonitor?.currentController
                if (musicControlsPopup == null) {
                    musicControlsPopup = MusicControlsPopup(this)
                }
                musicControlsPopup?.show(island, ctrl, pkg)
            }
        }
        nowPlayingMonitor?.start()
    }

    override fun onPause() {
        com.diegonmarcos.superapp.devcontrol.DevControlBridge.unregister(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener(null)
        cancelHamburgerJitter()
        nowPlayingMonitor?.stop()
        musicControlsPopup?.dismiss()
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
