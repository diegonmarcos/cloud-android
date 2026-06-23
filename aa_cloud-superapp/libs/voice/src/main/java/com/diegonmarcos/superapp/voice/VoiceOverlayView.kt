package com.diegonmarcos.superapp.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Compact dictation box (NOT full-screen): a small floating panel shown over the
 * keyboard. Contains a title + live waveform, a live partial hint, an EDITABLE
 * transcript field that accumulates the cleaned (finalised) text across pauses,
 * and a Copy / Translate / Insert / Close action row. Built in code (no XML).
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class VoiceOverlayView(
    context: Context,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onInsert: () -> Unit,
    onClose: () -> Unit,
) : LinearLayout(context) {

    private val wave = WaveBarView(context)
    private val hint: TextView
    val editor: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF21B1B20.toInt())
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)

        // Title + compact waveform on one row.
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "🎤"
            setTextColor(Color.WHITE)
            textSize = 18f
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        header.addView(wave, LayoutParams(0, dp(28), 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        hint = TextView(context).apply {
            setTextColor(0xFFB9A7FF.toInt())
            textSize = 12f
            text = "Listening…"
        }
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // Editable transcript — capped height with scroll so the box stays compact.
        editor = EditText(context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            hint = "Your speech appears here…"
            textSize = 16f
            setBackgroundColor(0x33000000)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLines(3)
            maxLines = 5
            setHorizontallyScrolling(false)
            val p = dp(8)
            setPadding(p, p, p, p)
        }
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // Action row.
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(actionButton("Copy", onCopy), btnLp())
        actions.addView(actionButton("Translate", onTranslate), btnLp())
        actions.addView(actionButton("Insert", onInsert), btnLp())
        actions.addView(actionButton("✕", onClose), btnLp())
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    fun setLevel(level: Float) = wave.setLevel(level)

    fun setPartialHint(text: String) { hint.text = if (text.isEmpty()) "Listening…" else "… $text" }

    /** Append a finalised (cleaned) segment to the editor, preserving any manual edits + cursor. */
    fun appendFinal(text: String) {
        if (text.isEmpty()) return
        val cur = editor.text?.toString().orEmpty()
        val joined = if (cur.isEmpty()) text else "${cur.trimEnd()} $text"
        editor.setText(joined)
        editor.setSelection(editor.text.length)
    }

    fun currentText(): String = editor.text?.toString().orEmpty().trim()
    fun replaceText(text: String) { editor.setText(text); editor.setSelection(editor.text.length) }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun btnLp() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(2); rightMargin = dp(2)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Animated bar visualiser driven by mic amplitude. */
    private class WaveBarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A6CFF.toInt() }
        private val bars = 18
        private var level = 0f
        private var phase = 0f
        private val rect = RectF()

        fun setLevel(l: Float) {
            level = max(level * 0.6f, l)
            phase += 0.35f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val gap = dp(2f)
            val barW = (w - gap * (bars - 1)) / bars
            val cy = h / 2f
            for (i in 0 until bars) {
                val env = 0.35f + 0.65f * abs(sin((phase + i * 0.5f).toDouble()).toFloat())
                val bh = h * 0.15f + h * 0.8f * level * env
                val x = i * (barW + gap)
                rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}
