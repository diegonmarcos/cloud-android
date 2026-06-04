package com.diegonmarcos.superapp

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
            // Tap anywhere EXCEPT the swap icon → open the Virtual
            // Business Card screen (the swap icon swallows its own taps
            // via its own listener below).
            userRow.setOnClickListener {
                Haptics.tap(it)
                (activity as? NavigationListener)?.onDrawerBusinessCardOpen()
            }
            // Swap icon → flip Apps↔Admin. Doesn't propagate to userRow.
            swapBtn?.setOnClickListener {
                Haptics.tap(it)
                (activity as? NavigationListener)?.onDrawerModeToggle()
                rebind()
            }
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
        for (group in Sections.homeGroups()) {
            val groupId = id++
            val sub = menu.addSubMenu(groupId, Menu.NONE, Menu.NONE, group.title)
            for (tile in group.tiles) {
                val tileId = id++
                val item = sub.add(groupId, tileId, Menu.NONE, tile.label)
                Sections.iconResFor(ctx, tile.iconName).takeIf { it != 0 }
                    ?.let { item.setIcon(it) }
                item.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                // Tile id format mirrors HomeGroupedFragment / MainActivity:
                //   "section:<X>"        → switch to section X
                //   "page:<sec>/<page>"  → deep-link to that page
                // Anything else falls back to a section dispatch with the
                // raw id so legacy entries don't lose their click target.
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
                    else -> Target.Section(tile.id, tile.label)
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
