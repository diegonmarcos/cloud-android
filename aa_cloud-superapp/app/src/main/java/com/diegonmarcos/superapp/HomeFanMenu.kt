package com.diegonmarcos.superapp

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Long-press of the Home bottom-nav slot opens this two-icon
 * "folder-widget"-style overlay above the nav. Each icon fans up into
 * place with a small overshoot; tapping (or releasing finger on)
 * either commits its action.
 *
 *   Configs → section:config
 *   Tabs    → section:tabs
 *
 * Implemented as a PopupWindow anchored to the home tab so it
 * survives configuration / decor changes and disappears on outside
 * tap automatically.
 */
object HomeFanMenu {

    fun show(host: View, onPick: (target: String) -> Unit) {
        val ctx = host.context
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            isClickable = true; isFocusable = true
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, pad)
        }
        val configs = makeBubble(ctx, R.drawable.ic_settings, "Configs") {
            onPick("section:config")
        }
        val tabs = makeBubble(ctx, R.drawable.ic_mode_apps, "Tabs") {
            onPick("section:tabs")
        }
        container.addView(configs)
        container.addView(tabs)

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp(ctx, 8).toFloat()
            setBackgroundDrawable(null)
        }
        val onPickDismiss: (String) -> Unit = { target ->
            popup.dismiss()
            onPick(target)
        }
        configs.setOnClickListener { onPickDismiss("section:config") }
        tabs   .setOnClickListener { onPickDismiss("section:tabs") }

        // Anchor above the bottom-nav home slot, biased to the centre.
        val xOff = host.width / 2 - dp(ctx, 90)
        val yOff = -dp(ctx, 140)
        popup.showAsDropDown(host, xOff, yOff, Gravity.TOP)
        animateIn(configs, fromX = dp(ctx, 30).toFloat(), fromY = dp(ctx, 30).toFloat())
        animateIn(tabs,    fromX = -dp(ctx, 30).toFloat(), fromY = dp(ctx, 30).toFloat())
    }

    private fun makeBubble(
        ctx: Context, iconRes: Int, label: String, onClick: () -> Unit,
    ): View {
        val sz = dp(ctx, 64)
        val frame = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(sz + dp(ctx, 16), sz + dp(ctx, 28))
        }
        val bubble = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1A0033.toInt())
                setStroke(2, 0x55B794F4)
            }
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply { setMargins(dp(ctx, 8), 0, 0, 0) }
            isClickable = true; isFocusable = true
        }
        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFB794F4.toInt())
            val pad = dp(ctx, 14)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        bubble.addView(icon)
        bubble.setOnClickListener { onClick() }
        val text = TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = sz + dp(ctx, 4)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            layoutParams = lp
        }
        frame.addView(bubble)
        frame.addView(text)

        // Place each bubble side-by-side via translation done in animateIn.
        return frame
    }

    private fun animateIn(v: View, fromX: Float, fromY: Float) {
        v.translationX = fromX; v.translationY = fromY; v.alpha = 0f
        val ax = ObjectAnimator.ofFloat(v, "translationX", fromX, 0f)
        val ay = ObjectAnimator.ofFloat(v, "translationY", fromY, 0f)
        val aa = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f)
        AnimatorSet().apply {
            duration = 220
            interpolator = OvershootInterpolator(1.6f)
            playTogether(ax, ay, aa)
            start()
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
