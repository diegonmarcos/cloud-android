package com.diegonmarcos.superapp

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Top-toolbar search sheet — live-filters a flat index of every
 * navigable target in the app PLUS every launchable Android app
 * installed on the device. The two sources mirror the Home Apps
 * sheet's top-level tabs (Cloud | Phone), so the search scope is
 * derived from `build.json::ui.home_apps_tabs` — exactly the same
 * declarative config that names the body tabs in
 * [AppDrawerSheetFragment]. Add / rename / reorder a tab there and
 * the scope chips follow on next build with no Kotlin edits.
 *
 * Layout:
 *   1. EditText — query input (auto-focused + IME shown on open).
 *   2. Scope chips row — one chip per HomeAppsTabs entry, both
 *      checked by default. Tap to toggle inclusion.
 *   3. Results scroll — when ≥2 scopes are on, results are grouped
 *      under "── <Label> (N)" headers; when exactly one scope is
 *      on, results render as one flat list (no header).
 *
 * Built by hand (no RecyclerView dependency) — even with phone-side
 * apps the combined index rarely exceeds a few hundred items, fits
 * comfortably in a ScrollView.
 */
class SearchSheetFragment : Fragment() {

    /** A single match. `source` carries the scope ID ("cloud" /
     *  "phone") for grouping + filtering; the dispatch path branches
     *  on [phoneApp] vs [cloudTarget] for the tap handler. */
    private data class Hit(
        val label: String,
        val crumb: String,
        val source: String,
        val cloudTarget: String? = null,
        val phoneApp: PhoneApp? = null,
    )

    private val scopes: List<HomeAppsTabs.Tab> by lazy { HomeAppsTabs.loadFromBuildConfig() }
    private val selectedScopes: MutableSet<String> by lazy {
        // Both ON by default — matches the user's stated requirement.
        // Re-opening the sheet resets to all-on; deliberate, no
        // persisted scope state across sessions (would surprise
        // muscle memory if the user toggled off Phone six weeks ago
        // and forgot).
        scopes.map { it.id }.toMutableSet()
    }

