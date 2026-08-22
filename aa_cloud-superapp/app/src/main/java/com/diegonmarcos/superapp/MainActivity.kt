package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.ui.snack
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.ShellOverride
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.system.BackgroundOrchestrator
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.IslandWaveView
import com.diegonmarcos.superapp.launcher.Pages
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.TabbedSectionFragment
import com.diegonmarcos.superapp.launcher.CircularMenuTree
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.SectionMenuFragment
import com.diegonmarcos.superapp.launcher.SectionFragment
import com.diegonmarcos.superapp.launcher.PlaceholderDrawerFragment
import com.diegonmarcos.superapp.launcher.MinimalistBlackFragment
import com.diegonmarcos.superapp.launcher.LauncherStatusStripView
import com.diegonmarcos.superapp.launcher.HomeFanMenu
import com.diegonmarcos.superapp.launcher.HomeDrawerFragment
import com.diegonmarcos.superapp.launcher.Home3DFragment
import com.diegonmarcos.superapp.launcher.DetailPlaceholderFragment
import com.diegonmarcos.superapp.launcher.GroupedTilesFragment
import com.diegonmarcos.superapp.launcher.AppDrawerSheetFragment
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment
import com.diegonmarcos.superapp.devtools.DevControlBridge
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.updater.UpdateOverlayFragment
import com.diegonmarcos.superapp.settings.LauncherThemePrefs
import com.diegonmarcos.superapp.settings.LauncherProfilePrefs
import com.diegonmarcos.superapp.settings.LauncherConfigFragment
import com.diegonmarcos.superapp.settings.ImportConfigsFragment
import com.diegonmarcos.superapp.apps.SuiteCloudPhoneTabsFragment
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.launcher.RecentCloudTiles
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.battery.BatterySessionWorker
import com.diegonmarcos.superapp.search.SearchSheetFragment
import com.diegonmarcos.superapp.search.SearchOpener
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.profile.BusinessCardFragment

