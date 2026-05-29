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

        nav.getHeaderView(0)
            ?.findViewById<TextView>(R.id.nav_header_build)?.text =
            "${BuildConfig.VERSION_NAME}  vc:${BuildConfig.VERSION_CODE}"

        val menu = nav.menu
        menu.clear()
        dispatch.clear()
        var id = MENU_BASE

        for (section in Sections.all().filter { !it.isMasterIndex }) {
            val groupId = id++
            val sectionItemId = id++
            val sectionItem = menu.add(groupId, sectionItemId, Menu.NONE, section.label)
            Sections.iconResFor(ctx, section.iconName).takeIf { it != 0 }
                ?.let { sectionItem.setIcon(it) }
            sectionItem.setOnMenuItemClickListener { mi ->
                onItemPicked(mi.itemId); true
            }
            dispatch[sectionItemId] = Target.Section(section.id, section.label)

            val sub = sectionItem.subMenu ?: continue

            // Two cases: real pages (with possible sub_pages / menu nesting)
            // OR fallback to drawer_default_children (no sub-level yet).
            if (section.pages.isNotEmpty()) {
                for (page in section.pages) {
                    val pageItemId = id++
                    // If the page itself has sub_pages, the NavigationView item
                    // owns a subMenu — clicking the parent header drills to
                    // the page; clicking a child opens that sub-page.
                    val pageItem = if (page.subPages.isNotEmpty()) {
                        sub.addSubMenu(groupId, pageItemId, Menu.NONE, page.label).item
                    } else {
                        sub.add(groupId, pageItemId, Menu.NONE, page.label)
                    }
                    page.iconName?.let {
                        Sections.iconResFor(ctx, it).takeIf { r -> r != 0 }
                            ?.let { r -> pageItem.setIcon(r) }
                    }
                    pageItem.setOnMenuItemClickListener { mi ->
                        onItemPicked(mi.itemId); true
                    }
                    dispatch[pageItemId] = Target.Page(section.id, page.id, page.label)

                    // 2nd level — sub-pages render under the page header.
                    val nested = pageItem.subMenu
                    if (nested != null && page.subPages.isNotEmpty()) {
                        for (subPage in page.subPages) {
                            val subItemId = id++
                            val subItem = nested.add(groupId, subItemId, Menu.NONE, subPage.label)
                            subPage.iconName?.let {
                                Sections.iconResFor(ctx, it).takeIf { r -> r != 0 }
                                    ?.let { r -> subItem.setIcon(r) }
                            }
                            subItem.setOnMenuItemClickListener { mi ->
                                onItemPicked(mi.itemId); true
                            }
                            // Dispatch as page id "<parentPage>/<subPage>" so the
                            // host can resolve it (MailPages, future C3 pages).
                            dispatch[subItemId] = Target.Page(
                                section.id,
                                "${page.id}/${subPage.id}",
                                subPage.label,
                            )
                        }
                    }
                }
            } else {
                for ((idx, label) in section.defaultChildren.withIndex()) {
                    val pageItemId = id++
                    val pageItem = sub.add(groupId, pageItemId, Menu.NONE, label)
                    pageItem.setOnMenuItemClickListener { mi ->
                        onItemPicked(mi.itemId); true
                    }
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
