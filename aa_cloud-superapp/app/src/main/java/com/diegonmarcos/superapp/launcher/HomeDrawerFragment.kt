package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.profile.ProfilePrefs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView

/**
 * "Home" drawer page — the all-sections index. Menu is built programmatically
 * from [Sections] so it mirrors `build.json::ui.sections[*].pages[*]` and
 * `ui.home_actions`. Single source of truth; the drawer has no hardcoded
 * Kotlin/XML inventory.
 *
 * Each top-level group is a section; its sub-items are that section's pages
 * (or `drawer_default_children` as fallback when no pages[] is declared).
 * Selecting a sub-item bubbles up via [NavigationListener] so the host
 * Activity can open the right page in the content pane.
 */
class HomeDrawerFragment : Fragment() {

    interface NavigationListener {
        fun onDrawerSectionSelected(sectionId: String, label: String)
        fun onDrawerPageSelected(sectionId: String, pageId: String, label: String)
        fun onDrawerActionSelected(actionType: String)
        /** Drawer swap-icon tap → flip Apps↔Admin globally and refresh
         *  the visible section so the new mode's tiles render. */
        fun onDrawerModeToggle()
        /** Drawer identity-row tap (anywhere EXCEPT the swap icon) → open
         *  the Virtual Business Card screen. */
        fun onDrawerBusinessCardOpen()
    }

    private sealed class Target {
        data class Section(val id: String, val label: String) : Target()
        data class Page(val sectionId: String, val pageId: String, val label: String) : Target()
        data class Action(val actionType: String) : Target()
    }

    private val dispatch = mutableMapOf<Int, Target>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val nav = view.findViewById<NavigationView>(R.id.navigation_view)

        val header = nav.getHeaderView(0)
        val buildLine = "${BuildConfig.VERSION_NAME}  vc:${BuildConfig.VERSION_CODE}"
        val tsLine = BuildConfig.BUILD_TIMESTAMP
        header?.findViewById<TextView>(R.id.nav_header_build)?.text = buildLine
        header?.findViewById<TextView>(R.id.nav_header_timestamp)?.text = tsLine

