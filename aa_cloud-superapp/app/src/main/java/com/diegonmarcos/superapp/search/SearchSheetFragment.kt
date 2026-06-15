package com.diegonmarcos.superapp.search
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.cloud.CloudData

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
 * Top-toolbar search sheet — live-filters a flat index across four
 * declarative scopes (`build.json::ui.search_scopes`):
 *   • Cloud-Apps    — cloud sections + tiles + home actions
 *   • Phone-Apps    — installed launchable apps (LauncherApps)
 *   • Cloud-Configs — section sub-pages + cached consolidated.json entries
 *   • Phone-Configs — installed Android Settings activities (native-style
 *                     settings search)
 * The scope list is DECOUPLED from home_apps_tabs (renaming a search scope
 * must not rename the Home-Apps body tabs). Add / rename / reorder a scope
 * there and the chips follow on next build with no Kotlin edits.
 *
 * Layout:
 *   1. EditText — query input (auto-focused + IME shown on open).
 *   2. Scope chips row — one chip per scope, all checked by default. Tap
 *      to toggle inclusion.
 *   3. Results scroll — when ≥2 scopes are on, results are grouped under
 *      "── <Label> (N)" headers; with exactly one scope on, a flat list.
 *
 * Built by hand (no RecyclerView dependency) — the combined index stays
 * within a few hundred items, fits comfortably in a ScrollView.
 */
class SearchSheetFragment : Fragment() {

    /** A single match. `source` is the scope ID for grouping + filtering.
     *  The tap path branches on which payload is non-null:
     *   • [phoneApp]          — launch via LauncherApps
     *   • [settingsComponent] — start that Android Settings activity
     *   • [copyValue]         — copy to clipboard (consolidated entries)
     *   • [cloudTarget]       — dispatch via the TileGrid target grammar */
    private data class Hit(
        val label: String,
        val crumb: String,
        val source: String,
        val cloudTarget: String? = null,
        val phoneApp: PhoneApp? = null,
        val settingsComponent: ComponentName? = null,
        val copyValue: String? = null,
    )

    private val scopes: List<SearchScopes.Scope> by lazy { SearchScopes.loadFromBuildConfig() }
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

        // ── 2. Scope chips (Cloud-Apps | Phone-Apps | Cloud-Configs |
        //    Phone-Configs). Built from build.json::ui.search_scopes
        //    (SearchScopes) — single source of truth for the search
        //    taxonomy. Only rendered when ≥2 scopes exist; single-scope
        //    means scope filtering is a no-op and the row would add noise.
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

