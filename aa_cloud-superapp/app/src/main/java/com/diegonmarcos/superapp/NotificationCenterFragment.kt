package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Drop-down Notification Centre — opened by tapping the Dynamic Island
 * in the activity toolbar. Slides in from the top, occupies the
 * fragment_container above the rest of the UI; tap anywhere outside
 * the panel (the dim backdrop) to dismiss.
 *
 * Content is a placeholder list of recent app events so the layout is
 * usable immediately — real notification feed wires here later via a
 * NotificationCenter source (DevControl trace lines / Updater state /
 * MailHost unread / …).
 */
class NotificationCenterFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        // Backdrop fills the surface — tap dismisses.
        val backdrop = FrameLayout(ctx).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            setOnClickListener { parentFragmentManager.popBackStack() }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // Panel — drops down from the top, glass surface, rounded.
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_liquid_glass)
            val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP,
            ).apply { setMargins(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), 0) }
            // Don't let backdrop's click pass through the panel.
            isClickable = true
        }
        panel.addView(TextView(ctx).apply {
            text = "Notifications"
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(ctx, 4), 0, 0, dp(ctx, 6))
        })

        // Placeholder list of sample notifications. Replace with a real
        // feed once a NotificationCenter source is wired.
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 360),
            )
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (n in samples()) list.addView(notificationRow(ctx, n.first, n.second))
        scroll.addView(list)
        panel.addView(scroll)

        backdrop.addView(panel)
        return backdrop
    }

    /** Inline placeholder feed — title + body pairs. */
    private fun samples(): List<Pair<String, String>> = listOf(
        "Updater" to "Tap Configs → Update to check GHCR for a new APK.",
        "Mail"    to "Inbox feed pending JMAP wiring.",
        "Trace"   to "Pull /trace from a Termux shell to read the live log.",
        "Cloud"   to "37 services declared in build.json — all green at last sync.",
    )

    private fun notificationRow(ctx: android.content.Context, title: String, body: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 6) }
            layoutParams = lp
            setBackgroundColor(0x331A0033)
        }
        row.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        })
        row.addView(TextView(ctx).apply {
            text = body
            setTextColor(0xCCE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        return row
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val BACK_STACK_TAG = "notification_center"
        fun newInstance(): NotificationCenterFragment = NotificationCenterFragment()
    }
}
