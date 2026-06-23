package com.diegonmarcos.superapp.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Dictation card (compact, floats above the keyboard). A rounded dark card with:
 * a header (mic + title + gradient waveform reacting to the voice), a live
 * partial hint, an editable transcript field, and pill action buttons
 * (Copy / Translate / Insert / ✕). Built entirely in code, no XML.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class VoiceOverlayView(
    context: Context,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onInsert: () -> Unit,
    onClose: () -> Unit,
) : FrameLayout(context) {

    private val wave = WaveBarView(context)
    private val hintView: TextView
    private val editor: EditText

    private companion object {
        const val ACCENT = 0xFF8A6CFF.toInt()
        const val CARD = 0xFF211E2E.toInt()
        const val CARD_EDGE = 0xFF3A3552.toInt()
        const val FIELD = 0xFF2C2842.toInt()
        const val NEUTRAL = 0xFF3A3552.toInt()
        const val DANGER = 0xFF5A2E45.toInt()
        const val TEXT = 0xFFF2EEFF.toInt()
        const val MUTED = 0xFFB9A7FF.toInt()
    }

    init {
        // Transparent root with padding → the card floats with margins.
        val margin = dp(8)
        setPadding(margin, margin, margin, margin)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(CARD)
                setStroke(dp(1), CARD_EDGE)
            }
            elevation = dp(12).toFloat()
            val p = dp(16)
            setPadding(p, dp(14), p, dp(14))
        }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Header: mic dot + title + waveform.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        header.addView(TextView(context).apply {
            text = "Voice"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        header.addView(wave, LinearLayout.LayoutParams(0, dp(34), 1f))
        card.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        hintView = TextView(context).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "Listening…"
        }
        card.addView(hintView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        editor = EditText(context).apply {
            setTextColor(TEXT)
            setHintTextColor(0x80FFFFFF.toInt())
            hint = "Speak — your words appear here…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(FIELD) }
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLines(3)
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        card.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("Copy", NEUTRAL, TEXT, onCopy), pillLp())
        actions.addView(pill("Translate", NEUTRAL, TEXT, onTranslate), pillLp())
        actions.addView(pill("Insert", ACCENT, Color.WHITE, onInsert), pillLp())
        actions.addView(pill("✕", DANGER, TEXT, onClose), pillLp(grow = 0.6f))
        card.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    fun setLevel(level: Float) = wave.setLevel(level)

    fun setPartialHint(text: String) { hintView.text = if (text.isEmpty()) "Listening…" else "… $text" }

    fun appendFinal(text: String) {
        if (text.isEmpty()) return
        val cur = editor.text?.toString().orEmpty()
        val joined = if (cur.isBlank()) text else "${cur.trimEnd()} $text"
        editor.setText(joined)
        editor.setSelection(editor.text.length)
    }

    fun currentText(): String = editor.text?.toString().orEmpty().trim()
    fun replaceText(text: String) { editor.setText(text); editor.setSelection(editor.text.length) }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        stateListAnimator = null
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
        val ph = dp(6)
        setPadding(dp(4), ph, dp(4), ph)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun pillLp(grow: Float = 1f) = LinearLayout.LayoutParams(0, dp(44), grow).apply {
        leftMargin = dp(3); rightMargin = dp(3)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Gradient bar visualiser driven by mic amplitude. */
    private class WaveBarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bars = 22
        private var level = 0f
        private var phase = 0f
        private val rect = RectF()

        fun setLevel(l: Float) {
            level = max(level * 0.62f, l)
            phase += 0.4f
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                0xFF6C4CFF.toInt(), 0xFFB66CFF.toInt(), Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val gap = dp(2.5f)
            val barW = (w - gap * (bars - 1)) / bars
            val cy = h / 2f
            for (i in 0 until bars) {
                val env = 0.3f + 0.7f * abs(sin((phase + i * 0.5f).toDouble()).toFloat())
                val bh = h * 0.14f + h * 0.82f * level * env
                val x = i * (barW + gap)
                rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}
