package com.diegonmarcos.cloudnav

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.configs.ConfigsFragment
import com.diegonmarcos.cloudnav.maps.MapsTimelineTabsFragment
import com.diegonmarcos.cloudnav.places.PlacesFragment
import com.diegonmarcos.cloudnav.routes.NavigationFragment
import com.diegonmarcos.cloudnav.routes.RoutesFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Cloud Nav shell — Google-Maps-style chrome:
 *   • top search bar (multi-stop routing query) + island shortcut chips
 *   • content fragment container
 *   • bottom nav: Routes · Navigation · Timeline · Places · Configs
 *
 * Tabs, islands and the default tab are data-driven from build.json::ui.*
 * (decoded by [NavConfig]). Per-tab chrome is driven by [NavShellChild].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var topIsland: View
    private lateinit var searchBar: EditText
    private lateinit var islandRow: ChipGroup
    private lateinit var bottomNav: BottomNavigationView
    private var currentTab: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        topIsland = findViewById(R.id.top_island)
        searchBar = findViewById(R.id.search_bar)
        islandRow = findViewById(R.id.island_row)
        bottomNav = findViewById(R.id.bottom_nav)

        applyInsets()
        buildIslandChips()
        buildBottomNav()

        searchBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                (currentFragment() as? NavShellChild)?.onSearchSubmit(searchBar.text.toString())
                true
            } else false
        }

        if (savedInstanceState == null) {
            switchTo(NavConfig.defaultTab)
        } else {
            currentTab = savedInstanceState.getString(KEY_TAB, NavConfig.defaultTab)
            applyChromeFor(currentFragment())
            syncBottomNav(currentTab)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab)
    }

    // ── chrome / insets ──────────────────────────────────────────────
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topIsland.updatePadding(top = bars.top)
            bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun buildIslandChips() {
        islandRow.removeAllViews()
        NavConfig.islands.forEach { island ->
            val chip = Chip(this).apply {
                text = island.label
                isClickable = true
                isCheckable = false
                setChipIconResource(Icons.island(context, island.icon))
                setOnClickListener {
                    (currentFragment() as? NavShellChild)?.onIslandTap(island)
                }
            }
            islandRow.addView(chip)
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

    // ── navigation ───────────────────────────────────────────────────
    private fun switchTo(tabId: String) {
        currentTab = tabId
        val fragment = fragmentForTab(tabId)
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .runOnCommit { applyChromeFor(fragment) }
            .commit()
        syncBottomNav(tabId)
    }

    private fun fragmentForTab(id: String): Fragment = when (id) {
        "routes"     -> RoutesFragment()
        "navigation" -> NavigationFragment()
        "timeline"   -> MapsTimelineTabsFragment()
        "places"     -> PlacesFragment()
        "configs"    -> ConfigsFragment()
        else         -> RoutesFragment()
    }

    private fun applyChromeFor(fragment: Fragment?) {
        val child = fragment as? NavShellChild
        val wantsSearch = child?.wantsSearchBar() ?: true
        val wantsIslands = child?.wantsIslands() ?: true
        topIsland.visibility = if (wantsSearch) View.VISIBLE else View.GONE
        islandRow.visibility = if (wantsSearch && wantsIslands) View.VISIBLE else View.GONE
    }

    private fun syncBottomNav(tabId: String) {
        val idx = NavConfig.tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0 && bottomNav.selectedItemId != idx) bottomNav.selectedItemId = idx
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.content)

    private companion object { const val KEY_TAB = "cloud_nav_tab" }
}
