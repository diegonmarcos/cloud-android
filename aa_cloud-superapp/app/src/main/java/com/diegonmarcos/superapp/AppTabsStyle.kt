package com.diegonmarcos.superapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.tabs.TabLayout

/**
 * Single-call helper that re-styles a Material [TabLayout] as the
 * SuperApp's "liquid-glass pill tab" row — matching the browser-tab
 * chip strip below it in Home Apps and the broader glassmorphism +
 * pastel-lavender language the rest of the app uses.
 *
 * Visual contract:
 *   • Selected pill — translucent royal-purple fill (0x44 7C3AED) +
 *     1dp lavender border (0x66 E9D8FD) + white monospace caps text.
 *   • Unselected pill — 13% white fill + 20% white border + 67%
 *     white text.
 *   • TabLayout's default Material chrome (gray background, blue
 *     underline indicator, gray ripple) is stripped.
 *
 * Apply at the TabLayout's construction site, AFTER `addTab(...)`
 * calls have populated the tabs. The helper installs its OWN
 * OnTabSelectedListener (in ADDITION to whatever the caller wires up
 * for navigation) — it only updates pill visuals and never consumes
 * the event.
 */
object AppTabsStyle {

    fun apply(tabLayout: TabLayout) {
        val ctx = tabLayout.context

        // ── 1. Strip default Material chrome ─────────────────────
        tabLayout.setBackgroundColor(0x00000000)
        tabLayout.setSelectedTabIndicatorColor(0x00000000)
        tabLayout.setSelectedTabIndicatorHeight(0)
        tabLayout.tabRippleColor = null
        val pad = dp(ctx, 4)
        tabLayout.setPadding(pad, pad, pad, pad)

        // ── 2. Replace each tab's content with a pill custom view ─
        for (i in 0 until tabLayout.tabCount) {
            val tab = tabLayout.getTabAt(i) ?: continue
            val label = tab.text?.toString().orEmpty()
            tab.customView = makePill(ctx, label, selected = i == tabLayout.selectedTabPosition)
        }

        // ── 3. Add a state-only listener — purely visual, never
        //       consumes the event so navigation listeners installed
        //       by the caller continue to fire. ──────────────────
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab)   { repaint(tab.customView, true) }
            override fun onTabUnselected(tab: TabLayout.Tab) { repaint(tab.customView, false) }
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /** Build a single pill — outer LinearLayout with a pill bg
     *  drawable + inner monospace caps TextView. */
    private fun makePill(ctx: Context, label: String, selected: Boolean): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = makePillBg(ctx, selected)
            val px = dp(ctx, 14); val py = dp(ctx, 8)
            setPadding(px, py, px, py)
            val m = dp(ctx, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(m, m, m, m) }
        }
        tile.addView(TextView(ctx).apply {
            text = label.uppercase()
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xAAFFFFFFL.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 12f
            letterSpacing = 0.08f
            isAllCaps = true
        })
        return tile
    }

    /** Rounded-rectangle pill background — different fill + stroke
     *  per selection state so the user can read the active tab at a
     *  glance even without the (stripped) underline indicator. */
    private fun makePillBg(ctx: Context, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, 18).toFloat()
        if (selected) {
            setColor(0x447C3AED)
            setStroke(dp(ctx, 1), 0x66E9D8FD)
        } else {
            setColor(0x22FFFFFFL.toInt())
            setStroke(dp(ctx, 1), 0x33FFFFFF)
        }
    }

    /** Update an existing pill's background + text color in-place on
     *  selection-state change. Tolerant of nulls + unexpected view
     *  shapes (returns silently). */
    private fun repaint(view: View?, selected: Boolean) {
        val tile = view as? LinearLayout ?: return
        tile.background = makePillBg(tile.context, selected)
        (tile.getChildAt(0) as? TextView)?.setTextColor(
            if (selected) 0xFFFFFFFF.toInt() else 0xAAFFFFFFL.toInt(),
        )
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
