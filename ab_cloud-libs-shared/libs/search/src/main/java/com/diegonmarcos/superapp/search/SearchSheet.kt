package com.diegonmarcos.superapp.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * The search sheet, as a library: one query box, one chip strip, one result
 * list — and, when the query opens with a colon, a command line instead.
 *
 * Nothing in here knows what an app's results ARE. The host hands over hits
 * and commands and takes back the targets it recognises, so the only thing
 * this module owns is the part every app would otherwise rewrite: matching,
 * grouping, capping, chip state, keyboard handling, dispatch and the
 * search/command split.
 *
 * ## Search vs. command
 * A query whose VERY FIRST character is `:` is a command line; anything else
 * is a search. The check is on the raw text, deliberately un-trimmed, so
 * `" Bars in Berlin:Mitte"` is a search for a bar (the colon is just text,
 * wherever it appears) while `":update-all"` runs the update-all command.
 * That is the whole rule — one character, in one position, and no escaping to
 * learn.
 *
 * ## Wiring
 * The [Host] is the ACTIVITY, not a constructor argument, so the sheet
 * survives recreation without a factory: `activity as? Host`. An activity that
 * does not implement it gets a sheet that says so rather than a crash.
 */
class SearchSheet : Fragment() {

    /** What the app must provide. Everything app-shaped lives behind this. */
    interface Host {
        /** Every hit for [scope], already labelled with `source = scope.id`.
         *  Called once when the sheet opens; keep it synchronous and cheap. */
        fun hitsFor(scope: SearchScope): List<SearchHit>

        /** The `:`-command table. Empty disables command mode entirely. */
        fun searchCommands(): List<SearchCommand> = emptyList()

        /** A [SearchHit.target] or [SearchCommand.target] the app understands. */
        fun openTarget(target: String)

        /** Background for the query box and chips (a `@DrawableRes`), so the
         *  sheet inherits the app's surface. 0 = leave the platform default. */
        fun searchBoxBackground(): Int = 0
        fun searchChipBackground(): Int = 0

        /** Called after a hit or command fires, to close the sheet. */
        fun dismissSearch()
    }

    private val host: Host? get() = activity as? Host

    private val scopes: List<SearchScope> by lazy { SearchScopes.fromBuildConfig() }
    private val allHits: List<SearchHit> by lazy {
        val h = host ?: return@lazy emptyList()
        if (scopes.isEmpty()) h.hitsFor(SearchScope(ALL, "All", "")) 
        else scopes.flatMap { h.hitsFor(it) }
    }
    private val commands: List<SearchCommand> by lazy { host?.searchCommands().orEmpty() }

