package com.diegonmarcos.superapp

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.NumberFormat
import java.util.Locale

/**
 * Configs → AI page. Two sections:
 *  • Tokens — three model slots (Server / Tasks / Architecture). Each
 *    has a name + token field; auto-persisted via [AiPrefs] on
 *    keystroke change. Same pattern ProfileFragment uses.
 *  • Reports — token-usage breakdown + cost breakdown, MOCK data for
 *    now so the layout is real; real numbers wire in when the backend
 *    metric source is plumbed.
 */
class AiFragment : Fragment() {

    private lateinit var prefs: AiPrefs

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = AiPrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(col)

        // ── Tokens ──
        col.addView(sectionHeader(ctx, "Tokens"))
        col.addView(caption(ctx, "Per-model credentials. Auto-saved on change; real secrets-management lives in the cloud vault."))

        for (slot in AiPrefs.Slot.values()) {
            col.addView(modelLabel(ctx, "Model ${slot.key} (${slot.role})"))
            col.addView(label(ctx, "Name"))
            col.addView(field(ctx, prefs.name(slot)) { prefs.setName(slot, it) })
            col.addView(label(ctx, "API URL"))
            col.addView(field(ctx, prefs.apiUrl(slot)) { prefs.setApiUrl(slot, it) }.apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            })
            col.addView(label(ctx, "Token"))
            col.addView(field(ctx, prefs.token(slot)) { prefs.setToken(slot, it) }.apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            })
            col.addView(label(ctx, "Cost Cap — Limit \$/Month"))
            col.addView(field(ctx, prefs.costCap(slot)) { prefs.setCostCap(slot, it) }.apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            })
            col.addView(android.widget.Switch(ctx).apply {
                text = "Cap Daily Proportionally"
                isChecked = prefs.dailyCap(slot)
                val pad = dp(6); setPadding(pad, pad, pad, pad)
                setOnCheckedChangeListener { _, checked -> prefs.setDailyCap(slot, checked) }
            })
        }

        // ── Reports ──
        col.addView(sectionHeader(ctx, "Reports"))
        col.addView(caption(ctx, "Mock data shown until the cloud metrics source is wired."))
        col.addView(reportCard(ctx,
            title = "Tokens usage per model",
            rows  = listOf(
                "Model 4b q4 (Server)"          to "in 1,240,890 · out 234,567 · cached 891,234",
                "Model 70b q4 (Tasks)"          to "in 567,890   · out 123,456 · cached 234,567",
                "Model Frontier (Architecture)" to "in 89,012    · out 12,345  · cached 56,789",
            ),
        ))
        col.addView(reportCard(ctx,
            title = "Cost breakdown (USD)",
            rows  = listOf(
                "Model 4b q4 (Server)"          to "$1.71 · in $1.24 · out $0.47",
                "Model 70b q4 (Tasks)"          to "$1.31 · in $0.85 · out $0.46",
                "Model Frontier (Architecture)" to "$1.49 · in $1.34 · out $0.15",
                "TOTAL (last 30 days)"          to "$4.51",
            ),
        ))

        return scroll
    }

    // ── widget factories ─────────────────────────────────────────────────

    private fun sectionHeader(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, dp(16), 0, dp(4))
        }

    private fun caption(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0x99FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, 0, 0, dp(8))
        }

    private fun modelLabel(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFB794F4.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(0, dp(14), 0, dp(2))
        }

    private fun label(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(8), 0, dp(2))
        }

    private fun field(ctx: Context, initial: String, save: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
            })
        }

    private fun reportCard(ctx: Context, title: String, rows: List<Pair<String, String>>): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            setBackgroundColor(0x331A0033)
        }
        card.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        for ((k, v) in rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(2))
            }
            row.addView(TextView(ctx).apply {
                text = k
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = v
                setTextColor(0xFFB794F4.toInt())
                typeface = Typeface.MONOSPACE
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setTextIsSelectable(true)
            })
            card.addView(row)
        }
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): AiFragment = AiFragment()
    }
}
