package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
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
    HomeDrawerFragment.NavigationItemListener,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.i(TAG, "onCreate enter")
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            currentLabel = getString(R.string.section_home)

            val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
            setSupportActionBar(toolbar)

            drawerLayout = findViewById(R.id.drawer_layout)
            bottomNav = findViewById(R.id.bottom_nav)
            drawerTabs = findViewById(R.id.drawer_tabs)
            drawerPageTabs = findViewById(R.id.drawer_page_tabs)

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

            Updater.start(applicationContext)
            Trace.i(TAG, "onCreate done")
        } catch (t: Throwable) {
            Trace.e(TAG, "onCreate FAILED", t)
            throw t
        }
    }

    // ── bottom nav ────────────────────────────────────────────────────────

    private fun onBottomNavPicked(item: MenuItem): Boolean {
        if (suppressBottomNavReentry) return true
        val id = sectionIdForNavId(item.itemId) ?: return false
        if (id == "home") goHome() else goSection(id, Sections.byId(id)?.label ?: id)
        return true
    }

    private fun sectionIdForNavId(navId: Int): String? = when (navId) {
        R.id.nav_mail  -> "mail"
        R.id.nav_feed  -> "feed"
        R.id.nav_home  -> "home"
        R.id.nav_cal   -> "cal"
        R.id.nav_vault -> "vault"
        else -> null
    }

    private fun idForSectionId(id: String): Int? = when (id) {
        "mail"  -> R.id.nav_mail
        "feed"  -> R.id.nav_feed
        "home"  -> R.id.nav_home
        "cal"   -> R.id.nav_cal
        "vault" -> R.id.nav_vault
        else    -> null
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

        val tiles = Sections.all()
            .filter { !it.isMasterIndex }
            .map { sec ->
                TileGridFragment.Tile(
                    id = "section:${sec.id}",
                    label = sec.label,
                    iconRes = Sections.iconResFor(this, sec.iconName),
                )
            }
        swapContent(TileGridFragment.newInstance(currentLabel, tiles), clearBackStack = true)

        syncBottomNav("home")
        syncDrawerTab(0)
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
            section.pages.isNotEmpty() -> TileGridFragment.newInstance(
                title = label,
                tiles = section.pages.map { p ->
                    TileGridFragment.Tile(
                        id = "page:${p.id}",
                        label = p.label,
                        iconRes = 0,
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, content)
            .runOnCommit { applyChrome(content) }
            .commit()
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
        lp.bottomMargin = if (ownsBottom) 0 else (56 * dp).toInt()
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
                val pages = SectionPages.pagesFor(currentSection)
                if (pages.isEmpty()) {
                    drawerPageTabs.visibility = View.GONE
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.drawer_content, PlaceholderDrawerFragment.newInstance(currentLabel))
                        .commitAllowingStateLoss()
                } else {
                    bindPageTabs(pages)
                    drawerPageTabs.visibility = View.VISIBLE
                    showDrawerSectionPage(pages.first())
                }
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
                val pid = tileId.removePrefix("page:")
                val frag = SectionPages.pagesFor(currentSection).firstOrNull { it.id == pid }?.factory?.invoke()
                    ?: return
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag)
                    .runOnCommit { applyChrome(frag) }
                    .addToBackStack(null)
                    .commit()
            }
            tileId.startsWith("stub:") ->
                Toast.makeText(this, tileId, Toast.LENGTH_SHORT).show()
        }
    }

    // ── MailHost (libs:mail → shell bridge) ──────────────────────────────

    override fun openMailPage(pageId: String, args: Bundle?) {
        val frag = MailPages.fragmentFor(pageId, args)
        if (currentSection != "mail") {
            currentSection = "mail"
            currentLabel = getString(R.string.section_mail)
            syncBottomNav("mail")
            syncDrawerTab(1)
        }
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .runOnCommit { applyChrome(frag) }
            .addToBackStack(null)
            .commit()
    }

    // ── HomeDrawerFragment delegate ──────────────────────────────────────

    override fun onDrawerItemSelected(item: MenuItem): Boolean {
        drawerLayout.closeDrawer(GravityCompat.START)
        when (item.itemId) {
            R.id.drawer_check_updates -> {
                Updater.checkNow(applicationContext)
                Toast.makeText(this, R.string.check_updates_started, Toast.LENGTH_SHORT).show()
            }
            R.id.drawer_wg_tunnels, R.id.drawer_wg_import ->
                goSection("wg", getString(R.string.section_wg))
            R.id.drawer_c3_reports, R.id.drawer_c3_stack,
            R.id.drawer_c3_health,  R.id.drawer_c3_workflows ->
                goSection("c3", getString(R.string.section_c3))
            else -> Toast.makeText(this, "drawer → ${item.title}", Toast.LENGTH_SHORT).show()
        }
        return true
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
