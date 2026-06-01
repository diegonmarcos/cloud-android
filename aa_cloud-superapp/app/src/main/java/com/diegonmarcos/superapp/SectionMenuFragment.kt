package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView

/**
 * Drawer's section pane — site-map style navigation for ONE section.
 * Same programmatic NavigationView pattern as [HomeDrawerFragment] but
 * scoped to the currently-open section: every page is shown, and pages
 * with sub_pages render as expandable parents (Mail Settings → 11 tabs,
 * Mail More → 8 overflow items, etc.).
 *
 * Tapping any item bubbles up to the host activity via
 * [HomeDrawerFragment.NavigationListener] so the same dispatcher
 * resolves the destination.
 */
class SectionMenuFragment : Fragment() {

    private sealed class Target {
        data class Section(val id: String, val label: String) : Target()
        data class Page(val sectionId: String, val pageId: String, val label: String) : Target()
        /** Aggregator tile target — uses the existing tile grammar
         *  (section: / page: / action: / http(s):// / app:// / intent://)
         *  so the activity's onTileClicked handler resolves it. */
        data class Tile(val target: String) : Target()
    }

    private val dispatch = mutableMapOf<Int, Target>()
    private val sectionId: String get() = requireArguments().getString(ARG_SECTION_ID).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_section_menu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val nav = view.findViewById<NavigationView>(R.id.section_navigation_view)
        val section = Sections.byId(sectionId) ?: return

        val menu = nav.menu
        menu.clear()
        dispatch.clear()
        var id = MENU_BASE

        // Section header — taps go straight to the section index.
        val groupId = id++
        val headerId = id++
        val headerItem = menu.add(groupId, headerId, Menu.NONE, section.label.uppercase())
        Sections.iconResFor(ctx, section.iconName).takeIf { it != 0 }
            ?.let { headerItem.setIcon(it) }
        headerItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
        dispatch[headerId] = Target.Section(section.id, section.label)

        when {
            section.pages.isNotEmpty() -> {
                // Same flattening NavigationView requires — see HomeDrawerFragment.
                for (page in section.pages) {
                    val pageItemId = id++
                    val pageItem = menu.add(groupId, pageItemId, Menu.NONE, page.label)
                    page.iconName?.let {
                        Sections.iconResFor(ctx, it).takeIf { r -> r != 0 }
                            ?.let { r -> pageItem.setIcon(r) }
                    }
                    pageItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                    dispatch[pageItemId] = Target.Page(section.id, page.id, page.label)

                    for (sp in page.subPages) {
                        val subId = id++
                        val subItem = menu.add(groupId, subId, Menu.NONE, "    ↳  ${sp.label}")
                        sp.iconName?.let {
                            Sections.iconResFor(ctx, it).takeIf { r -> r != 0 }
                                ?.let { r -> subItem.setIcon(r) }
                        }
                        subItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                        dispatch[subId] = Target.Page(section.id, "${page.id}/${sp.id}", sp.label)
                    }
                }
            }
            section.isAggregator -> {
                // Aggregator section (Suite, Tools, Communication, Infos):
                //  • Aggregator tiles for the current Apps/Admin mode →
                //    rendered as tappable rows. Each tile.target uses the
                //    standard tile grammar so onTileClicked routes it.
                //  • Stack-panel titles also rendered so the drawer
                //    surfaces every section of the body (Cloud Backend,
                //    C3 Health, etc.). Tapping a stack title just opens
                //    the section's body — no nested expansion in the
                //    drawer (kept simple; details live in the body).
                val mode = ModePrefs(ctx).mode
                for (tile in Sections.aggregatorTilesFor(section, mode)) {
                    val tileItemId = id++
                    val tileItem = menu.add(groupId, tileItemId, Menu.NONE, tile.label)
                    Sections.iconResFor(ctx, tile.iconName).takeIf { r -> r != 0 }
                        ?.let { r -> tileItem.setIcon(r) }
                    tileItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                    dispatch[tileItemId] = Target.Tile(tile.target)
                }
                for (panel in Sections.aggregatorStackFor(section, mode)) {
                    if (panel.title.isBlank()) continue
                    val panelItemId = id++
                    val panelItem = menu.add(groupId, panelItemId, Menu.NONE, panel.title)
                    Sections.iconResFor(ctx, panel.iconName).takeIf { r -> r != 0 }
                        ?.let { r -> panelItem.setIcon(r) }
                    panelItem.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                    // No fine-grained target for a stack panel — re-open
                    // the section so the user lands on the aggregator
                    // body where the panel lives.
                    dispatch[panelItemId] = Target.Section(section.id, section.label)
                }
            }
            else -> {
                // Fallback: drawer_default_children (no pages declared yet).
                for ((idx, label) in section.defaultChildren.withIndex()) {
                    val itemId = id++
                    val item = menu.add(groupId, itemId, Menu.NONE, label)
                    item.setOnMenuItemClickListener { mi -> onItemPicked(mi.itemId); true }
                    dispatch[itemId] = Target.Page(section.id, "child-$idx", label)
                }
            }
        }
    }

    private fun onItemPicked(itemId: Int) {
        val listener = activity as? HomeDrawerFragment.NavigationListener
        when (val t = dispatch[itemId] ?: return) {
            is Target.Section -> listener?.onDrawerSectionSelected(t.id, t.label)
            is Target.Page    -> listener?.onDrawerPageSelected(t.sectionId, t.pageId, t.label)
            is Target.Tile    -> {
                // Aggregator tile in the drawer — route through the same
                // dispatcher tile clicks in the body use. Activity closes
                // the drawer in its onTileClicked path.
                (activity as? TileGridFragment.TileClickListener)?.onTileClicked(t.target)
            }
        }
    }

    companion object {
        private const val MENU_BASE = 30_000
        private const val ARG_SECTION_ID = "section_id"

        fun newInstance(sectionId: String) = SectionMenuFragment().apply {
            arguments = bundleOf(ARG_SECTION_ID to sectionId)
        }
    }
}