    /** Horizontal chip strip — one toggle per search scope.
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

    private fun makeScopeChip(ctx: Context, tab: SearchScopes.Scope): TextView {
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
        // before each block. Order follows the search_scopes declared
        // order so groups always read in the same sequence regardless of
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

    /** Tap dispatch — branches on the hit's payload (independent of which
     *  scope produced it):
     *   • phoneApp          → launch via LauncherApps (PhoneAppsFragment path)
     *   • settingsComponent → start that Android Settings activity
     *   • copyValue         → copy to clipboard (consolidated entries)
     *   • cloudTarget       → TileGrid target grammar (section:/page:/action:/…) */
    private fun dispatchHit(hit: Hit) {
        when {
            hit.phoneApp != null -> {
                val launcher = activity?.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return
                runCatching {
                    launcher.startMainActivity(hit.phoneApp.activityComponent, hit.phoneApp.user, null, null)
                }
            }
            hit.settingsComponent != null -> {
                val i = Intent().setComponent(hit.settingsComponent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(i) }.onFailure {
                    Toast.makeText(activity, "Can't open “${hit.label}”", Toast.LENGTH_SHORT).show()
                }
            }
            hit.copyValue != null -> {
                val cb = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText(hit.label, hit.copyValue))
                Toast.makeText(activity, "Copied: ${hit.copyValue}", Toast.LENGTH_SHORT).show()
            }
            hit.cloudTarget != null -> {
                (activity as? TileGridFragment.TileClickListener)?.onTileClicked(hit.cloudTarget)
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

    /** Flat index across every declared scope. Each scope's `kind` selects
     *  the builder that fills it; `source` on every Hit is the scope ID so
     *  result grouping + chip filtering line up. All builders are
     *  synchronous + network-free (Cloud-Configs reads the consolidated
     *  cache only if a prior fetch populated it). Phone enumeration uses
     *  [PhoneAppsFragment.snapshot] so warm-up is amortised. */
    private fun buildIndex(): List<Hit> {
        val out = mutableListOf<Hit>()
        val ctx = activity?.applicationContext
        for (scope in scopes) {
            when (scope.kind) {
                "cloud_apps"    -> out += cloudAppsHits(scope.id)
                "phone_apps"    -> if (ctx != null) out += phoneAppsHits(scope.id, ctx)
                "cloud_configs" -> if (ctx != null) out += cloudConfigsHits(scope.id, ctx)
                "phone_configs" -> if (ctx != null) out += phoneConfigsHits(scope.id, ctx)
            }
        }
        return out
    }

    /** Cloud-Apps: navigable cloud destinations — sections, their tiles,
     *  and home actions. (Sub-pages live in Cloud-Configs.) */
    private fun cloudAppsHits(scopeId: String): List<Hit> {
        val out = mutableListOf<Hit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            out += Hit(sec.label, "Section", scopeId, cloudTarget = "section:${sec.id}")
            for (tile in (sec.tilesShared + sec.tilesApps + sec.tilesAdmin)) {
                out += Hit(tile.label, "${sec.label} · Tile", scopeId, cloudTarget = tile.target)
            }
        }
        for (act in Sections.homeActions()) {
            out += Hit(act.label, "Action", scopeId, cloudTarget = "action:${act.actionType}")
        }
        return out
    }

    /** Phone-Apps: every installed launchable app. */
    private fun phoneAppsHits(scopeId: String, ctx: Context): List<Hit> =
        PhoneAppsFragment.snapshot(ctx).map { app ->
            Hit(app.label, "Phone app · ${app.packageName}", scopeId, phoneApp = app)
        }

    /** Cloud-Configs: every section sub-page (config/settings/about
     *  surfaces) PLUS cached consolidated.json service entries (tap copies
     *  the value). Consolidated entries appear only once some screen has
     *  fetched the consolidated config this install (network-free here). */
    private fun cloudConfigsHits(scopeId: String, ctx: Context): List<Hit> {
        val out = mutableListOf<Hit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            for (page in sec.pages) {
                out += Hit(page.label, "${sec.label} · Page", scopeId,
                    cloudTarget = "page:${sec.id}/${page.id}")
            }
        }
        val root = CloudData.cachedOrNull(ctx)
        if (root != null) {
            val services = CloudData.services(root)
            val names = services.keys()
            while (names.hasNext()) {
                val name = names.next()
                val svc = services.optJSONObject(name) ?: continue
                val domain = svc.optString("domain", svc.optString("private_dns", ""))
                val vm = svc.optString("vm", "")
                val crumb = listOf("Cloud config", vm, domain).filter { it.isNotBlank() }.joinToString(" · ")
                out += Hit(name, crumb, scopeId, copyValue = domain.ifBlank { name })
            }
        }
        return out
    }

    /** Phone-Configs: installed Android Settings activities — enumerated
     *  from the device's Settings package(s) (whatever resolves
     *  ACTION_SETTINGS, plus any *.settings package). Only EXPORTED,
     *  ENABLED activities that carry their OWN label are indexed (so the
     *  list reads "Wi‑Fi", "Bluetooth", "Display"… not dozens of generic
     *  "Settings" rows). Tap launches that screen — same spirit as the
     *  native Settings search. */
    private fun phoneConfigsHits(scopeId: String, ctx: Context): List<Hit> {
        val pm = ctx.packageManager
        val pkgs = linkedSetOf<String>()
        runCatching {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                ?.activityInfo?.packageName?.let { pkgs += it }
        }
        // OEM secondary settings packages (Samsung etc.) — keep it generic.
        runCatching {
            pm.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it.endsWith(".settings") || it.contains(".settings.") }
                .forEach { pkgs += it }
        }
        val out = mutableListOf<Hit>()
        for (pkg in pkgs) {
            val pi = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue
            for (ai in pi.activities ?: emptyArray()) {
                if (!ai.exported || !ai.enabled) continue
                // Self-labelled only — skip activities that fall back to the
                // app label (avoids a wall of identical "Settings" rows).
                if (ai.labelRes == 0 && ai.nonLocalizedLabel == null) continue
                val label = ai.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) continue
                out += Hit(label, "Settings · $pkg", scopeId,
                    settingsComponent = ComponentName(pkg, ai.name))
            }
        }
        // Stable, de-duped by visible label (OEMs alias the same screen).
        return out.distinctBy { it.label.lowercase() }.sortedBy { it.label.lowercase() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val BACK_STACK_TAG = "search_sheet"
        fun newInstance() = SearchSheetFragment()
    }
}
