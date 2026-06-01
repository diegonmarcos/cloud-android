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
        /** Drawer identity row tap → flip Apps↔Admin globally and refresh
         *  the visible section so the new mode's tiles render. */
        fun onDrawerModeToggle()
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
            userRow.setOnClickListener {
                Haptics.tap(it)
                (activity as? NavigationListener)?.onDrawerModeToggle()
                rebind()
            }
        }

        val menu = nav.menu
        menu.clear()
        dispatch.clear()
        var id = MENU_BASE

        // Per-section group keeps NavigationView's automatic divider between
        // sections AND lets the section title row itself be a tappable
        // MenuItem (addSubMenu would render it as a non-clickable group
        // header instead). Pages render as indented siblings within the
        // same group — visual hierarchy via "    " prefix.
        for (section in Sections.all().filter { !it.isMasterIndex }) {
            val groupId = id++

            // Section title row — tappable, navigates to the section index.
            val sectionItemId = id++
            val sectionItem = menu.add(groupId, sectionItemId, Menu.NONE, section.label)
            Sections.iconResFor(ctx, section.iconName).takeIf { it != 0 }
                ?.let { sectionItem.setIcon(it) }
            sectionItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
            dispatch[sectionItemId] = Target.Section(section.id, section.label)

            // First-level pages only — section.pages, NOT page.subPages.
            // Per user request the drawer expands one level deep so the
            // site map stays scannable.
            if (section.pages.isNotEmpty()) {
                for (page in section.pages) {
                    val pageItemId = id++
                    val pageItem = menu.add(groupId, pageItemId, Menu.NONE, "    ${page.label}")
                    page.iconName?.let {
                        Sections.iconResFor(ctx, it).takeIf { r -> r != 0 }
                            ?.let { r -> pageItem.setIcon(r) }
                    }
                    pageItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                    dispatch[pageItemId] = Target.Page(section.id, page.id, page.label)
                }
            } else {
                for ((idx, label) in section.defaultChildren.withIndex()) {
                    val pageItemId = id++
                    val pageItem = menu.add(groupId, pageItemId, Menu.NONE, "    $label")
                    pageItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                    dispatch[pageItemId] = Target.Page(section.id, "child-$idx", label)
                }
            }
        }

        val appGroupId = id++
        for (action in Sections.homeActions()) {
            val actId = id++
            val actItem = menu.add(appGroupId, actId, Menu.NONE, action.label)
            Sections.iconResFor(ctx, action.iconName).takeIf { it != 0 }
                ?.let { actItem.setIcon(it) }
            actItem.setOnMenuItemClickListener { mi ->
                onItemPicked(mi.itemId); true
            }
            dispatch[actId] = Target.Action(action.actionType)
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