    private val allHits: List<Hit> by lazy { buildIndex() }
    private lateinit var resultsBox: LinearLayout
    private var currentQuery: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0000000.toInt())
            val pad = dp(12); setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
        }

        // ── 1. Query input.
        val edit = EditText(ctx).apply {
            hint = "Search the app…"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass)
            val ip = dp(14); setPadding(ip, ip, ip, ip)
            isSingleLine = true
        }
        root.addView(edit)

        // ── 2. Scope chips (Cloud | Phone). Built from the same
        //    HomeAppsTabs config the body tabs use — single source of
        //    truth for the surface taxonomy. Only rendered when ≥2
        //    scopes exist; single-scope means scope filtering is
        //    a no-op and the row would add visual noise.
        if (scopes.size >= 2) {
            root.addView(buildScopeChipsRow(ctx))
        }

        // ── 3. Results scroll.
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        resultsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                currentQuery = s?.toString().orEmpty()
                renderResults()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderResults()   // initial render — all entries, both scopes

        edit.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        edit.postDelayed({ imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }, 120)
        return root
    }

    /** Horizontal chip strip — one toggle per HomeAppsTabs entry.
     *  Visual language matches the existing browser-tab chips and the
     *  app-drawer body tabs (glassmorphism pill, lavender accent),
     *  so the search bar reads as part of the same surface family. */
    private fun buildScopeChipsRow(ctx: Context): View {
        val strip = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        strip.addView(row)
        for (tab in scopes) row.addView(makeScopeChip(ctx, tab))
        return strip
    }

    private fun makeScopeChip(ctx: Context, tab: HomeAppsTabs.Tab): TextView {
        val chip = TextView(ctx).apply {
            text = tab.label
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val hpad = dp(14); val vpad = dp(8)
            setPadding(hpad, vpad, hpad, vpad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            layoutParams = lp
            isClickable = true; isFocusable = true
        }
        applyChipState(chip, tab.id)
        chip.setOnClickListener {
            Haptics.tap(it)
            val on = tab.id in selectedScopes
            if (on) {
                // Refuse to drop the LAST selected scope — a search
                // with zero scopes is the same as no search, and the
                // user can't tell from the empty list that they
                // accidentally toggled everything off. Re-tap the
                // chip they just turned off to swap scopes if that
                // was their intent.
                if (selectedScopes.size > 1) selectedScopes -= tab.id
            } else {
                selectedScopes += tab.id
            }
            applyChipState(chip, tab.id)
            renderResults()
        }
        return chip
    }

    private fun applyChipState(chip: TextView, id: String) {
        val on = id in selectedScopes
        chip.background = androidx.core.content.ContextCompat.getDrawable(
            chip.context,
            if (on) R.drawable.bg_liquid_glass_pill else R.drawable.bg_liquid_glass,
        )
        chip.setTextColor(if (on) 0xFFE9D8FD.toInt() else 0x88FFFFFF.toInt())
    }

    private fun renderResults() {
        resultsBox.removeAllViews()
        val q = currentQuery.trim().lowercase()
        val matched = (if (q.isEmpty()) allHits else allHits.filter {
            it.label.lowercase().contains(q) || it.crumb.lowercase().contains(q)
        }).filter { it.source in selectedScopes }

        if (matched.isEmpty()) {
            resultsBox.addView(emptyRow(
                if (q.isEmpty()) "No items in the selected scope(s)"
                else "No matches for “$currentQuery”"
            ))
            return
        }

        // Single scope → flat list, no header (avoids visual noise
        // when the user is searching only Cloud OR only Phone).
        // Multi-scope → group by source, "── <Label> (N)" header
        // before each block. Order follows HomeAppsTabs declared order
        // so the user always sees Cloud above Phone regardless of
        // result count.
        val cap = 60
        if (selectedScopes.size == 1) {
            for (hit in matched.take(cap)) resultsBox.addView(resultRow(hit))
            return
        }

        val perScopeCap = (cap / selectedScopes.size).coerceAtLeast(15)
        for (scope in scopes) {
            if (scope.id !in selectedScopes) continue
            val group = matched.filter { it.source == scope.id }
            if (group.isEmpty()) continue
            resultsBox.addView(sectionHeaderRow("── ${scope.label} (${group.size})"))
            for (hit in group.take(perScopeCap)) resultsBox.addView(resultRow(hit))
        }
    }

    private fun sectionHeaderRow(text: String): TextView =
        TextView(requireContext()).apply {
            this.text = text
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val pad = dp(12); setPadding(pad, dp(14), pad, dp(6))
        }

    private fun resultRow(hit: Hit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, dp(10), pad, dp(10))
            isClickable = true; isFocusable = true
            background = androidx.core.content.ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background)
        }
        row.addView(TextView(ctx).apply {
            text = hit.label
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        if (hit.crumb.isNotBlank()) {
            row.addView(TextView(ctx).apply {
                text = hit.crumb
                alpha = 0.55f
                setTextColor(0xFFFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        }
        row.setOnClickListener {
            dispatchHit(hit)
            // Pop the sheet so the destination's screen is visible.
            activity?.supportFragmentManager?.popBackStack(
                BACK_STACK_TAG,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        }
        return row
    }

    /** Tap dispatch — branches by source. Cloud hits flow through the
     *  TileGrid target-grammar dispatcher (section: / page: / action:
     *  / http: …). Phone hits launch the activity directly via
     *  LauncherApps — same path PhoneAppsFragment uses. */
    private fun dispatchHit(hit: Hit) {
        when (hit.source) {
            "phone" -> {
                val app = hit.phoneApp ?: return
                val launcher = activity?.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return
                runCatching {
                    launcher.startMainActivity(app.activityComponent, app.user, null, null)
                }
            }
            else -> {
                val target = hit.cloudTarget ?: return
                (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
            }
        }
    }

    private fun emptyRow(msg: String): TextView =
        TextView(requireContext()).apply {
            text = msg
            alpha = 0.65f
            setTextColor(0xFFFFFFFF.toInt())
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }

    /** Flat index of every searchable target — cloud surfaces + phone
     *  apps. Phone enumeration uses [PhoneAppsFragment.snapshot] so
     *  warm-up (kicked from MainActivity.onCreate) is amortised; if
     *  the user races warm-up the helper enumerates synchronously,
     *  adding ~600ms ONLY on first open. */
    private fun buildIndex(): List<Hit> {
        val out = mutableListOf<Hit>()
        // Cloud — sections, pages, tiles, home actions.
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            out += Hit(
                label = sec.label,
                crumb = "Section",
                source = "cloud",
                cloudTarget = "section:${sec.id}",
            )
            for (page in sec.pages) {
                out += Hit(
                    label = page.label,
                    crumb = "${sec.label} · Page",
                    source = "cloud",
                    cloudTarget = "page:${sec.id}/${page.id}",
                )
            }
            for (tile in (sec.tilesShared + sec.tilesApps + sec.tilesAdmin)) {
                out += Hit(
                    label = tile.label,
                    crumb = "${sec.label} · Tile",
                    source = "cloud",
                    cloudTarget = tile.target,
                )
            }
        }
        for (act in Sections.homeActions()) {
            out += Hit(
                label = act.label,
                crumb = "Action",
                source = "cloud",
                cloudTarget = "action:${act.actionType}",
            )
        }
        // Phone — installed launchable apps.
        val ctx = activity?.applicationContext
        if (ctx != null) {
            for (app in PhoneAppsFragment.snapshot(ctx)) {
                out += Hit(
                    label = app.label,
                    crumb = "Phone app · ${app.packageName}",
                    source = "phone",
                    phoneApp = app,
                )
            }
        }
        return out
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val BACK_STACK_TAG = "search_sheet"
        fun newInstance() = SearchSheetFragment()
    }
}
