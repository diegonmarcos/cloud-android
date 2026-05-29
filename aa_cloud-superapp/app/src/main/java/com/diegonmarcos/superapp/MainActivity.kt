package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.mail.MailFragment
import com.diegonmarcos.superapp.mail.MailDrawerFragment

/**
 * Top-level shell. Drawer is paginated via two tabs:
 *   [Home]                  — all-sections index (HomeDrawerFragment)
 *   [<currentSectionLabel>] — section's own drawer fragment (real UI per
 *                              module; PlaceholderDrawerFragment until the
 *                              section grows one)
 *
 * When the user switches sections (bottom nav or drawer item), the second
 * tab's label updates and that tab's fragment is rebuilt from the
 * drawerFragmentFor(...) factory.
 */
class MainActivity : AppCompatActivity(), HomeDrawerFragment.NavigationItemListener {

    private val TAG = "MainActivity"
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var drawerTabs: TabLayout

    /** Most-recently-opened section. Drives the second drawer tab + content. */
    private var currentSection: String = "mail"
    private var currentLabel:   String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.i(TAG, "onCreate enter")
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            currentLabel = getString(R.string.section_mail)

            val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
            setSupportActionBar(toolbar)

            drawerLayout = findViewById(R.id.drawer_layout)
            bottomNav = findViewById(R.id.bottom_nav)
            drawerTabs = findViewById(R.id.drawer_tabs)

            val toggle = ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close,
            )
            drawerLayout.addDrawerListener(toggle)
            toggle.syncState()

            // Drawer tabs: [Home] [<currentSection>]. Second tab text is
            // updated every time the user opens a section, so the drawer's
            // "app" page always corresponds to the visible content area.
            drawerTabs.addTab(drawerTabs.newTab().setText(getString(R.string.drawer_tab_home)))
            drawerTabs.addTab(drawerTabs.newTab().setText(currentLabel))
            drawerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showDrawerPage(if (tab.position == 0) DrawerPage.HOME else DrawerPage.SECTION)
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            bottomNav.setOnItemSelectedListener { selectSection(it) }

            if (savedInstanceState == null) {
                bottomNav.selectedItemId = R.id.nav_mail   // sets currentSection + content
                showDrawerPage(DrawerPage.HOME)            // start with the index
            }

            Updater.start(applicationContext)
            Trace.i(TAG, "onCreate done")
        } catch (t: Throwable) {
            Trace.e(TAG, "onCreate FAILED", t)
            throw t
        }
    }

    // ── section content area (bottom nav + drawer parents) ─────────────────

    private fun selectSection(item: MenuItem): Boolean {
        val (id, labelRes) = when (item.itemId) {
            R.id.nav_mail  -> "mail"  to R.string.section_mail
            R.id.nav_feed  -> "feed"  to R.string.section_feed
            R.id.nav_chat  -> "chat"  to R.string.section_chat
            R.id.nav_cal   -> "cal"   to R.string.section_cal
            R.id.nav_vault -> "vault" to R.string.section_vault
            else -> return false
        }
        switchToSection(id, getString(labelRes))
        return true
    }

    private fun switchToSection(id: String, label: String) {
        Trace.d(TAG, "switchToSection id=$id label=$label")
        currentSection = id
        currentLabel = label
        supportActionBar?.title = label

        // Content area: real fragment per section, placeholder otherwise.
        val content: Fragment = when (id) {
            "mail" -> MailFragment.newInstance()
            else   -> SectionFragment.forSection(id, label)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, content)
            .commit()

        // Drawer's second tab label tracks the section.
        drawerTabs.getTabAt(1)?.text = label
        // If the user is currently looking at the "section" page in the drawer,
        // rebuild it for the new section.
        if (drawerTabs.selectedTabPosition == 1) showDrawerPage(DrawerPage.SECTION)
    }

    // ── drawer pagination ──────────────────────────────────────────────────

    private enum class DrawerPage { HOME, SECTION }

    private fun showDrawerPage(page: DrawerPage) {
        val fragment: Fragment = when (page) {
            DrawerPage.HOME    -> HomeDrawerFragment.newInstance()
            DrawerPage.SECTION -> drawerFragmentFor(currentSection, currentLabel)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.drawer_content, fragment)
            .commitAllowingStateLoss()
    }

    /** Map section id → its drawer fragment. Each libs:<x>/ module provides
     *  its own; sections without one fall back to PlaceholderDrawerFragment. */
    private fun drawerFragmentFor(sectionId: String, label: String): Fragment = when (sectionId) {
        "mail" -> MailDrawerFragment.newInstance()
        else   -> PlaceholderDrawerFragment.newInstance(label)
    }

    // ── HomeDrawerFragment delegate ───────────────────────────────────────

    override fun onDrawerItemSelected(item: MenuItem): Boolean {
        Trace.d(TAG, "drawer item ${item.itemId} ${item.title}")
        drawerLayout.closeDrawer(GravityCompat.START)
        when (item.itemId) {
            R.id.drawer_check_updates -> {
                Updater.checkNow(applicationContext)
                Toast.makeText(this, R.string.check_updates_started, Toast.LENGTH_SHORT).show()
            }
            R.id.drawer_wg_tunnels, R.id.drawer_wg_import ->
                switchToSection("wg", getString(R.string.section_wg))
            R.id.drawer_c3_reports, R.id.drawer_c3_stack,
            R.id.drawer_c3_health,  R.id.drawer_c3_workflows ->
                switchToSection("c3", getString(R.string.section_c3))
            else -> Toast.makeText(this, "drawer → ${item.title}", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
