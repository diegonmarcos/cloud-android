package com.diegonmarcos.superapp.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Gboard-style listening popup content (built in code, no XML, to stay
 * self-contained): a title, a live waveform bar driven by mic amplitude, the
 * live partial transcript, and a Cancel button that stops listening + closes.
 */
@SuppressLint("ViewConstructor")
class VoiceOverlayView(context: Context, onCancel: () -> Unit) : LinearLayout(context) {

    private val wave = WaveBarView(context)
    private val partialView: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xF21B1B20.toInt()) // matches the translate bar's dark frame
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)

        addView(TextView(context).apply {
            text = "🎤  Listening…"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }, rowLp(matchWidth = true, top = dp(4)))

        addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(16) })

        partialView = TextView(context).apply {
            setTextColor(0xFFB9A7FF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        addView(partialView, rowLp(matchWidth = true, top = dp(12)))

        addView(Button(context).apply {
            text = "Cancel"
            setOnClickListener { onCancel() }
        }, rowLp(matchWidth = false, top = dp(16)))
    }

    fun setLevel(level: Float) = wave.setLevel(level)

    fun setPartial(text: String) { partialView.text = text }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rowLp(matchWidth: Boolean, top: Int) = LayoutParams(
        if (matchWidth) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = top }

    /** Animated bar visualiser. Each amplitude update advances the phase and sets
     *  the level (smoothed attack/decay); bars draw a sine envelope * level so the
     *  bar "dances" with the voice and idles low when silent. */
    private class WaveBarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A6CFF.toInt() }
        private val bars = 24
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
            val gap = dp(3f)
            val barW = (w - gap * (bars - 1)) / bars
            val cy = h / 2f
            for (i in 0 until bars) {
                val env = 0.35f + 0.65f * abs(sin((phase + i * 0.5f).toDouble()).toFloat())
                val bh = h * 0.12f + h * 0.8f * level * env
                val x = i * (barW + gap)
                rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}
