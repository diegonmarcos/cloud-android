package com.diegonmarcos.superapp.ai

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
        col.addView(caption(ctx, "Mock data shown until the cloud metrics source is wired. Both tables show per-row + per-column + global totals."))

        // Tokens table — per-model rows × in/out/cached cols + row & col totals.
        val tokenData = listOf(
            Triple("Servicer 4b",   intArrayOf(1_240_890, 234_567, 891_234), null),
            Triple("Tasks 70b",     intArrayOf(  567_890, 123_456, 234_567), null),
            Triple("Frontier",      intArrayOf(   89_012,  12_345,  56_789), null),
        )
        col.addView(tokensTable(ctx, tokenData))

        // Cost table — per-model rows × in/out cols + row & col totals.
        // Dollar values are floats; we display 2 decimal places.
        val costData = listOf(
            Triple("Servicer 4b", doubleArrayOf(1.24, 0.47), null),
            Triple("Tasks 70b",   doubleArrayOf(0.85, 0.46), null),
            Triple("Frontier",    doubleArrayOf(1.34, 0.15), null),
        )
        col.addView(costTable(ctx, costData))

        // ── Models (suggestions) ──
        col.addView(sectionHeader(ctx, "Models"))
        col.addView(caption(ctx, "Recommended candidates per slot tier. Each slot lists one Anthropic + one Gemini + two Chinese open-source picks sized for that tier. params · quant · context · open-source flag · API \$/M (in/out) · self-host VPS \$/h. Prices indicative as of mid-2026."))

        col.addView(modelsGroup(ctx, "Model 4b (Servicer)", listOf(
            ModelInfo("Claude Haiku 4.5",          "—",                   "—",  "200k", openSource = false, api = "\$1   / \$5",      vps = "—"),
            ModelInfo("Gemini 2.5 Flash-Lite",     "—",                   "—",  "1M",   openSource = false, api = "\$0.10 / \$0.40",  vps = "—"),
            ModelInfo("Qwen 2.5-3B Instruct",      "3B",                  "Q4", "128k", openSource = true,  api = "\$0.05 / \$0.10",  vps = "~\$0.05/h (small GPU)"),
            ModelInfo("DeepSeek V2-Lite",          "16B (2.4B act, MoE)", "Q4", "32k",  openSource = true,  api = "\$0.07 / \$0.28",  vps = "~\$0.10/h (1×L40S)"),
        )))

        col.addView(modelsGroup(ctx, "Model 70b (Tasks)", listOf(
            ModelInfo("Claude Sonnet 4.6",         "—",                   "—",  "200k", openSource = false, api = "\$3    / \$15",    vps = "—"),
            ModelInfo("Gemini 2.5 Flash",          "—",                   "—",  "1M",   openSource = false, api = "\$0.075 / \$0.30", vps = "—"),
            ModelInfo("Qwen 2.5-72B Instruct",     "72B",                 "Q4", "128k", openSource = true,  api = "\$0.20 / \$0.60",  vps = "~\$0.40/h (2×H100)"),
            ModelInfo("DeepSeek V3",               "671B (37B act, MoE)", "FP8/Q4", "128k", openSource = true, api = "\$0.27 / \$1.10", vps = "~\$0.50/h (4×H100)"),
        )))

        col.addView(modelsGroup(ctx, "Frontier (Architecture)", listOf(
            ModelInfo("Claude Opus 4.7",           "—",                   "—",  "200k", openSource = false, api = "\$15  / \$75",     vps = "—"),
            ModelInfo("Gemini 2.5 Pro",            "—",                   "—",  "2M",   openSource = false, api = "\$1.25 / \$5",     vps = "—"),
            ModelInfo("DeepSeek R1",               "671B (37B act, MoE)", "FP8/Q4", "128k", openSource = true, api = "\$0.55 / \$2.19", vps = "~\$0.50/h (4×H100)"),
            ModelInfo("Qwen 3 Max Reasoning",      "~250B (MoE)",         "FP8", "256k", openSource = true,  api = "\$0.60 / \$2.40",  vps = "~\$1.00/h (8×H100)"),
        )))

        col.addView(modelsGroup(ctx, "Frontier · Batch (50% off)", listOf(
            ModelInfo("Claude Opus 4.7 Batch",     "—",                   "—",  "200k", openSource = false, api = "\$7.50 / \$37.50", vps = "—"),
            ModelInfo("Gemini 2.5 Pro Batch",      "—",                   "—",  "2M",   openSource = false, api = "\$0.625 / \$2.50", vps = "—"),
            ModelInfo("DeepSeek R1 (off-peak)",    "671B (37B act, MoE)", "FP8/Q4", "128k", openSource = true, api = "\$0.135 / \$0.55", vps = "~\$0.50/h (4×H100)"),
            ModelInfo("Qwen 3 Max Batch",          "~250B (MoE)",         "FP8", "256k", openSource = true,  api = "\$0.30 / \$1.20",  vps = "~\$1.00/h (8×H100)"),
        )))

        // ── Action bar — three buttons in one row at the bottom ──
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(6); setPadding(0, dp(16), 0, pad)
        }
        actions.addView(actionButton(ctx, "Import All") { importLauncher.launch("application/json") })
        actions.addView(actionButton(ctx, "Export All") { exportLauncher.launch("ai-config.json") })
        actions.addView(actionButton(ctx, "Paste Config") { pasteConfig() })
        col.addView(actions)

        return scroll
    }

    // ── Action-bar plumbing ──────────────────────────────────────────────

    /** Open a JSON file picker → apply its contents to AiPrefs. */
    private val importLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                val body = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@runCatching
                applyConfig(body)
                redrawFragment()
                toast("Imported.")
            }.onFailure { toast("Import failed: ${it.message}") }
        }

    /** SAF "create document" → write serialized config to the chosen file. */
    private val exportLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                val body = serializeAll()
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                toast("Exported.")
            }.onFailure { toast("Export failed: ${it.message}") }
        }

    private fun pasteConfig() {
        val ctx = requireContext()
        val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
        val text = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) { toast("Clipboard empty."); return }
        runCatching {
            applyConfig(text)
            redrawFragment()
            toast("Pasted.")
        }.onFailure { toast("Paste failed: ${it.message}") }
    }

    /** Read every slot's four persisted fields into a single JSON object,
     *  one nested object per slot keyed by slot.key. */
    private fun serializeAll(): String {
        val root = org.json.JSONObject()
        for (slot in AiPrefs.Slot.values()) {
            root.put(slot.key, org.json.JSONObject().apply {
                put("name",    prefs.name(slot))
                put("apiUrl",  prefs.apiUrl(slot))
                put("token",   prefs.token(slot))
                put("costCap", prefs.costCap(slot))
                put("dailyCap", prefs.dailyCap(slot))
            })
        }
        return root.toString(2)
    }

    /** Apply the inverse — overwrites all five fields per declared slot.
     *  Throws on malformed JSON so the caller can toast the error. */
    private fun applyConfig(body: String) {
        val root = org.json.JSONObject(body)
        for (slot in AiPrefs.Slot.values()) {
            val o = root.optJSONObject(slot.key) ?: continue
            if (o.has("name"))    prefs.setName(slot,    o.optString("name"))
            if (o.has("apiUrl"))  prefs.setApiUrl(slot,  o.optString("apiUrl"))
            if (o.has("token"))   prefs.setToken(slot,   o.optString("token"))
            if (o.has("costCap")) prefs.setCostCap(slot, o.optString("costCap"))
            if (o.has("dailyCap")) prefs.setDailyCap(slot, o.optBoolean("dailyCap"))
        }
    }

    private fun redrawFragment() {
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }

    /** One model's facts. */
    private data class ModelInfo(
        val name: String,
        val params: String,
        val quant: String,
        val context: String,
        val openSource: Boolean,
        val api: String,   // "input$/M  /  output$/M"
        val vps: String,   // self-host hourly cost, or "—" when closed
    )

    /** Group of models under a provider header. Each model gets a small
     *  card showing params/quant/context + price line. */
    private fun modelsGroup(ctx: Context, title: String, models: List<ModelInfo>): View {
        val card = tableCard(ctx, title)
        for (m in models) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(6); setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
                layoutParams = lp
                setBackgroundColor(0x221A0033)
            }
            // Name + open-source badge.
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(ctx).apply {
                text = m.name
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(TextView(ctx).apply {
                text = if (m.openSource) "open-source" else "closed"
                setTextColor(if (m.openSource) 0xFF22C55E.toInt() else 0xFF94A3B8.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(head)
            // Specs line.
            row.addView(TextView(ctx).apply {
                text = "params ${m.params} · quant ${m.quant} · ctx ${m.context}"
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(2), 0, 0)
            })
            // Price line.
            row.addView(TextView(ctx).apply {
                text = "API ${m.api} per 1M  |  VPS ${m.vps}"
                setTextColor(0xFFB794F4.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(1), 0, 0)
                setTextIsSelectable(true)
            })
            card.addView(row)
        }
        return card
    }

    // ── Report tables ────────────────────────────────────────────────────

    private fun tokensTable(ctx: Context, data: List<Triple<String, IntArray, Nothing?>>): View {
        val card = tableCard(ctx, "Tokens usage per model")
        val header = listOf("Model", "Input", "Output", "Cached", "Total")
        card.addView(tableRow(ctx, header, isHeader = true))
        val nf = NumberFormat.getNumberInstance(Locale.US)
        val colTotals = IntArray(3)
        var grand = 0
        for ((model, cells, _) in data) {
            val rowTotal = cells.sum()
            grand += rowTotal
            for (i in cells.indices) colTotals[i] += cells[i]
            card.addView(tableRow(ctx, listOf(
                model,
                nf.format(cells[0]),
                nf.format(cells[1]),
                nf.format(cells[2]),
                nf.format(rowTotal),
            )))
        }
        card.addView(tableRow(ctx, listOf(
            "TOTAL",
            nf.format(colTotals[0]),
            nf.format(colTotals[1]),
            nf.format(colTotals[2]),
            nf.format(grand),
        ), isFooter = true))
        return card
    }

    private fun costTable(ctx: Context, data: List<Triple<String, DoubleArray, Nothing?>>): View {
        val card = tableCard(ctx, "Cost breakdown (USD)")
        val header = listOf("Model", "Input \$", "Output \$", "Total \$")
        card.addView(tableRow(ctx, header, isHeader = true))
        val cf = NumberFormat.getCurrencyInstance(Locale.US)
        val colTotals = DoubleArray(2)
        var grand = 0.0
        for ((model, cells, _) in data) {
            val rowTotal = cells.sum()
            grand += rowTotal
            for (i in cells.indices) colTotals[i] += cells[i]
            card.addView(tableRow(ctx, listOf(
                model,
                cf.format(cells[0]),
                cf.format(cells[1]),
                cf.format(rowTotal),
            )))
        }
        card.addView(tableRow(ctx, listOf(
            "TOTAL",
            cf.format(colTotals[0]),
            cf.format(colTotals[1]),
            cf.format(grand),
        ), isFooter = true))
        return card
    }

    private fun tableCard(ctx: Context, title: String): LinearLayout {
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
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        return card
    }

    private fun tableRow(
        ctx: Context,
        cells: List<String>,
        isHeader: Boolean = false,
        isFooter: Boolean = false,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            if (isFooter) setBackgroundColor(0x55B794F4.toInt())
        }
        for ((i, txt) in cells.withIndex()) {
            // Column 0 is the model label (wide, left-aligned); the rest
            // are numeric, narrow + right-aligned (mono) for readability.
            val isLabel = (i == 0)
            row.addView(TextView(ctx).apply {
                text = txt
                if (isLabel) {
                    setTextColor(if (isHeader || isFooter) 0xFFE9D8FD.toInt() else 0xCCFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                } else {
                    setTextColor(if (isHeader || isFooter) 0xFFE9D8FD.toInt() else 0xFFB794F4.toInt())
                    typeface = Typeface.MONOSPACE
                    gravity = android.view.Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (isHeader || isFooter) setTypeface(typeface, Typeface.BOLD)
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setTextIsSelectable(true)
            })
        }
        return row
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): AiFragment = AiFragment()
    }
}
