package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.diegonmarcos.superapp.updater.Updater

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var navigationView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.i(TAG, "onCreate enter, savedInstanceState=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)
        try {
            Trace.d(TAG, "setContentView R.layout.activity_main")
            setContentView(R.layout.activity_main)

            Trace.d(TAG, "findViewById toolbar")
            val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
            Trace.d(TAG, "setSupportActionBar")
            setSupportActionBar(toolbar)

            Trace.d(TAG, "findViewById drawer_layout / bottom_nav / navigation_view")
            drawerLayout = findViewById(R.id.drawer_layout)
            bottomNav = findViewById(R.id.bottom_nav)
            navigationView = findViewById(R.id.navigation_view)

            Trace.d(TAG, "ActionBarDrawerToggle setup")
            val toggle = ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close,
            )
            drawerLayout.addDrawerListener(toggle)
            toggle.syncState()

            Trace.d(TAG, "nav header build identifier")
            navigationView.getHeaderView(0).findViewById<TextView>(R.id.nav_header_build).text =
                "${BuildConfig.VERSION_NAME}  vc:${BuildConfig.VERSION_CODE}"

            Trace.d(TAG, "bottomNav.setOnItemSelectedListener")
            bottomNav.setOnItemSelectedListener { selectSection(it) }

            Trace.d(TAG, "navigationView.setNavigationItemSelectedListener")
            navigationView.setNavigationItemSelectedListener { handleDrawerItem(it) }

            if (savedInstanceState == null) {
                Trace.d(TAG, "initial section: nav_mail")
                bottomNav.selectedItemId = R.id.nav_mail
            }

            Trace.d(TAG, "Updater.start")
            Updater.start(applicationContext)

            Trace.i(TAG, "onCreate done — UI mounted successfully")
        } catch (t: Throwable) {
            Trace.e(TAG, "onCreate FAILED", t)
            throw t   // let CrashLogger capture the full stacktrace too
        }
    }

    private fun selectSection(item: MenuItem): Boolean {
        Trace.d(TAG, "selectSection itemId=${item.itemId} title=${item.title}")
        val (id, labelRes) = when (item.itemId) {
            R.id.nav_mail  -> "mail"  to R.string.section_mail
            R.id.nav_feed  -> "feed"  to R.string.section_feed
            R.id.nav_chat  -> "chat"  to R.string.section_chat
            R.id.nav_cal   -> "cal"   to R.string.section_cal
            R.id.nav_vault -> "vault" to R.string.section_vault
            // WG (build.json::ui.sections.wg.bottom_nav=false) is drawer-only —
            // BottomNavigationView caps at 5 items. Reachable via the drawer's
            // WG group children (handleDrawerItem swaps the fragment).
            else -> {
                Trace.w(TAG, "unknown bottom-nav item ${item.itemId}")
                return false
            }
        }
        switchToSection(id, getString(labelRes))
        return true
    }

    /** Single entry point — both bottom-nav and drawer can swap section. */
    private fun switchToSection(id: String, label: String) {
        Trace.d(TAG, "switchToSection id=$id label=$label")
        supportActionBar?.title = label
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SectionFragment.forSection(id, label))
            .commit()
    }

    private fun handleDrawerItem(item: MenuItem): Boolean {
        Trace.d(TAG, "handleDrawerItem id=${item.itemId} title=${item.title}")
        drawerLayout.closeDrawer(GravityCompat.START)
        when (item.itemId) {
            R.id.drawer_check_updates -> {
                Updater.checkNow(applicationContext)
                Toast.makeText(this, R.string.check_updates_started, Toast.LENGTH_SHORT).show()
            }
            // WG children → switch to WG section (drawer-only section).
            R.id.drawer_wg_tunnels, R.id.drawer_wg_import ->
                switchToSection("wg", getString(R.string.section_wg))
            // C3 children → switch to C3 / ops section (drawer-only).
            R.id.drawer_c3_reports, R.id.drawer_c3_stack,
            R.id.drawer_c3_health,  R.id.drawer_c3_workflows ->
                switchToSection("c3", getString(R.string.section_c3))
            else -> Toast.makeText(this, "drawer → ${item.title}", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            Trace.d(TAG, "back closes drawer")
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