        // Tap on the header strip → trigger the in-app updater check;
        // long-press → copy the full build descriptor to the clipboard.
        header?.findViewById<android.view.View>(R.id.nav_header_root)?.apply {
            setOnClickListener {
                (activity as? NavigationListener)?.onDrawerActionSelected("check_updates")
            }
            setOnLongClickListener {
                val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clip?.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Cloud SuperApp build", "$buildLine\n$tsLine"))
                android.widget.Toast.makeText(ctx, "Build info copied", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }

        // User-banner row — avatar (initials) + 3 stacked text lines:
        // [Name]  /  [Email]  /  Mode: {Apps|Admin} + toggle glyph.
        // The whole row is the click target; tap flips Apps↔Admin. Bound
        // from ProfilePrefs + ModePrefs so it always reflects current
        // state — works even when the drawer is re-opened after the user
        // edited Configs → Profile.
        val userRow  = header?.findViewById<View>(R.id.nav_header_user_row)
        val avatar   = header?.findViewById<TextView>(R.id.nav_header_avatar)
        val nameTv   = header?.findViewById<TextView>(R.id.nav_header_user_name)
        val emailTv  = header?.findViewById<TextView>(R.id.nav_header_user_email)
        val modeTv   = header?.findViewById<TextView>(R.id.nav_header_user_mode)
        val swapBtn  = header?.findViewById<android.widget.ImageView>(R.id.nav_header_swap)
        if (userRow != null && avatar != null && nameTv != null && emailTv != null && modeTv != null) {
            fun rebind() {
                val profile = ProfilePrefs(ctx)
                val mode    = ModePrefs(ctx).mode
                val modeLabel = if (mode == "admin") "Admin" else "Apps"
                avatar.text  = profile.initials
                nameTv.text  = profile.name
                emailTv.text = profile.email
                modeTv.text  = "Mode: $modeLabel"
            }
            rebind()
            // Narrower hit-target: only the round AVATAR (the circle with
            // the initials) opens the Virtual Business Card. The rest of
            // the userRow — name / email / mode text — is no longer
            // tappable, so casual interaction with the drawer header
            // doesn't accidentally trigger the BC screen.
            avatar.isClickable = true
            avatar.isFocusable  = true
            avatar.setOnClickListener {
                Haptics.tap(it)
                (activity as? NavigationListener)?.onDrawerBusinessCardOpen()
            }
            // The Apps↔Admin global swap icon is RETIRED. Apps and Admin
            // are ordinary PAGES of the sections that have both (Infos,
            // Tools, Communication) — pick one from the section grid,
            // the fan menu or the Sirius ring. Hide the swap icon so the drawer
            // identity row stays a pure profile row. The
            // NavigationListener.onDrawerModeToggle hook is kept for
            // backwards-compatibility in case any other surface
            // triggers it programmatically.
            swapBtn?.visibility = View.GONE
        }

        val menu = nav.menu
        menu.clear()
        dispatch.clear()
        var id = MENU_BASE

        // Prepend entries (build.json::ui.home_drawer_prepend) — render
        // ABOVE the first section so quick-access items (Home Apps sheet,
        // …) stay above the alphabetical section list. Same dispatch as
        // home_actions.
        val prependGroupId = id++
        for (action in Sections.homeDrawerPrepend()) {
            val actId = id++
            val actItem = menu.add(prependGroupId, actId, Menu.NONE, action.label)
            Sections.iconResFor(ctx, action.iconName).takeIf { it != 0 }
                ?.let { actItem.setIcon(it) }
            actItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
            dispatch[actId] = Target.Action(action.actionType)
        }

        // Drawer mirrors the swipe-up Home Apps view 1:1 — same
        // `home_groups` schema, same per-group ordering, same tile ids.
        // Each group becomes a NavigationView sub-menu whose title is
        // rendered as a non-clickable section header; each tile under
        // it is a clickable MenuItem. Single source of truth for both
        // surfaces is build.json::ui.home_groups — change there to
        // reshape both at once.
        val mode = ModePrefs(ctx).mode
        // Icon resolution: when a tile's id is `section:X`, the section's
        // iconForMode(mode) wins so drawer / bottom-nav / sheet all show
        // the same glyph per mode. For `page:S/P` tiles the page's icon
        // is used; everything else falls back to the tile's own icon.
        fun iconForTile(tile: Sections.HomeTile): String {
            if (tile.id.startsWith("section:")) {
                Sections.byId(tile.id.removePrefix("section:"))?.let {
                    return it.iconForMode(mode)
                }
            }
            return tile.iconForMode(mode)
        }
        for (group in Sections.homeGroups()) {
            val groupId = id++
            val sub = menu.addSubMenu(groupId, Menu.NONE, Menu.NONE, group.title)
            for (tile in group.tiles) {
                val tileId = id++
                val item = sub.add(groupId, tileId, Menu.NONE, tile.label)
                Sections.iconResFor(ctx, iconForTile(tile)).takeIf { it != 0 }
                    ?.let { item.setIcon(it) }
                item.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                // Tile id format mirrors HomeGroupedFragment / MainActivity:
                //   "section:<X>"        → switch to section X
                //   "page:<sec>/<page>"  → deep-link to that page
                dispatch[tileId] = when {
                    tile.id.startsWith("section:") -> Target.Section(
                        tile.id.removePrefix("section:"), tile.label,
                    )
                    tile.id.startsWith("page:") -> {
                        val rest = tile.id.removePrefix("page:")
                        val slash = rest.indexOf('/')
                        if (slash > 0) Target.Page(
                            rest.substring(0, slash),
                            rest.substring(slash + 1),
                            tile.label,
                        ) else Target.Section(rest, tile.label)
                    }
                    tile.id.startsWith("action:") -> Target.Action(tile.id.removePrefix("action:"))
                    else -> Target.Section(tile.id, tile.label)
                }

                // One extra depth level: when the tile is `section:X`,
                // expand its first-level pages as indented siblings inside
                // the same sub-menu. Each page becomes its own tappable
                // MenuItem that deep-links to that page. `page:` tiles
                // don't get expanded (they're already a leaf), and free-
                // form tiles have no pages by definition.
                if (tile.id.startsWith("section:")) {
                    val sid = tile.id.removePrefix("section:")
                    val section = Sections.byId(sid) ?: continue
                    for (page in section.pages) {
                        val pageItemId = id++
                        // Visual indent so children read clearly as
                        // children of their parent tile. NBSP (U+00A0) is
                        // used instead of regular spaces because
                        // NavigationView's MenuItem title trims/collapses
                        // leading whitespace in some Material themes;
                        // NBSP survives that. The "└─" prefix + extra
                        // indent matches a typical tree-view visual.
                        val indented = "      └─── ${page.label}"
                        val pageItem = sub.add(groupId, pageItemId, Menu.NONE, indented)
                        page.iconName?.let {
                            Sections.iconResFor(ctx, it).takeIf { r -> r != 0 }
                                ?.let { r -> pageItem.setIcon(r) }
                        }
                        pageItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                        dispatch[pageItemId] = Target.Page(section.id, page.id, page.label)
                    }
                }
            }
        }
    }

    private fun onItemPicked(itemId: Int) {
        val listener = activity as? NavigationListener ?: return
        when (val t = dispatch[itemId] ?: return) {
            is Target.Section -> listener.onDrawerSectionSelected(t.id, t.label)
            is Target.Page    -> listener.onDrawerPageSelected(t.sectionId, t.pageId, t.label)
            is Target.Action  -> listener.onDrawerActionSelected(t.actionType)
        }
    }

    companion object {
        private const val MENU_BASE = 10_000
        fun newInstance() = HomeDrawerFragment()
    }
}
