package com.diegonmarcos.cloudme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.fin.MyFinDashboardFragment
import com.diegonmarcos.superapp.health.HealthFragment
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject

/**
 * One section: its tab strip and whichever page is showing.
 *
 * A tab that declares `pages` of its own is a container — it draws a second,
 * quieter strip underneath and shows one of its children. Buro > Fin is the
 * only one today: Acct, Budget and Portfolio are three views of one question,
 * and three top-level tabs would have said they were three questions.
 *
 * A page's content is decided by its `stack_<id>` list. When that list is a
 * single `fragment` block the library fragment is hosted directly — Health,
 * Accounting, Agenda and the Wallet file browser are whole surfaces owned by
 * their modules, and wrapping them in a renderer would only add a scroll
 * container inside a scroll container. Everything else goes to [StackFragment].
 */
class SectionFragment : Fragment() {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION).orEmpty()

    /** The LEAF page on screen — a sub-page id when the tab is a container. */
    private var pageId: String? = null

    private var tabs: TabLayout? = null
    private var subTabs: TabLayout? = null

    /** TabLayout fires onTabSelected for programmatic selection and for the
     *  first tab added, so every sync below would bounce straight back into
     *  [showPage] — and on a config change that would replace the child
     *  fragment the FragmentManager had just restored. */
    private var syncing = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val section = Sections.byId(sectionId)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
        }

        pageId = (s?.getString(STATE_PAGE) ?: arguments?.getString(ARG_PAGE))
            .let { section?.page(it)?.leaf()?.id }

        // One page needs no tab strip. Me is the only section shaped that way
        // today, and a single-tab strip reads as a broken control rather than
        // a navigation aid.
        if (section != null && section.pages.size > 1) {
            val top = buildStrip(ctx, section.pages, primary = true) { id ->
                // A container tab has no content of its own; opening it opens
                // the child you were last on, or its first.
                val target = section.page(id) ?: return@buildStrip
                showPage(if (target.pages.any { it.id == pageId }) pageId else target.leaf().id)
            }
            tabs = top
            root.addView(top, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val sub = TabLayout(ctx).apply { visibility = View.GONE }
        subTabs = sub
        root.addView(sub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(FrameLayout(ctx).apply { id = HOST_ID }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        syncing = false
        showPage(pageId, replaceContent = s == null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, pageId)
    }

    /** A tab strip over [pages]. `primary` is the section's own strip; the
     *  secondary one is smaller and unindicated, so the two never read as
     *  competing rows of the same control. */
    private fun buildStrip(
        ctx: android.content.Context,
        pages: List<Page>,
        primary: Boolean,
        onSelect: (String) -> Unit,
    ): TabLayout = TabLayout(ctx).apply {
        tabMode = if (pages.size > 4) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
        setSelectedTabIndicatorColor(ContextCompat.getColor(
            ctx, if (primary) R.color.me_primary else R.color.me_surface))
        setTabTextColors(
            ContextCompat.getColor(ctx, R.color.me_text_dim),
            ContextCompat.getColor(ctx, R.color.me_primary),
        )
        pages.forEach { p -> addTab(newTab().apply { text = p.label; tag = p.id }) }
        addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!syncing) (tab.tag as? String)?.let(onSelect)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    /** Shows [id] and brings both strips in line with it. [replaceContent] is
     *  false on a config change, where the child fragment is restored already
     *  and replacing it would drop its own state. */
    private fun showPage(id: String?, replaceContent: Boolean = true) {
        val section = Sections.byId(sectionId) ?: return
        val page = section.page(id)?.leaf() ?: return
        val changed = pageId != page.id
        pageId = page.id

        val parent = section.parentOf(page.id)
        val wasSyncing = syncing
        syncing = true
        tabs?.let { syncStrip(it, section.pages.indexOfFirst { p -> p.id == parent?.id }) }
        syncSubStrip(parent)
        syncing = wasSyncing

        if (replaceContent || changed) {
            childFragmentManager.commit { replace(HOST_ID, contentFor(section, page)) }
        }
    }

    /** Selects [index] without re-entering [showPage] through the listener. */
    private fun syncStrip(strip: TabLayout, index: Int) {
        if (index < 0 || strip.selectedTabPosition == index) return
        strip.getTabAt(index)?.let { strip.selectTab(it, true) }
    }

    private fun syncSubStrip(parent: Page?) {
        val holder = subTabs ?: return
        val children = parent?.pages.orEmpty()
        if (children.isEmpty()) {
            holder.visibility = View.GONE
            holder.removeAllTabs()
            return
        }
        holder.visibility = View.VISIBLE
        // Rebuilt rather than reused: a second container tab would otherwise
        // inherit the previous one's children for one frame.
        val shown = (0 until holder.tabCount).map { holder.getTabAt(it)?.tag as? String }
        if (children.map { it.id } != shown) {
            holder.clearOnTabSelectedListeners()
            holder.removeAllTabs()
            holder.tabMode = if (children.size > 4) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
            children.forEach { p -> holder.addTab(holder.newTab().apply { text = p.label; tag = p.id }) }
            holder.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!syncing) (tab.tag as? String)?.let { showPage(it) }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        syncStrip(holder, children.indexOfFirst { it.id == pageId })
    }

    private fun contentFor(section: Section, page: Page): Fragment {
        val stack = section.stackFor(page.id)
        val single = if (stack.length() == 1) stack.optJSONObject(0) else null
        if (single != null && single.optString("kind") == "fragment") {
            libraryFragment(single)?.let { return it }
        }
        return StackFragment.newInstance(section.id, page.id)
    }

    /** `{"kind":"fragment","id":"…"}` → the module that owns that surface.
     *  An unknown id falls through to the stack renderer, which shows the
     *  block's title rather than a blank screen, so a typo in JSON is
     *  visible on the phone instead of silent. */
    private fun libraryFragment(o: JSONObject): Fragment? = when (o.optString("id")) {
        "health" -> HealthFragment.newInstance(o.optString("page", HealthFragment.PAGE_SUMMARY))
        "fin"    -> MyFinDashboardFragment()
        "agenda" -> AgendaFragment.newInstance(AgendaFragment.MODE_EVENTS)
        "todo"   -> AgendaFragment.newInstance(AgendaFragment.MODE_TODOS)
        "files"  -> FilesFragment.newInstance(o)
        else     -> null
    }

    companion object {
        private const val ARG_SECTION = "section"
        private const val ARG_PAGE = "page"
        private const val STATE_PAGE = "state_page"
        private const val HOST_ID = 0x00ED0001

        fun newInstance(sectionId: String, pageId: String?): SectionFragment =
            SectionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION, sectionId)
                    putString(ARG_PAGE, pageId)
                }
            }
    }
}
