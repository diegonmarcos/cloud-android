package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.tabs.TabPrefs
import com.diegonmarcos.superapp.tabs.TabsHostFragment

/**
 * Android-launcher-style "all apps" drawer revealed by pulling up from
 * [Home3DFragment]. Content:
 *   • Top spacer — double the gap to the activity-level search-bar island
 *     above (user feedback: needed breathing room).
 *   • Horizontal tab strip (Google-Maps-style chip row) — one chip per
 *     entry in [TabPrefs], tap = open that tab in [TabsHostFragment].
 *   • TileGrid of every section + home actions below.
 * Pull-down (or back press) closes the sheet and restores the 3D cube.
 */
class AppDrawerSheetFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_gradient_black_purple)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Top spacer — pushes the tab strip + grid clear of the
        //    activity's top-island search bar. Roughly doubles the
        //    previous breathing room.
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(16),
            )
        })

        // ── Horizontal tabs strip (Google-Maps style chips).
        val tabsStrip = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(12); setPadding(pad, dp(4), pad, dp(4))
        }
        tabsStrip.addView(chipRow)
        renderTabChips(chipRow)
        root.addView(tabsStrip)

        // ── Section + action tile grid below.
        val sectionTiles = Sections.all()
            .filter { !it.isMasterIndex }
            .map { sec ->
                TileGridFragment.Tile(
                    id      = "section:${sec.id}",
                    label   = sec.label,
                    iconRes = Sections.iconResFor(requireContext(), sec.iconName),
                )
            }
        val actionTiles = Sections.homeActions().map { act ->
            TileGridFragment.Tile(
                id      = "action:${act.actionType}",
                label   = act.label,
                iconRes = Sections.iconResFor(requireContext(), act.iconName),
            )
        }
        val title = getString(R.string.section_home)

        val host = FrameLayout(ctx).apply {
            id = R.id.app_drawer_grid_host
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(host)
        if (s == null && childFragmentManager.findFragmentById(host.id) == null) {
            childFragmentManager.beginTransaction()
                .replace(host.id, TileGridFragment.newInstance(title, sectionTiles + actionTiles))
                .commit()
        }
        return root
    }

    /** One chip per open tab — Google-Maps-pill styling. Tap = navigate
     *  to TabsHostFragment with that URL pre-opened. Empty strip if no
     *  tabs are saved. */
    private fun renderTabChips(row: LinearLayout) {
        val ctx = row.context
        val tabs = TabPrefs(ctx).all()
        if (tabs.isEmpty()) {
            row.addView(TextView(ctx).apply {
                text = "No open tabs"
                setTextColor(0x88FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
            return
        }
        for (t in tabs) {
            row.addView(makeChip(ctx, t.title.ifBlank { t.url }, t.url))
        }
    }

    private fun makeChip(ctx: android.content.Context, label: String, url: String): View {
        // All chips share the same width so they read as a uniform row
        // (like Google-Maps' filter chips). Long labels truncate with
        // ellipsis at the end; short labels left-justify within the pill.
        val pillWidth = dp(140)
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_liquid_glass_pill)
            val hpad = dp(12); val vpad = dp(6)
            setPadding(hpad, vpad, hpad, vpad)
            val lp = LinearLayout.LayoutParams(
                pillWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            layoutParams = lp
            isClickable = true; isFocusable = true
        }
        pill.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_mode_apps)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(14)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(6) }
        })
        pill.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // Take the remaining row width so truncation kicks in at the
            // pill edge regardless of label length.
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        pill.setOnClickListener {
            Haptics.tap(it)
            // Pop the home-sheet first so back-press from the tab returns
            // to the sheet's parent (Home3D), not into the sheet again.
            parentFragmentManager.popBackStack(
                BACK_STACK_TAG,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
            // Then open TabsHostFragment with the URL pre-loaded (its
            // newInstance(openUrl) jumps straight to DETAIL on that page).
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TabsHostFragment.newInstance(url))
                .addToBackStack(null)
                .commit()
        }
        return pill
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Tag used both for the back-stack entry name and for activity-side
         *  presence detection. Don't rename without updating MainActivity. */
        const val BACK_STACK_TAG = "app_drawer"
        fun newInstance() = AppDrawerSheetFragment()
    }
}
