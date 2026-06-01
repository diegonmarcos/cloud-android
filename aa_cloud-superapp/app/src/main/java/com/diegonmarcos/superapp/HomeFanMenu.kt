package com.diegonmarcos.superapp

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView

/**
 * iOS-style folder-action fan menu — long-press the Home bottom-nav
 * slot, slide finger UP to one of the bubbles without releasing,
 * lift finger ON the bubble to commit its action.
 *
 *   ╭────────╮  ╭────────╮
 *   │ Tabs   │  │ Configs│
 *   ╰────╮ ╭─╯  ╰─╮ ╭────╯
 *        │ │      │ │
 *        ╰─╯      ╰─╯
 *
 * Public surface = [show], which returns a [Controller] the host
 * activity drives with the raw MotionEvent stream.
 */
object HomeFanMenu {

    interface Controller {
        /** Update which bubble is highlighted, based on finger screen position. */
        fun updateFinger(rawX: Float, rawY: Float)
        /** Commit the currently-highlighted bubble's action (if any) and dismiss. */
        fun commit()
        /** Dismiss without committing. */
        fun dismiss()
    }

    fun show(host: View, onPick: (target: String) -> Unit): Controller {
        val ctx = host.context
        // Per user spec: LEFT = Open Tabs, CENTER = Linktree, RIGHT = Update.
        val items = listOf(
            // LEFT — Open Tabs (page:tabs/all goes straight to grid).
            "page:tabs/all"           to (R.drawable.ic_mode_apps  to "Open Tabs"),
            // CENTER — Home Apps (pulls up the home-sheet icon grid).
            //   Distinct icon from Linktree (ic_tree) and from Open Tabs
            //   (ic_mode_apps); use ic_solutions = 4-dot grid that reads
            //   as "open the app drawer".
            "action:open_home_apps"   to (R.drawable.ic_solutions to "Home Apps"),
            // RIGHT — Update.
            "action:check_updates"    to (R.drawable.ic_refresh   to "Update"),
        )
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            isClickable = false; isFocusable = false
            val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
            // Don't clip children — the highlighted bubble scales up 1.25×
            // and the icon scales up further; clipping would chop the
            // overflow back into the bubble's bounds.
            clipChildren = false; clipToPadding = false
        }
        val bubbles = items.map { (target, art) ->
            val bubble = makeBubble(ctx, art.first, art.second)
            bubble.tag = target
            container.addView(bubble)
            bubble
        }

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = false
            isFocusable = false
            isTouchable = false               // do NOT steal touches — main activity drives
            elevation = dp(ctx, 10).toFloat()
            setBackgroundDrawable(null)
        }

        // Measure the container so we know its actual width, then center
        // horizontally over the host's centre on the screen.
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val containerW = container.measuredWidth
        val containerH = container.measuredHeight
        val hostPos = IntArray(2); host.getLocationOnScreen(hostPos)
        val cx = hostPos[0] + host.width / 2
        val x  = cx - containerW / 2
        val y  = hostPos[1] - containerH - dp(ctx, 12)
        popup.showAtLocation(host, android.view.Gravity.NO_GRAVITY, x, y)

        // Fan-in animation — outer bubbles fly inward toward the centre.
        val n = bubbles.size
        for ((i, b) in bubbles.withIndex()) {
            val mid = (n - 1) / 2f
            val fromX = (mid - i) * dp(ctx, 60).toFloat()
            animateIn(b, fromX = fromX)
        }

        var highlightedIdx: Int = -1

        return object : Controller {
            override fun updateFinger(rawX: Float, rawY: Float) {
                // Find the bubble nearest the finger (screen coords). To
                // figure each bubble's screen rect we use getLocationOnScreen.
                var nearest = -1; var nearestD = Float.MAX_VALUE
                val tmp = IntArray(2)
                for ((i, b) in bubbles.withIndex()) {
                    b.getLocationOnScreen(tmp)
                    val cx = tmp[0] + b.width / 2f
                    val cy = tmp[1] + b.height / 2f
                    val dx = rawX - cx; val dy = rawY - cy
                    val d = dx * dx + dy * dy
                    // Activation radius (in px²) — only highlight when finger
                    // is reasonably close (within ~140px of centre).
                    if (d < 140f * 140f && d < nearestD) {
                        nearest = i; nearestD = d
                    }
                }
                if (nearest != highlightedIdx) {
                    highlightedIdx = nearest
                    for ((i, b) in bubbles.withIndex()) {
                        val sel = i == highlightedIdx
                        b.animate()
                            .scaleX(if (sel) 1.35f else 1f)
                            .scaleY(if (sel) 1.35f else 1f)
                            .setDuration(110).start()
                        ((b.getChildAt(0) as? FrameLayout)?.background as? GradientDrawable)
                            ?.setColor(if (sel) 0xFF7C3AED.toInt() else 0xFF1A0033.toInt())
                    }
                }
            }

            override fun commit() {
                val idx = highlightedIdx
                if (idx in items.indices) {
                    val target = items[idx].first
                    onPick(target)
                }
                dismiss()
            }

            override fun dismiss() {
                runCatching { popup.dismiss() }
            }
        }
    }

    private fun makeBubble(ctx: Context, iconRes: Int, label: String): FrameLayout {
        // Bubble matches the rest of the app's icon size (~40dp). Selection
        // scale (1.35×) makes it grow to ~54dp — visibly enlarged but small
        // enough that all three fan items stay readable.
        val sz = dp(ctx, 40)
        val cell = FrameLayout(ctx).apply {
            // Cell wider than bubble so the scale-up doesn't clip horizontally.
            layoutParams = android.widget.LinearLayout.LayoutParams(sz + dp(ctx, 20), sz + dp(ctx, 26))
            clipChildren = false; clipToPadding = false
        }
        val bubble = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1A0033.toInt())
                setStroke(2, 0x99B794F4.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(sz, sz, android.view.Gravity.CENTER_HORIZONTAL)
            clipChildren = false; clipToPadding = false
        }
        val icon = ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        bubble.addView(icon)
        cell.addView(bubble)
        cell.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = sz + dp(ctx, 4)
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            }
        })
        return cell
    }

    private fun animateIn(v: View, fromX: Float) {
        v.translationX = fromX; v.translationY = 20f; v.alpha = 0f
        val ax = ObjectAnimator.ofFloat(v, "translationX", fromX, 0f)
        val ay = ObjectAnimator.ofFloat(v, "translationY", 20f, 0f)
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
