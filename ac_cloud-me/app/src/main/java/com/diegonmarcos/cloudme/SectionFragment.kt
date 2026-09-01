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
 * A page's content is decided by its `stack_<id>` list. When that list is a
 * single `fragment` block the library fragment is hosted directly — Health,
 * Accounting, Me and Agenda are whole surfaces owned by their modules and
 * wrapping them in a renderer would only add a scroll container inside a
 * scroll container. Everything else goes to [StackFragment].
 */
class SectionFragment : Fragment() {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION).orEmpty()
    private var pageId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val section = Sections.byId(sectionId)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
        }

        pageId = s?.getString(STATE_PAGE) ?: arguments?.getString(ARG_PAGE)
        val page = section?.page(pageId)
        pageId = page?.id

        val host = FrameLayout(ctx).apply { id = HOST_ID }

        // One page needs no tab strip. Me is the only section shaped that way
        // today, and a single-tab strip reads as a broken control rather than
        // a navigation aid.
        if (section != null && section.pages.size > 1) {
            root.addView(buildTabs(ctx, section), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        if (s == null) showPage(pageId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, pageId)
    }

    private fun buildTabs(ctx: android.content.Context, section: Section): TabLayout =
        TabLayout(ctx).apply {
            tabMode = if (section.pages.size > 4) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
            setSelectedTabIndicatorColor(ContextCompat.getColor(ctx, R.color.me_primary))
            setTabTextColors(
                ContextCompat.getColor(ctx, R.color.me_text_dim),
                ContextCompat.getColor(ctx, R.color.me_primary),
            )
            section.pages.forEach { p -> addTab(newTab().apply { text = p.label; tag = p.id }) }
            val start = section.pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
            getTabAt(start)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { showPage(tab.tag as? String) }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }

    private fun showPage(id: String?) {
        val section = Sections.byId(sectionId) ?: return
        val page = section.page(id) ?: return
        pageId = page.id
        childFragmentManager.commit {
            replace(HOST_ID, contentFor(section, page))
        }
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
        "me"     -> MeFragment()
        "agenda" -> AgendaFragment.newInstance(AgendaFragment.MODE_EVENTS)
        "todo"   -> AgendaFragment.newInstance(AgendaFragment.MODE_TODOS)
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
