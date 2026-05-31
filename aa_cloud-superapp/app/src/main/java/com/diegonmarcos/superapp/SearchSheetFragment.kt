package com.diegonmarcos.superapp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Top-toolbar search sheet — live-filters a flat index of every
 * navigable target in the app: sections, pages, home actions, and
 * aggregator tiles. Each result row is tappable and dispatches via
 * the same target-grammar the rest of the app uses (section: / page: /
 * action: / http: …).
 *
 * Built by hand (no RecyclerView dependency) — list rarely exceeds a
 * few hundred items, fits comfortably in a ScrollView.
 */
class SearchSheetFragment : Fragment() {

    private data class Hit(val label: String, val target: String, val crumb: String)

    private val allHits: List<Hit> by lazy { buildIndex() }
    private lateinit var resultsBox: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0000000.toInt())
            val pad = dp(12); setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
        }
        val edit = EditText(ctx).apply {
            hint = "Search the app…"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass)
            val ip = dp(14); setPadding(ip, ip, ip, ip)
            isSingleLine = true
        }
        root.addView(edit)
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
                renderResults(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderResults("")   // initial render — all entries

        edit.requestFocus()
        val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        edit.postDelayed({ imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }, 120)
        return root
    }

    private fun renderResults(query: String) {
        resultsBox.removeAllViews()
        val q = query.trim().lowercase()
        val hits = if (q.isEmpty()) allHits.take(30) else allHits.filter {
            it.label.lowercase().contains(q) || it.crumb.lowercase().contains(q)
        }.take(50)
        if (hits.isEmpty()) {
            resultsBox.addView(emptyRow("No matches for “$query”"))
            return
        }
        for (hit in hits) resultsBox.addView(resultRow(hit))
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
            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(hit.target)
            // Pop the sheet so the destination's screen is visible.
            activity?.supportFragmentManager?.popBackStack(
                BACK_STACK_TAG,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        }
        return row
    }

    private fun emptyRow(msg: String): TextView =
        TextView(requireContext()).apply {
            text = msg
            alpha = 0.65f
            setTextColor(0xFFFFFFFF.toInt())
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }

    /** Flat index of everything navigable in the app. */
    private fun buildIndex(): List<Hit> {
        val out = mutableListOf<Hit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            out += Hit(sec.label, "section:${sec.id}", "Section")
            for (page in sec.pages) {
                out += Hit(page.label, "page:${sec.id}/${page.id}", "${sec.label} · Page")
            }
            for (tile in (sec.tilesShared + sec.tilesApps + sec.tilesAdmin)) {
                out += Hit(tile.label, tile.target, "${sec.label} · Tile")
            }
        }
        for (act in Sections.homeActions()) {
            out += Hit(act.label, "action:${act.actionType}", "Action")
        }
        return out
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val BACK_STACK_TAG = "search_sheet"
        fun newInstance() = SearchSheetFragment()
    }
}
