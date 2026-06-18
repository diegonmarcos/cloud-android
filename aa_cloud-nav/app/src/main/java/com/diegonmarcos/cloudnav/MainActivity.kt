package com.diegonmarcos.cloudnav

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.configs.ConfigsFragment
import com.diegonmarcos.cloudnav.maps.MapsTimelineTabsFragment
import com.diegonmarcos.cloudnav.updater.Updater
import com.diegonmarcos.cloudnav.places.PlacesFragment
import com.diegonmarcos.cloudnav.routes.NavigationFragment
import com.diegonmarcos.cloudnav.routes.RoutesFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Cloud Nav shell — minimal Google-Maps-style chrome:
 *   • a content fragment container (each page draws its own search UI)
 *   • bottom nav: Routes · Navigation · Places · Timeline · Configs
 *
 * Tabs + default tab are data-driven from build.json::ui.* (decoded by
 * [NavConfig]). The shell deliberately owns NO search bar — Routes does
 * multi-stop routing, Navigation a destination field, Places a simple POI
 * lookup with category islands. Each fragment renders the search UI it needs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: View
    private lateinit var bottomNav: BottomNavigationView
    private var currentTab: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        bottomNav = findViewById(R.id.bottom_nav)

        applyInsets()
        buildBottomNav()

        // Schedule the periodic GHCR self-update check (data-driven cadence).
        // The Update tab in Configs offers a manual check-now button.
        Updater.start(this)

        if (savedInstanceState == null) {
            switchTo(NavConfig.defaultTab)
        } else {
            currentTab = savedInstanceState.getString(KEY_TAB, NavConfig.defaultTab)
            syncBottomNav(currentTab)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(top = bars.top)
            bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun buildBottomNav() {
        val menu = bottomNav.menu
        menu.clear()
        NavConfig.tabs.forEachIndexed { i, tab ->
            menu.add(0, i, i, tab.label).setIcon(Icons.nav(this, tab.icon))
        }
        bottomNav.setOnItemSelectedListener { item ->
            val tab = NavConfig.tabs.getOrNull(item.itemId) ?: return@setOnItemSelectedListener false
            if (tab.id != currentTab) switchTo(tab.id)
            true
        }
    }

    private fun switchTo(tabId: String) {
        currentTab = tabId
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragmentForTab(tabId))
            .commit()
        syncBottomNav(tabId)
    }

    private fun fragmentForTab(id: String): Fragment = when (id) {
        "routes"     -> RoutesFragment()
        "navigation" -> NavigationFragment()
        "places"     -> PlacesFragment()
        "timeline"   -> MapsTimelineTabsFragment()
        "configs"    -> ConfigsFragment()
        else         -> RoutesFragment()
    }

    private fun syncBottomNav(tabId: String) {
        val idx = NavConfig.tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0 && bottomNav.selectedItemId != idx) bottomNav.selectedItemId = idx
    }

    private companion object { const val KEY_TAB = "cloud_nav_tab" }
}
