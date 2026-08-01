package com.diegonmarcos.superapp.translate

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Gboard-style translate bar, hosted INSIDE the keyboard frame (LatinIME adds
 * it above the suggestion strip; onComputeInsets reserves its height so the
 * host app reflows up). Mimics Gboard:
 *
 *   ┌───────────────────────────────────────────┐
 *   │ [From ▾]   ⇄   [To ▾]                   ✕  │
 *   │ type here…                                 │   ← input field (key routing)
 *   └───────────────────────────────────────────┘
 *
 * Input model: while the bar is open, LatinIME routes key presses into this
 * view's buffer (not the app field); the live TRANSLATION is what's committed
 * to the app field as output. From defaults to "auto" (detect); To defaults to
 * the active subtype language. Both are tappable selectors.
 *
 * Lives in libs:translate so sync-heliboard's verbatim mirror never deletes it.
 */
class TranslateBarView(context: Context) : LinearLayout(context) {

    /** Supplies the current InputConnection (the IME's getCurrentInputConnection). */
    fun interface IcProvider { fun get(): InputConnection? }

    // Every language the engine can translate — DATA-DRIVEN from the client,
    // no hardcoded list. "auto" (detect) is a From-only option. Sorted by
    // display name for the picker. Computed fresh on each read (not cached):
    // the AIDL client (cloud-keyboard) binds its service asynchronously, so a
    // one-shot `by lazy` snapshot taken before the bind completes would freeze
    // the list at empty forever and make the language chips look unresponsive.
    private fun toLangs(): List<String> =
        (TranslateEngines.client?.supportedLanguages() ?: emptyList())
            .sortedBy { java.util.Locale(it).displayLanguage }
    private fun fromLangs(): List<String> = listOf("auto") + toLangs()

    private var icp: IcProvider? = null
    private var onClose: Runnable? = null
    private var fromTag = "auto"
    private var toTag = "en"

    private val buffer = StringBuilder()
    private var lastOutput = ""          // what we've committed to the app field

    private val fromChip: TextView
    private val toChip: TextView
    private val inputView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(8))
        setBackgroundColor(0xF21B1B20.toInt())

        // ── Row 1: From  ⇄  To ............................. ✕ ───────────────
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        fromChip = chip(chipLabel(fromTag)) { showLangMenu(fromChip, true) }
        toChip = chip(chipLabel(toTag)) { showLangMenu(toChip, false) }
        row.addView(fromChip)
        row.addView(TextView(context).apply {
            text = "  ⇄  "; setTextColor(0xFFB0B0B8.toInt()); textSize = 15f
            isClickable = true; setOnClickListener { swap() }
        })
        row.addView(toChip)
        row.addView(View(context), LayoutParams(0, 1, 1f)) // spacer
        row.addView(TextView(context).apply {
            text = "✕"; setTextColor(0xFFB0B0B8.toInt()); textSize = 16f
            setPadding(dp(10), dp(2), dp(4), dp(2)); isClickable = true
            setOnClickListener { onClose?.run() }
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: input field (the buffer the keys type into) ───────────────
        inputView = TextView(context).apply {
            textSize = 16f; setTextColor(Color.WHITE); maxLines = 3
            setPadding(0, dp(6), 0, 0)
        }
        addView(inputView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        renderInput()
    }

    /** Wire the bar to the IME. [defaultTarget] is the active subtype language. */
    fun bind(provider: IcProvider, defaultTarget: String, onCloseAction: Runnable) {
        icp = provider
        onClose = onCloseAction
        toTag = if (toLangs().contains(defaultTarget)) defaultTarget else "en"
        toChip.text = chipLabel(toTag)
    }

    /** Called by LatinIME each time the bar is (re)shown — fresh session. */
    fun onShown() {
        buffer.setLength(0); lastOutput = ""
        renderInput()
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    fun appendCodePoint(cp: Int) { buffer.appendCodePoint(cp); onChanged() }
    fun backspace() {
        if (buffer.isNotEmpty()) {
            val last = buffer.length - 1
            // drop a surrogate pair as one character
            if (last > 0 && Character.isLowSurrogate(buffer[last]) && Character.isHighSurrogate(buffer[last - 1]))
                buffer.setLength(last - 1) else buffer.setLength(last)
        }
        onChanged()
    }

    private fun onChanged() {
        renderInput()
        pending?.let { ui.removeCallbacks(it) }
        val text = buffer.toString()
        if (text.isBlank()) { pushOutput(""); return }
        val job = Runnable {
            Translator.liveTranslateFromTo(text, fromTag, toTag) { res ->
                if (buffer.toString() != text) return@liveTranslateFromTo  // stale
                pushOutput(res ?: "")
            }
        }
        pending = job
        ui.postDelayed(job, 300)
    }

    /** Replace the previously-committed translation in the app field with [out]. */
    private fun pushOutput(out: String) {
        val ic = icp?.get() ?: return
        ic.beginBatchEdit()
        if (lastOutput.isNotEmpty()) ic.deleteSurroundingText(lastOutput.length, 0)
        if (out.isNotEmpty()) ic.commitText(out, 1)
        lastOutput = out
        ic.endBatchEdit()
    }

    private fun renderInput() {
        if (buffer.isEmpty()) {
            inputView.text = "Type to translate…"
            inputView.setTextColor(0xFF6E6E78.toInt())
            inputView.setTypeface(null, Typeface.ITALIC)
        } else {
            inputView.text = buffer
            inputView.setTextColor(Color.WHITE)
            inputView.setTypeface(null, Typeface.NORMAL)
        }
    }

    /** Chip caption: the code (or "auto") + a dropdown caret. */
    private fun chipLabel(tag: String): String =
        (if (tag == "auto") "auto" else tag).uppercase() + " ▾"

    /** Tap a chip → scrollable language picker (all ML Kit langs). From includes
     *  "auto". Anchored to the chip; selecting sets the language + re-translates. */
    private fun showLangMenu(anchor: View, isFrom: Boolean) {
        val codes = if (isFrom) fromLangs() else toLangs()
        if (codes.isEmpty()) {
            android.widget.Toast.makeText(context, "Translate engine not connected yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = codes.map {
            if (it == "auto") "Auto-detect"
            else java.util.Locale(it).displayLanguage.let { n ->
                if (n.equals(it, ignoreCase = true)) it.uppercase() else "$n  ($it)"
            }
        }
        val lpw = android.widget.ListPopupWindow(context)
        lpw.anchorView = anchor
        lpw.isModal = true
        lpw.width = dp(240)
        lpw.height = dp(300)
        lpw.setAdapter(android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
        lpw.setOnItemClickListener { _, _, pos, _ ->
            val sel = codes[pos]
            if (isFrom) { fromTag = sel; fromChip.text = chipLabel(sel) }
            else { toTag = sel; toChip.text = chipLabel(sel) }
            lpw.dismiss(); onChanged()
        }
        lpw.show()
    }

    private fun swap() {
        if (fromTag == "auto") return            // can't put auto on the target side
        val f = fromTag; fromTag = toTag; toTag = f
        fromChip.text = chipLabel(fromTag); toChip.text = chipLabel(toTag)
        onChanged()
    }

    // ── view helpers ─────────────────────────────────────────────────────────
    private fun chip(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f
        setPadding(dp(12), dp(3), dp(12), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF34343F.toInt()) }
        isClickable = true; setOnClickListener { onTap() }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); layoutParams = lp
    }
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
