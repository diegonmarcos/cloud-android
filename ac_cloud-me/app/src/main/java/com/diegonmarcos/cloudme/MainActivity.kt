package com.diegonmarcos.cloudme

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView

/**
 * Single-activity shell: hamburger drawer, toolbar with a Configs gear, a
 * content host and a bottom bar — the SuperApp's shape, at a fraction of its
 * size, because Cloud Me has no launcher, no home index and no modes.
 *
 * Both menus are built here from [Sections] rather than inflated from
 * res/menu. That is the point of the app: the bar, the drawer and the tab
 * strips are one JSON list rendered three ways, so they cannot disagree.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    /** Section currently on screen — the back handler's only state. */
    private var currentSection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer_layout)
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottom_nav)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }

        buildBottomNav()
        buildDrawer()
        installBackHandler()

        if (savedInstanceState == null) {
            Sections.default()?.let { open(it.id) }
        } else {
            currentSection = savedInstanceState.getString(STATE_SECTION)
            supportActionBar?.title = Sections.byId(currentSection)?.label ?: getString(R.string.app_name)
        }

        // Self-update from GHCR: the periodic check plus a one-shot shortly
        // after launch, because periodic alone defers the first pass by a full
        // interval on a fresh install.
        Updater.start(this)
        UpdateProgress.setListener { state -> runOnUiThread { handleUpdateState(state) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, currentSection)
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

    // ── menus, built from build.json ─────────────────────────────────

    private fun buildBottomNav() {
        val menu = bottomNav.menu
        menu.clear()
        // Item ids are 1-based: 0 is Menu.NONE, which BottomNavigationView
        // also uses to mean "nothing selected", so a zero-id first tab can
        // never be shown as checked.
        Sections.bottom().forEachIndexed { index, s ->
            menu.add(Menu.NONE, index + 1, index, s.label).apply {
                iconRes(s.icon)?.let { setIcon(it) }
            }
        }
        bottomNav.setOnItemSelectedListener { item ->
            select(Sections.bottom().getOrNull(item.itemId - 1))
        }
    }

    private fun buildDrawer() {
        val nav = findViewById<NavigationView>(R.id.nav_drawer)
        val menu = nav.menu
        menu.clear()
        val drawerSections = Sections.drawer()
        drawerSections.forEachIndexed { index, s ->
            menu.add(Menu.NONE, index + 1, index, s.label).apply {
                iconRes(s.icon)?.let { setIcon(it) }
            }
        }
        nav.setNavigationItemSelectedListener { item ->
            drawer.closeDrawer(GravityCompat.START)
            select(drawerSections.getOrNull(item.itemId - 1))
        }
        nav.getHeaderView(0)?.let { header ->
            header.findViewById<TextView>(R.id.header_name)?.text =
                BuildConfig.UI_PROFILE_NAME.ifBlank { getString(R.string.app_name) }
            header.findViewById<TextView>(R.id.header_email)?.text = BuildConfig.UI_PROFILE_EMAIL
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // The gear is a section like any other; it is only drawn in the
        // toolbar instead of the bar because Configs is the one destination
        // you want reachable from wherever you already are.
        val cfg = Sections.toolbarSection() ?: return false
        menu.add(Menu.NONE, MENU_CONFIGS, 0, cfg.label).apply {
            iconRes(cfg.icon)?.let { setIcon(it) }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CONFIGS) {
            Sections.toolbarSection()?.let { open(it.id) }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── navigation ───────────────────────────────────────────────────

    /** One tap on a bar or drawer item. A section with a `target` launches
     *  something else and reports "not selected", so the bar keeps its
     *  highlight on the page you are still on and will come back to. */
    private fun select(section: Section?): Boolean = when {
        section == null -> false
        section.target.isNotBlank() -> { onTarget(section.target); false }
        else -> { open(section.id); true }
    }

    /** The single entry point for showing a section. Everything — bar, drawer,
     *  gear, tile targets, restored state — comes through here. */
    fun open(sectionId: String, pageId: String? = null) {
        val section = Sections.byId(sectionId) ?: return
        currentSection = section.id
        supportActionBar?.title = section.label
        supportFragmentManager.commit {
            replace(R.id.fragment_container, SectionFragment.newInstance(section.id, pageId))
        }
        // Keep the bar's highlight honest when the section was reached from
        // the drawer or a tile: an off-bar section leaves nothing checked.
        val barIndex = Sections.bottom().indexOfFirst { it.id == section.id }
        if (barIndex >= 0 && bottomNav.selectedItemId != barIndex + 1) {
            bottomNav.menu.findItem(barIndex + 1)?.isChecked = true
        } else if (barIndex < 0) {
            bottomNav.menu.setGroupCheckable(Menu.NONE, true, false)
            for (i in 0 until bottomNav.menu.size()) bottomNav.menu.getItem(i).isChecked = false
            bottomNav.menu.setGroupCheckable(Menu.NONE, true, true)
        }
    }

    /**
     * Resolves one tile/link target. Three grammars, matching the SuperApp's:
     * `page:<section>/<page>` inside this app, `extapp:<id>` to another
     * constellation member, anything starting http(s) to the browser.
     */
    fun onTarget(target: String) {
        when {
            target.startsWith("page:") -> {
                val path = target.removePrefix("page:")
                open(path.substringBefore('/'), path.substringAfter('/', "").ifBlank { null })
            }
            target.startsWith("section:") -> open(target.removePrefix("section:"))
            target.startsWith("extapp:") -> ExternalApps.launch(this, target)
            target.startsWith("http") -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            }
            else -> Unit
        }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val home = Sections.default()?.id
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    // Back from anywhere else lands on the first bar section
                    // before it leaves the app — the same one-step-home rule
                    // the SuperApp uses, so Back never exits from deep inside.
                    currentSection != null && currentSection != home && home != null -> open(home)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    // ── self-update overlay ──────────────────────────────────────────

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        when (state) {
            is UpdateProgress.State.Idle -> frag?.let { supportFragmentManager.commit { remove(it) } }
            else -> if (frag == null) {
                supportFragmentManager.commit {
                    add(android.R.id.content,
                        com.diegonmarcos.superapp.updater.UpdateOverlayFragment.newInstance(), tag)
                }
            } else {
                (frag as? com.diegonmarcos.superapp.updater.UpdateOverlayFragment)?.applyState(state)
            }
        }
    }

    private fun iconRes(name: String): Int? {
        if (name.isBlank()) return null
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier(name, "drawable", packageName)
        return if (id == 0) null else id
    }

    private companion object {
        const val MENU_CONFIGS = 9001
        const val STATE_SECTION = "section"
    }
}