    private lateinit var resultsBox: LinearLayout
    private var chipsRow: View? = null
    private var currentQuery = ""
    private val selectedScopes = linkedSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12); setPadding(p, p, p, p)
        }
        if (host == null) {
            root.addView(emptyRow("This screen's activity does not implement SearchSheet.Host"))
            return root
        }
        selectedScopes += if (scopes.isEmpty()) listOf(ALL) else scopes.map { it.id }

        // ── 1. Query input. Also the command line — same box, because the
        //    colon is the mode switch and a second box would be a second thing
        //    to find.
        val edit = EditText(ctx).apply {
            hint = if (commands.isEmpty()) "Search…" else "Search…   ( : for commands )"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            host?.searchBoxBackground()?.takeIf { it != 0 }?.let {
                background = androidx.core.content.ContextCompat.getDrawable(ctx, it)
            }
            val ip = dp(14); setPadding(ip, ip, ip, ip)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        root.addView(edit)

        // ── 2. Scope chips. Meaningless in command mode, so they are hidden
        //    while the colon is held rather than left there filtering nothing.
        if (scopes.size >= 2) {
            chipsRow = buildScopeChipsRow(ctx).also { root.addView(it) }
        }

        // ── 3. Results / command list.
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
                chipsRow?.visibility = if (isCommandMode()) View.GONE else View.VISIBLE
                render()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // Enter on a command line runs the one exact alias, so `:update-all`
        // ⏎ never needs a tap. An ambiguous or unknown alias falls through to
        // the list, which is already showing the candidates.
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO && isCommandMode()) {
                exactCommand()?.let { runCommand(it); return@setOnEditorActionListener true }
            }
            false
        }
        render()

        edit.requestFocus()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        edit.postDelayed({ imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }, 120)
        return root
    }

    // ─────────────────────────── the colon rule ───────────────────────────

    /** Command mode iff the FIRST character is a colon. Raw text on purpose:
     *  a leading space means the user is typing prose, not a command. */
    private fun isCommandMode() = commands.isNotEmpty() && currentQuery.startsWith(":")

    /** What was typed after the colon, normalised for matching. */
    private fun commandQuery() = currentQuery.substring(1).trim().lowercase()

    private fun exactCommand(): SearchCommand? =
        commandQuery().let { q -> commands.singleOrNull { it.alias.equals(q, ignoreCase = true) } }

    private fun runCommand(cmd: SearchCommand) {
        host?.openTarget(cmd.target)
        host?.dismissSearch()
    }

    // ───────────────────────────── rendering ─────────────────────────────

    private fun render() = if (isCommandMode()) renderCommands() else renderResults()

    private fun renderCommands() {
        resultsBox.removeAllViews()
        val q = commandQuery()
        val matched = if (q.isEmpty()) commands else commands.filter {
            it.alias.contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true)
        }
        resultsBox.addView(sectionHeaderRow(
            if (q.isEmpty()) "── Commands (${commands.size}) — keep typing, ⏎ to run"
            else "── Commands (${matched.size})"))
        if (matched.isEmpty()) {
            resultsBox.addView(emptyRow("No command matches “:$q”"))
            return
        }
        for (cmd in matched.take(60)) {
            resultsBox.addView(row(":${cmd.alias}", cmd.crumb.ifBlank { cmd.label }) { runCommand(cmd) })
        }
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

        // Single scope → flat list, no header (avoids visual noise when the
        // user is searching only one). Multi-scope → group by source with a
        // "── <Label> (N)" header, in declared scope order so groups always
        // read in the same sequence regardless of result count.
        val cap = 60
        if (selectedScopes.size == 1) {
            for (hit in matched.take(cap)) resultsBox.addView(hitRow(hit))
            return
        }
        val perScopeCap = (cap / selectedScopes.size).coerceAtLeast(15)
        for (scope in scopes) {
            if (scope.id !in selectedScopes) continue
            val group = matched.filter { it.source == scope.id }
            if (group.isEmpty()) continue
            resultsBox.addView(sectionHeaderRow("── ${scope.label} (${group.size})"))
            for (hit in group.take(perScopeCap)) resultsBox.addView(hitRow(hit))
        }
    }

    // ──────────────────────────────── rows ────────────────────────────────

    private fun sectionHeaderRow(text: String): TextView =
        TextView(requireContext()).apply {
            this.text = text
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val pad = dp(12); setPadding(pad, dp(14), pad, dp(6))
        }

    private fun emptyRow(msg: String): TextView =
        TextView(requireContext()).apply {
            text = msg
            alpha = 0.65f
            setTextColor(0xFFFFFFFF.toInt())
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }

    /** Title + subtitle + tap. Hits and commands render identically on
     *  purpose — a command IS a result, it just came from the other mode. */
    private fun row(title: String, sub: String, onTap: () -> Unit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, dp(10), pad, dp(10))
            isClickable = true; isFocusable = true
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, android.R.drawable.list_selector_background)
        }
        row.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        if (sub.isNotBlank()) {
            row.addView(TextView(ctx).apply {
                text = sub
                alpha = 0.55f
                setTextColor(0xFFFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        }
        row.setOnClickListener { onTap() }
        return row
    }

    private fun hitRow(hit: SearchHit): View = row(hit.label, hit.crumb) {
        dispatchHit(hit)
        host?.dismissSearch()
    }

    /** Tap dispatch — branches on the hit's payload, independent of which
     *  scope produced it. Only [SearchHit.target] goes back to the host; the
     *  other three are platform work any app would do the same way. */
    private fun dispatchHit(hit: SearchHit) {
        when {
            hit.phoneApp != null -> {
                val launcher = activity?.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return
                runCatching {
                    launcher.startMainActivity(hit.phoneApp.component, hit.phoneApp.user, null, null)
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
            hit.target != null -> host?.openTarget(hit.target)
        }
    }

    // ──────────────────────────────── chips ────────────────────────────────

    /** Horizontal chip strip — one toggle per scope. */
    private fun buildScopeChipsRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scopes.forEach { row.addView(makeScopeChip(ctx, it)) }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(8), 0, dp(4)) }
            addView(row)
        }
    }

    private fun makeScopeChip(ctx: Context, scope: SearchScope): TextView {
        val chip = TextView(ctx).apply {
            text = scope.label
            val hp = dp(14); val vp = dp(8); setPadding(hp, vp, hp, vp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
            isClickable = true; isFocusable = true
        }
        applyChipState(chip, scope.id)
        chip.setOnClickListener {
            // Never let the last chip off: an empty selection shows nothing
            // and looks like a broken search rather than a filter.
            if (scope.id in selectedScopes) {
                if (selectedScopes.size > 1) selectedScopes -= scope.id
            } else selectedScopes += scope.id
            applyChipState(chip, scope.id)
            render()
        }
        return chip
    }

    private fun applyChipState(chip: TextView, id: String) {
        val on = id in selectedScopes
        chip.alpha = if (on) 1f else 0.45f
        chip.setTextColor(if (on) 0xFFE9D8FD.toInt() else 0xFFFFFFFF.toInt())
        host?.searchChipBackground()?.takeIf { it != 0 }?.let {
            chip.background = androidx.core.content.ContextCompat.getDrawable(chip.context, it)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Scope id used when the app declares no `ui.search_scopes` at all. */
        const val ALL = "all"
        /** Fragment tag AND back-stack name — the host uses it to avoid
         *  stacking two sheets on a double tap, and to pop this one. */
        const val BACK_STACK_TAG = "search_sheet"
        fun newInstance() = SearchSheet()
    }
}