import com.diegonmarcos.superapp.core.SuppressVerticalSwipe
import com.diegonmarcos.superapp.launcher.LauncherToolbarFx
import com.diegonmarcos.superapp.launcher.LauncherNavController
import com.diegonmarcos.superapp.onehand.ArcMenu
import com.diegonmarcos.superapp.onehand.CanopusStar
import com.diegonmarcos.superapp.onehand.CircularMenu
import com.diegonmarcos.superapp.onehand.SiriusStar
import com.diegonmarcos.superapp.media.MusicIslandController
import com.diegonmarcos.superapp.notificationcenter.NotificationCenterFragment

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
import com.diegonmarcos.superapp.launcher.BackHandler
// WalletHost removed — libs:wallet moved to ea_cloud-wallet (constellation APK).

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
    com.diegonmarcos.superapp.devtools.DevControlBridge.ActivityHost,
    MailHost,
    SearchOpener,
    com.diegonmarcos.superapp.apptabs.AppTabsHost,
    LauncherNavController.NavHost {

    /** Navigation policy + walk-list state; the Activity is its view host. */
    private val nav = LauncherNavController(this)

    /** [AppTabsHost] — card-tap callback from libs:launcher-apptabs. Routes a
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
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.TargetEntry ->
                dispatchTarget(entry.target)
        }
    }

    private val TAG = "MainActivity"
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var toolbarFx: LauncherToolbarFx
    private lateinit var drawerTabs: TabLayout
    private lateinit var drawerPageTabs: TabLayout

    // Custom setter = single chokepoint: every section change re-evaluates
    // all three home stars' visibility (shown only on `home`). findViewById is
    // null-safe so the very first assignment (before setContentView) no-ops.
    override var currentSection: String = ""
        set(value) {
            field = value
            siriusStar.update(value); canopusStar.update(value); centauriStar.update(value)
        }
    override var currentLabel:   String = ""

    // All THREE home stars live in libs:launcher-onehand — Sirius/Canopus/Centauri are
    // all generic widget classes there now. Sirius and Canopus need app-side
    // content (the section/page tree), so MainActivity builds their Host here
    // and injects it; Centauri needs none (pure platform API), so it takes no
    // Host at all. iconBitmap is identical for Sirius and Canopus — both
    // resolve build.json icon names via Sections.iconResFor, same as every
    // other launcher surface.
    private fun iconBitmapFor(name: String, sizePx: Int): android.graphics.Bitmap? {
        if (name.isBlank() || sizePx <= 0) return null
        val resId = Sections.iconResFor(this, name)
        if (resId == 0) return null
        val d = androidx.core.content.ContextCompat.getDrawable(this, resId) ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, sizePx, sizePx); d.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    private val siriusStar by lazy {
        SiriusStar(
            activity = this,
            star = findViewById(R.id.sirius_star),
            host = object : CircularMenu.Host {
                override fun navigate(target: String) { onTileClicked(target) }
                override fun iconBitmap(name: String, sizePx: Int) = iconBitmapFor(name, sizePx)
                override fun childrenOf(key: String) = CircularMenuTree.childrenOf(this@MainActivity, key)
            },
            twinkleEnabled = {
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).toggle("star_twinkle")
            },
        )
    }
    private val canopusStar by lazy {
        CanopusStar(
            activity = this,
            star = findViewById(R.id.canopus_star),
            island = findViewById(R.id.bottom_nav_island),
            host = object : ArcMenu.Host {
                override fun navigate(target: String) { onTileClicked(target) }
                override fun iconBitmap(name: String, sizePx: Int) = iconBitmapFor(name, sizePx)
                override fun itemsFor(section: String) =
                    SectionPages.pagesFor(section).map {
                        ArcMenu.Item(it.label, it.iconName, "page:$section/${it.id}")
                    }
            },
        )
    }
    // Centauri lives in libs:launcher-onehand (like Sirius/Canopus) — its content
    // (last 9 recent apps) is pure platform API, no app Host needed, so it
    // takes the star View + island anchor directly instead of a Host.
    private val centauriStar by lazy {
        com.diegonmarcos.superapp.onehand.CentaurusStar(
            this,
            findViewById(R.id.centauri_star),
            findViewById(R.id.bottom_nav_island),
        )
    }

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
    override val currentMode: String get() = modePrefs.mode

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
            // Draw INTO the display cutout (camera punch-hole) row so the
            // launcher status strip's Line 0 OWNS the camera band — black bg
            // with "Cloud"/"SuperApp" flanking the punch-hole — instead of
            // being letterboxed BELOW it. SHORT_EDGES = top/bottom only
            // (portrait), the standard mode for a top camera cutout. Set
            // programmatically (not via theme) for the same reason as the
            // colors above: Samsung One UI ignores the theme attribute.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            applyWindowBlurIfSupported()

            setContentView(R.layout.activity_main)
            // App Tabs LRU window size — data-driven from build.json::ui.app_tabs.cap.
            com.diegonmarcos.superapp.apptabs.AppTabPrefs.cap = BuildConfig.UI_APP_TABS_CAP
            modePrefs = ModePrefs(this)
            currentLabel = getString(R.string.section_home)
            siriusStar.setup(); siriusStar.update(currentSection)
            canopusStar.setup(); canopusStar.update(currentSection)
            centauriStar.setup(); centauriStar.update(currentSection)

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
                // A full-bleed modal overlay (Notification Center / Search),
                // when present, dictates the shell chrome; otherwise the
                // primary content does. Keeps islands correct whether or not
                // the underlying section is a ShellOverride.
                val chromeFrag = supportFragmentManager.findFragmentById(R.id.overlay_container) ?: frag
                chromeFrag?.let { applyChrome(it) }
                // When the back stack drains AND the resulting visible
                // fragment is the originally-committed Home3DFragment,
                // sync currentSection back to "home". popBackStack()
                // itself doesn't go through goHome(), so without this
                // the field stays stale and onPrepareOptionsMenu keeps
                // showing the Back arrow at the Home root.
                // GUARD: skip during a goSection swap — its clearBackStack pop
                // fires this listener while the OLD Home3DFragment is still
                // mounted (the replace hasn't run yet), which would clobber the
                // just-set currentSection back to "home" (broke Home→Configs:
                // no Back arrow + dead page:<id> tiles).
                if (!inSectionSwap && supportFragmentManager.backStackEntryCount == 0 && frag is Home3DFragment) {
                    currentSection = "home"
                    currentLabel = getString(R.string.section_home)
                    supportActionBar?.title = currentLabel
                }
                // Tablet: popping the last DETAIL page leaves the detail pane
                // empty — restore the "Select an item" placeholder so the
                // master-detail split never shows a blank right column.
                if (isTwoPane() && supportFragmentManager.backStackEntryCount == 0 &&
                    currentSection != "home" &&
                    supportFragmentManager.findFragmentById(R.id.detail_container) !is DetailPlaceholderFragment) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.detail_container, DetailPlaceholderFragment.newInstance())
                        .commitAllowingStateLoss()
                }
                // Toolbar's right-side Back action toggles visibility with
                // the back-stack depth; invalidate to redraw.
                invalidateOptionsMenu()
            }

            installNavSwipeGesture()
            toolbarFx = LauncherToolbarFx(this, bottomNav, { onTileClicked(it) }, ::sectionIdForNavId)
            toolbarFx.install()

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
                    // "Home screen" means the Home ROOT — currentSection stays
                    // "home" for every fragment pushed while inside the Home
                    // section (e.g. Suite:Phone:All), so that alone isn't
                    // enough: also require an empty back stack, mirroring
                    // atHomeRoot in onPrepareOptionsMenu. Otherwise swipe
                    // up/down leaks into those pages and fights their scroll.
                    val atHomeRoot = currentSection == "home" &&
                        supportFragmentManager.backStackEntryCount == 0
                    if (atHomeRoot && !sheetIsUp &&
                        absDy > absDx * 1.4f && absDy > minSwipePx && Math.abs(vY) > 600f) {
                        val consumed = handleVerticalFling(dy)
                        if (consumed) Haptics.tap(bottomNav)
                        return consumed
                    }

                    // HORIZONTAL swipes are ALSO home-root-only, same reason.
                    if (!atHomeRoot) return false
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
                    // Horizontal swipe = step the circular walk-list (Comms →
                    // Infos·Apps → Infos·Admin → Home-Apps·Cloud → … → Labs·
                    // Admin → wrap). Left-swipe (dx<0) = next, right = prev.
                    fireGeminiPattern()
                    val prefs = com.diegonmarcos.superapp.settings.HomeSwipePrefs(this@MainActivity)
                    val swipeAction = if (dx < 0) prefs.left else prefs.right
                    if (swipeAction == "walk_step_next" || swipeAction == "walk_step_prev") {
                        walkStep(direction = if (dx < 0) +1 else -1)
                    } else {
                        handleHomeSwipeAction(swipeAction)
                    }
                    return true
                }
            })
    }

    /** Fire the configured action on swipe-up/down while on Home. Returns
     *  true if the gesture was consumed. */
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
                handleHomeSwipeAction(com.diegonmarcos.superapp.settings.HomeSwipePrefs(this).up); true
            }
            dy > 0 && currentSection == "home" && !sheetIsUp -> {
                handleHomeSwipeAction(com.diegonmarcos.superapp.settings.HomeSwipePrefs(this).down); true
            }
            else -> false
        }
    }

    /** Dispatch a home-screen swipe action by name. Values come from
     *  build.json::onehand.home_swipes baked into BuildConfig. */
    private fun handleHomeSwipeAction(action: String) {
        when (action) {
            "open_home_apps" -> openAppDrawerSheet()
            "open_last_superapp_page" -> {
                val last = runCatching {
                    com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).all()
                        .firstOrNull { it !is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry }
                }.getOrNull()
                if (last != null) onAppTabPicked(last) else openAppDrawerSheet()
            }
            "open_last_android_app" -> {
                val last = runCatching {
                    com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).all()
                        .filterIsInstance<com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry>()
                        .firstOrNull()
                }.getOrNull()
                if (last != null) {
                    runCatching { startActivity(packageManager.getLaunchIntentForPackage(last.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } else openAppDrawerSheet()
            }
            "walk_step_next" -> { fireGeminiPattern(); walkStep(+1) }
            "walk_step_prev" -> { fireGeminiPattern(); walkStep(-1) }
            // Target-string actions (e.g. "tab:phone:suite", "action:open_home_apps")
            // route through the same dispatch grammar as tile taps.
            else             -> if (action.contains(':')) onTileClicked(action) else openAppDrawerSheet()
        }
    }

    override fun openAppDrawerSheet(initialTab: String) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_up,  R.anim.fade_out,
                R.anim.fade_in,      R.anim.slide_out_down,
            )
            .add(R.id.fragment_container, AppDrawerSheetFragment.newInstance(initialTab),
                 AppDrawerSheetFragment.BACK_STACK_TAG)
            .addToBackStack(AppDrawerSheetFragment.BACK_STACK_TAG)
            .commit()
    }

    /** Step `direction` through the circular walk-list, wrapping at both
     *  ends. +1 = next (left-swipe), -1 = prev (right-swipe). */
    private fun walkStep(direction: Int) = nav.walkStep(direction)

    override fun closeAppDrawerSheetIfOpen() {
        if (supportFragmentManager.findFragmentByTag(AppDrawerSheetFragment.BACK_STACK_TAG) != null) {
            supportFragmentManager.popBackStack(
                AppDrawerSheetFragment.BACK_STACK_TAG,
                FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::navSwipeGesture.isInitialized) navSwipeGesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }


    // ── bottom nav ────────────────────────────────────────────────────────

    private val haptHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onBottomNavPicked(item: MenuItem): Boolean {
        if (suppressBottomNavReentry) return true
        val id = sectionIdForNavId(item.itemId) ?: return false
        fireGeminiPattern()
        // "Home" fully resets to the home root (pop pages, close drawer) — same as
        // the system HOME button — not just a section switch.
        if (id == "home") resetToHome() else goSection(id, Sections.byId(id)?.label ?: id)
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

    private fun goHome() = nav.goHome()

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
    override fun applyLauncherChrome() {
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
                        // WRAP_CONTENT (was fixed barH): the strip now stacks
                        // Line 0 (black band sized to barH) + Line 1 (info row)
                        // + hairline, so its height is the sum, not one barH.
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        it.layoutParams = lp
                    }
                    // Drop the legacy py=3dp padding the view set in init
                    // so the text isn't squeezed against the bottom edge.
                    it.setPadding(it.paddingLeft, 0, it.paddingRight, 0)
                    it.visibility = View.VISIBLE
                }
                // Push the toolbar island BELOW the (now two-line) strip with a
                // 6dp gap. The strip is WRAP_CONTENT, so its final height isn't
                // known until layout: seed with barH (Line 0) for the first
                // pass, then correct to the real measured height post-layout.
                val barH = if (topSystemInset > 0) topSystemInset else statusBarHeightPx()
                setToolbarIslandTopMargin(toolbarIsland, barH + dp(6))
                strip?.let { s ->
                    s.post {
                        val h = s.height
                        if (h > 0) setToolbarIslandTopMargin(toolbarIsland, h + dp(6))
                    }
                }
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
        applyLauncherSettings()
        if (currentSection == "home") goHome()
    }

    /** Configs → Launcher → Others — re-applies settings whose views live in the
     *  activity shell (not recreated on a chrome re-render), so toggling them
     *  takes effect LIVE: stars (galaxy backdrop) + animal pets, plus device-wide
     *  system brightness. Eye protection is the ANDROID SYSTEM night-light (opened
     *  from the picker), not a custom overlay. Cube/haptics re-read themselves. */
    private fun applyLauncherSettings() {
        val prefs = com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this)
        runCatching {
            (findViewById<View>(R.id.galaxy_backdrop)
                as? com.diegonmarcos.superapp.ui.GalaxyBackdropView)?.applyStarsPref()
        }
        runCatching {
            (findViewById<View>(R.id.launcher_status_strip)
                as? LauncherStatusStripView)?.applyPetsPref()
        }
        runCatching {
            (findViewById<View>(R.id.dynamic_island_wave)
                as? com.diegonmarcos.superapp.ui.IslandWaveView)?.applyIslandPref()
        }
        runCatching {
            val b = prefs.brightness
            if (b >= 0 && android.provider.Settings.System.canWrite(this)) {
                android.provider.Settings.System.putInt(contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                android.provider.Settings.System.putInt(contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, b.coerceIn(0, 255))
            }
        }
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
    override fun isDefaultLauncher(): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolved = packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == packageName
    }

    /** Land the right pane on the given section's TileGrid (or placeholder
     *  if the section has no declared sub-pages). */
    private fun goSection(id: String, label: String, initialTab: String = "") =
        nav.goSection(id, label, initialTab)

    /** Public entry so transient UI (e.g. the status-strip network popup's
     *  KDE Connect row) can navigate to a section. */
    fun openSection(id: String) = goSection(id, Sections.byId(id)?.label ?: id)

    override fun syncBottomNav(sectionId: String) {
        val navId = idForSectionId(sectionId) ?: return
        if (bottomNav.selectedItemId != navId) {
            suppressBottomNavReentry = true
            bottomNav.selectedItemId = navId
            suppressBottomNavReentry = false
        }
    }

    override fun syncDrawerTab(index: Int) {
        if (index == 1) drawerTabs.getTabAt(1)?.text = currentLabel
        if (drawerTabs.selectedTabPosition != index) {
            suppressTabReentry = true
            drawerTabs.getTabAt(index)?.let { drawerTabs.selectTab(it) }
            suppressTabReentry = false
        }
        showDrawerPage(if (index == 0) DrawerPage.HOME else DrawerPage.SECTION)
    }

    // ── NavHost view-mechanism (orchestrated by LauncherNavController) ───
    override fun navContext(): android.content.Context = this
    override fun setSectionTitle(label: String) { supportActionBar?.title = label }
    override fun invalidateMenu() = invalidateOptionsMenu()
    override fun dispatchTarget(target: String) = onTileClicked(target)
    override fun tabHaptic() = Haptics.tap(bottomNav)
    override fun closeDrawerIfOpen() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
    }
    override fun applyMode(mode: String) {
        modePrefs.mode = mode
        refreshBottomNavIconsForMode()
        invalidateOptionsMenu()
    }
    /** sw600dp tablet → master-detail split (see view_content_panes.xml).
     *  Declarative breakpoint via the bool resource qualifier — no dp math. */
    private val twoPane: Boolean by lazy { resources.getBoolean(R.bool.two_pane) }
    /** The detail pane only exists in the tablet layout; null on phones. */
    private val hasDetailPane: Boolean get() = findViewById<View?>(R.id.detail_container) != null
    override fun isTwoPane(): Boolean = twoPane && hasDetailPane

    override fun pushContent(content: Fragment) {
        // Tablet master-detail: opened pages render in the right-hand DETAIL
        // pane while the master (section grid) stays visible on the left.
        // They still go on the shared back stack so Back pops the detail
        // page first, exactly like the phone single-pane flow.
        val target = if (isTwoPane()) R.id.detail_container else R.id.fragment_container
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(target, content)
            .addToBackStack(null)
            .commit()
    }
    override fun recordSection(id: String, label: String, icon: String) {
        runCatching { com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).recordSection(id, label, icon) }
    }
    override fun recordPage(sectionId: String, pageId: String, label: String, icon: String) {
        runCatching {
            com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                .recordPage(sectionId, pageId, label = label, iconName = icon)
        }
    }
    override fun recordTarget(target: String, label: String, icon: String) {
        runCatching {
            com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                .recordTarget(target, label = label, iconName = icon)
        }
    }

    /** True while a [swapContent] section change is mid-flight — the
     *  clearBackStack pop fires the OnBackStackChangedListener BEFORE the
     *  content replace runs, so without this guard the listener sees the old
     *  Home3DFragment + an empty stack and wrongly resets currentSection to
     *  "home". Cleared via runOnCommit after the new content is in place. */
    private var inSectionSwap = false

    override fun swapContent(content: Fragment, clearBackStack: Boolean) {
        if (clearBackStack) {
            inSectionSwap = true
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
            // Clears AFTER the replace commits — by then the stale-Home3D pop
            // listener has already fired (and been skipped). A no-op swap with
            // clearBackStack=false never sets the flag, so this is safe.
            .runOnCommit { inSectionSwap = false }
            .commit()
        if (isTwoPane()) syncDetailPaneForMaster()
    }

    /**
     * Keep the tablet DETAIL pane consistent with the freshly-swapped MASTER:
     *   • Home → collapse the detail column (GONE). The master is then the
     *     only visible weighted child, so it expands to fill the full width
     *     and Home renders edge-to-edge like on phones.
     *   • Any section → show the detail column with the "Select an item"
     *     placeholder, ready for the first page the user opens from the grid.
     * Called only when [isTwoPane]; uses [currentSection] which the
     * controller sets before swapContent.
     */
    private fun syncDetailPaneForMaster() {
        val detail = findViewById<View>(R.id.detail_container) ?: return
        val divider = findViewById<View?>(R.id.pane_divider)
        if (currentSection == "home") {
            detail.visibility = View.GONE
            divider?.visibility = View.GONE
        } else {
            detail.visibility = View.VISIBLE
            divider?.visibility = View.VISIBLE
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_container, DetailPlaceholderFragment.newInstance())
                .commitAllowingStateLoss()
        }
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
    override fun applyChrome(fragment: Fragment) {
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
        // Pages that are pure action launchers (keyboard, update, import…) carry
        // an action string from build.json. Dispatch via onTileClicked so they
        // behave identically whether reached from the tab strip or a drawer item.
        if (page.action.isNotBlank()) {
            onTileClicked(page.action)
            return
        }
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

        // http(s):// → hand off to Cloud-Browser (external constellation APK).
        // Cloud-Browser's manifest declares http/https VIEW filter; a targeted
        // ACTION_VIEW routes straight to it when installed. If not yet installed,
        // launchExternalApp downloads + installs it first (URL lost, acceptable).
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                                     android.net.Uri.parse(uri))
            viewIntent.setPackage("com.diegonmarcos.cloudbrowser")
            val launched = runCatching { startActivity(viewIntent); true }.getOrElse { false }
            if (!launched) launchExternalApp("cloud-browser")
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

    /**
     * Open a companion app declared in build.json::ui.external_apps.
     *
     * Grammar: `extapp:<appId>/<forkKey>` (forkKey optional). Resolution,
     * in priority order — first hit wins:
     *   1. the specific fork package (forks[forkKey]),
     *   2. the hub package (hub_package),
     *   3. neither installed → download + install install_apk_url (a direct
     *      APK URL — Cloud-Comms-Hub.apk on a GitHub release) targeting
     *      install_package, via libs:updater's PackageInstaller flow.
     *
     * Per-fork launch degrades to the hub automatically: in Cloud-Comms'
     * one-icon model the forks ship without a launcher activity, so
     * getLaunchIntentForPackage returns null for them and we fall through
     * to the hub switcher — exactly the intended behaviour.
     */
    private fun launchExternalApp(payload: String) {
        val parts = payload.split("/", limit = 2)
        val app = Sections.externalApp(parts[0]) ?: run {
            findViewById<View>(R.id.fragment_container).snack("Unknown app: ${parts[0]}")
            return
        }
        val forkKey = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        // Candidate packages, most-specific first: the fork, then the hub.
        val candidates = listOfNotNull(forkKey?.let { app.forks[it] }, app.hubPackage)
            .filter { it.isNotBlank() }
        for (pkg in candidates) {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching {
                val lbl = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(app.label)
                com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).recordExternalApp(pkg, lbl)
                startActivity(launch); true
            }.getOrDefault(false)
            if (ok) return
        }
        // Nothing installed → install the companion APK (user-confirmed).
        if (app.installApkUrl.isBlank() || app.installPackage.isBlank()) {
            findViewById<View>(R.id.fragment_container).snack("${app.label} not installed")
            return
        }
        com.diegonmarcos.superapp.updater.Updater.installApk(
            this, app.installApkUrl, app.installPackage, app.label,
        )
        findViewById<View>(R.id.fragment_container)
            .snack("${app.label} not installed — downloading…")
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
        // Suite → Cloud "Recently Used" smart folder — the one real
        // per-tile signal Cloud tiles have (no pin/favorite/most-used
        // tracking exists otherwise). Recorded here, the single dispatch
        // chokepoint, so every surface that fires a tile click
        // contributes, not just the Suite page itself.
        RecentCloudTiles.recordOpen(this, tileId)
        // App Tabs capture — the single dispatch chokepoint. `action:`/`tab:`/
        // `mode:` destinations are real "pages" (Constellation, Suite·All, the
        // Suite tabs) but aren't recorded by goSection/openSectionPage, so they
        // never reached the Tabs shelf. If the target is a named page in the
        // build.json::ui.pages registry, record it here. `section:`/`page:`/
        // `extapp:` are deliberately excluded — their own paths already record.
        if (tileId.startsWith("action:") || tileId.startsWith("tab:") || tileId.startsWith("mode:")) {
            runCatching { Pages.byTarget(tileId)?.let { recordTarget(tileId, it.label, it.icon) } }
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
                val page = SectionPages.pagesFor(currentSection).firstOrNull { it.id == pid } ?: return
                val frag = page.factory.invoke()
                applyChrome(frag)
                supportFragmentManager.beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack(null)
                    .commit()
                // App Tabs: in-section sub-page navigations were never recorded
                // (only nav-routed section + deep-link pages were), so pages
                // inside Configs etc. never landed in the Tabs shelf. Record here.
                runCatching { recordPage(currentSection, pid, page.label, "") }
            }
            tileId.startsWith("action:") -> dispatchHomeAction(tileId.removePrefix("action:"))
            // mode:<apps|admin>:<sectionId> / tab:<cloud|phone>:<sectionId> —
            // long-press fan-menu targets (build.json::sections[*].long_press,
            // LauncherToolbarFx). Force a mode/Suite-tab then open the section.
            tileId.startsWith("mode:") -> {
                val (m, sec) = tileId.removePrefix("mode:").split(":", limit = 2)
                    .let { it[0] to it.getOrElse(1) { currentSection } }
                applyMode(m)
                goSection(sec, Sections.byId(sec)?.label ?: sec)
            }
            tileId.startsWith("tab:") -> {
                val (t, sec) = tileId.removePrefix("tab:").split(":", limit = 2)
                    .let { it[0] to it.getOrElse(1) { currentSection } }
                goSection(sec, Sections.byId(sec)?.label ?: sec, t)
            }
            // extapp:<appId>/<forkKey> — open a companion app (Cloud-Comms),
            // installing it from build.json::ui.external_apps if absent.
            tileId.startsWith("extapp:") -> launchExternalApp(tileId.removePrefix("extapp:"))
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
            // Constellation AppStore — Configs → Constellation.
            actionType == "constellation" -> {
                val frag = com.diegonmarcos.superapp.appstore.ConstellationFragment()
                applyChrome(frag)
                supportFragmentManager.beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack(null)
                    .commit()
            }
            // Config → Update tile: update EVERY constellation app. Opens the
            // Constellation page (per-app progress) and kicks Update-all. The
            // shared update overlay (with Cancel) shows install progress.
            actionType == "update_all" -> {
                dispatchHomeAction("constellation")
                kotlin.concurrent.thread {
                    val fleet = com.diegonmarcos.superapp.updater.Fleet
                        .parse(BuildConfig.CONSTELLATION_FLEET_B64)
                    com.diegonmarcos.superapp.updater.Fleet.installAll(
                        applicationContext, fleet, com.diegonmarcos.superapp.updater.Fleet.Mode.UPDATES)
                }
            }
            // Drawer "Home Apps" entry → open the same pull-up sheet the
            // home-screen swipe-up gesture shows.
            actionType == "open_home_apps" -> {
                if (currentSection != "home") goHome()
                openAppDrawerSheet()
            }
            // Same Home Apps drawer, opened on the Phone tab — legacy long-press
            // target (build.json::sections[suite].long_press).
            actionType == "open_home_apps_phone" -> {
                if (currentSection != "home") goHome()
                openAppDrawerSheet("phone")
            }
            // Cloud Notification Center — same surface the top-bar bell
            // icon opens. Wiring it here means any data-driven entry
            // (tile target / drawer action) can route to it without code.
            actionType == "open_notification_center" -> openNotificationCenter()
            // Configs → Keyboard now hands off to the standalone Cloud-Keyboard
            // app via extapp:cloud-keyboard (ui.external_apps[cloud-keyboard]);
            // the old in-APK keyboard_settings component launch is retired.
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
    fun openSectionPage(sectionId: String, pageId: String, args: Bundle? = null) =
        nav.openSectionPage(sectionId, pageId, args)

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
            .add(R.id.overlay_container, NotificationCenterFragment.newInstance(),
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
                } else if (!navigateUpToParent() && currentSection != "home") {
                    goHome()
                }
                return true
            }
            R.id.action_wallet -> {
                // Wallet moved to ea_cloud-wallet (constellation APK).
                // Launch it as an external app; installs from GHCR if absent.
                launchExternalApp("cloud-wallet")
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Back at a section ROOT (empty back stack): if the current section
     *  declares a parent (build.json::sections[*].parent), navigate UP to it
     *  instead of Home/exit. Data-driven — e.g. WireGuard → Configs. Returns
     *  true when it navigated to a parent. */
    private fun navigateUpToParent(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) return false
        val parentId = Sections.byId(currentSection)?.parent ?: return false
        goSection(parentId, Sections.byId(parentId)?.label ?: parentId)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else if (navigateUpToParent()) {
            // handled — landed on the parent section
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Pressing the system HOME button while we're the default launcher
        // re-delivers our launcher intent here (ACTION_MAIN + CATEGORY_HOME, no
        // shortcut_action). Other apps get sent to us; inside us it must reset to
        // the home page too — otherwise HOME is a no-op on non-home pages.
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) resetToHome()
        else handleShortcutIntent(intent)
    }

    /** Return to a clean Home root: close the drawer, drop any pushed pages and
     *  overlays, and select the home section. Mirrors what a fresh launch shows. */
    private fun resetToHome() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
        supportFragmentManager.popBackStackImmediate(null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        goHome()
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
            .add(R.id.overlay_container, SearchSheetFragment.newInstance(),
                SearchSheetFragment.BACK_STACK_TAG)
            .addToBackStack(SearchSheetFragment.BACK_STACK_TAG)
            .commit()
    }

    // ── DevControlBridge.ActivityHost ────────────────────────────────────

    /** Music-playing mini-island controller (lazy: allocated on first resume). */
    private val musicIsland by lazy { MusicIslandController(this) }

    // Foreground energy sampler — fine-resolution watchdog samples while
    // the screen is on / the app is resumed. Background coarse samples
    // come from BatterySessionWorker (15-min). Stopped in onPause so it
    // never runs while backgrounded.
    private val energySamplerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val energySamplerRunnable = object : Runnable {
        override fun run() {
            runCatching { EnergyWatchdog.sample(this@MainActivity) }
            energySamplerHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        // Configs → Launcher → Others: stars/pets live re-apply + system brightness.
        applyLauncherSettings()
        // Arm the screensaver idle timer.
        resetIdleTimer()
        // Floating Top Nav Bar — start the overlay service iff enabled in
        // build.json AND the user granted "display over other apps". The
        // service self-hides while we're foreground and surfaces the bubble
        // once the user leaves. Idempotent (START_STICKY); no-op otherwise.
        // hostForeground = reliable "we're in SuperApp" signal so the floating
        // circle never shows here (the in-app trigger is the Sirius Star).
        com.diegonmarcos.superapp.floatingnav.FloatingNavService.hostForeground = true
        com.diegonmarcos.superapp.floatingnav.FloatingNavService.startIfPermitted(this)
        com.diegonmarcos.superapp.devtools.DevControlBridge.register(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
        if (::toolbarFx.isInitialized) toolbarFx.resume()
        // Re-apply launcher chrome — user may have toggled us as the
        // default Home handler from system Settings while we were
        // backgrounded, and the system status bar / our strip need to
        // sync. Idempotent.
        applyLauncherChrome()
        musicIsland.resume()
        // Kick a sample now + every 60s while resumed.
        energySamplerHandler.removeCallbacks(energySamplerRunnable)
        energySamplerHandler.post(energySamplerRunnable)
    }

    override fun onPause() {
        // We're leaving SuperApp → the floating circle may now appear.
        com.diegonmarcos.superapp.floatingnav.FloatingNavService.hostForeground = false
        com.diegonmarcos.superapp.devtools.DevControlBridge.unregister(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener(null)
        if (::toolbarFx.isInitialized) toolbarFx.pause()
        musicIsland.pause()
        energySamplerHandler.removeCallbacks(energySamplerRunnable)
        idleHandler.removeCallbacks(idleRunnable)
        super.onPause()
    }

    // ── Screensaver idle timer (Configs → Launcher → Screensaver) ────────
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!com.diegonmarcos.superapp.floatingnav.ScreensaverService.isRunning) {
            runCatching {
                startService(android.content.Intent(this,
                    com.diegonmarcos.superapp.floatingnav.ScreensaverService::class.java))
            }
        }
    }

    /** (Re)arm the screensaver idle timer from the data-driven timeout
     *  (build.json::launcher_settings.screensaver_timeout, default 10s; 0 =
     *  never). Called on resume + every user interaction. */
    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        val secs = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).screensaverTimeout
        }.getOrDefault(0)
        if (secs > 0) idleHandler.postDelayed(idleRunnable, secs * 1000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
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
                            .add(R.id.overlay_container, frag, tag)
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
