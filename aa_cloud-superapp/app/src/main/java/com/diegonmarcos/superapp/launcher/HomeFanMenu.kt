package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.R

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

    /** Home's own fixed 4-bubble layout — unchanged by the generic
     *  [show] overload other bottom-nav items now use (build.json::
     *  sections[*].long_press). Kept separate per the user's "Home
     *  stays as it is" instruction. */
    val HOME_ITEMS = listOf(
        "action:open_home_apps"   to (R.drawable.ic_home_apps to "Home Apps"),
        "page:browser/all"        to (R.drawable.ic_world     to "Browser"),
        "page:apptabs/grid"       to (R.drawable.ic_mode_apps to "Tabs"),
        "action:check_updates"    to (R.drawable.ic_refresh   to "Update"),
    )

    /** items ordering = render order = bubble index. Top entry FIRST so
     *  bubbles[0] is the centered top bubble; commit() reads items[idx]
     *  by the same index the finger-detection loop fills in. 1 item ⇒
     *  top-only; 2-4 items ⇒ 1 top + rest along the bottom row. */
    fun show(host: View, items: List<Pair<String, Pair<Int, String>>>, onPick: (target: String) -> Unit): Controller {
        val ctx = host.context
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isClickable = false; isFocusable = false
            val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
            // Don't clip children — the highlighted bubble scales up 1.35×
            // and the icon scales up further; clipping would chop the
            // overflow back into the bubble's bounds.
            clipChildren = false; clipToPadding = false
        }
        val topRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val bottomRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        container.addView(topRow)
        container.addView(bottomRow)
        // bubbles[0] goes in the top row; bubbles[1..3] in the bottom row.
        // The order matches `items` so commit() resolves the same index
        // the finger-detection loop highlights.
        val bubbles = items.mapIndexed { i, (target, art) ->
            val bubble = makeBubble(ctx, art.first, art.second)
            bubble.tag = target
            if (i == 0) topRow.addView(bubble) else bottomRow.addView(bubble)
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

        // Fan-in animation matched to the triangle layout:
        //   • bubbles[0] = top centered → drop in from above (translationY < 0).
        //   • bubbles[1..3] = bottom row, 3 wide → outer bubbles slide
        //     inward toward the centred Tabs bubble; all rise from below
        //     so the row reads as one coordinated motion.
        for ((i, b) in bubbles.withIndex()) {
            if (i == 0) {
                animateIn(b, fromX = 0f, fromY = -dp(ctx, 20).toFloat())
            } else {
                val bIdx = i - 1                       // 0..2 in bottom row
                val bMid = 1f                          // bottom row has 3 items
                val fromX = (bMid - bIdx) * dp(ctx, 60).toFloat()
                animateIn(b, fromX = fromX, fromY = dp(ctx, 20).toFloat())
            }
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

    private fun animateIn(v: View, fromX: Float, fromY: Float = 20f) {
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
