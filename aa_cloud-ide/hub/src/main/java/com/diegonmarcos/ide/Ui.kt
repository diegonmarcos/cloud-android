package com.diegonmarcos.ide

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared UI kit for the hub — one place for the visual language so every screen
 * looks like ONE app (dark surface, purple accent #7C3AED, rounded cards,
 * ripple feedback, monospace data values). Pure-code views (no XML inflation)
 * so screens stay data-driven and theme-consistent.
 */
object Ui {
    const val BG          = 0xFF0B0B10.toInt()   // near-black surface
    const val CARD        = 0xFF17171F.toInt()   // card surface
    const val CARD_STROKE = 0x33FFFFFF           // hairline
    const val ACCENT      = 0xFF7C3AED.toInt()   // family purple
    const val ACCENT_SOFT = 0xFFB794F4.toInt()   // value text
    const val TEXT        = 0xFFF5F3FF.toInt()
    const val TEXT_DIM    = 0x99FFFFFF.toInt()

    fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    /** Rounded card background with ripple. */
    fun cardBg(a: Activity, accent: Boolean = false): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(a, 18).toFloat()
            setColor(if (accent) ACCENT else CARD)
            setStroke(maxOf(1, dp(a, 1)), CARD_STROKE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    /** Big app card: glyph bubble + title + status line. */
    fun appCard(
        a: Activity, glyph: String, titleText: String, statusText: String,
        enabled: Boolean, onTap: () -> Unit,
    ): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(a)
            isClickable = true; isFocusable = true
            elevation = dp(a, 4).toFloat()
            alpha = if (enabled) 1f else 0.55f
            setPadding(dp(a, 18), dp(a, 20), dp(a, 18), dp(a, 20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(a, 14) }
            setOnClickListener { onTap() }
        }
        // Glyph bubble.
        row.addView(TextView(a).apply {
            text = glyph
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(a, 14).toFloat()
                setColor(ACCENT)
            }
            layoutParams = LinearLayout.LayoutParams(dp(a, 52), dp(a, 52)).apply {
                rightMargin = dp(a, 16)
            }
        })
        // Title + status.
        row.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(a).apply {
                text = titleText
                textSize = 17f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(a).apply {
                text = statusText
                textSize = 12f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(a, 3), 0, 0)
            })
        })
        // Chevron.
        row.addView(TextView(a).apply {
            text = "›"
            textSize = 22f
            setTextColor(ACCENT_SOFT)
        })
        return row
    }

    /** Small secondary row card (e.g. Configs). */
    fun rowCard(a: Activity, label: String, onTap: () -> Unit): View =
        TextView(a).apply {
            text = label
            textSize = 14f
            setTextColor(TEXT_DIM)
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(a)
            isClickable = true; isFocusable = true
            setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(a, 6) }
            setOnClickListener { onTap() }
        }

    fun header(a: Activity, titleText: String, subText: String): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 4), dp(a, 8), dp(a, 4), dp(a, 22))
            addView(TextView(a).apply {
                text = titleText
                textSize = 26f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(a).apply {
                text = subText
                textSize = 12f
                setTextColor(TEXT_DIM)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(a, 4), 0, 0)
            })
        }

    /** Apply the shared dark surface to a screen root. */
    fun screen(a: Activity, root: View) {
        root.setBackgroundColor(BG)
        a.window.statusBarColor = BG
        a.window.navigationBarColor = BG
    }

    /** First letter glyph for an app name ("Acode" → "A"). */
    fun glyphFor(name: String): String =
        name.firstOrNull()?.uppercase() ?: "?"
}
