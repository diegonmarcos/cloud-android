package com.diegonmarcos.superapp

import com.diegonmarcos.superapp.core.NotificationStore
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
        // Header row: title on the left, "Clear" action on the right.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 6))
        }
        header.addView(TextView(ctx).apply {
            text = "Notifications"
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        // Viewing the in-app feed is also the moment to dismiss any
        // matching framework notifications so the launcher icon badge
        // clears — keeps the launcher's "1" badge and our feed in sync.
        runCatching {
            (ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager)?.cancelAll()
        }
        val entries = NotificationStore.all(ctx)
        if (entries.isNotEmpty()) {
            header.addView(TextView(ctx).apply {
                text = "Clear"
                setTextColor(0xFFB794F4.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    NotificationStore.clear(ctx)
                    parentFragmentManager.popBackStack()
                }
            })
        }
        panel.addView(header)

        // Feed comes from NotificationStore. Producers: CrashLogger
        // (writes "App crashed" on every uncaught throwable),
        // App.detectVersionBump (writes "Updated to vc:N" on first
        // launch after a versionCode bump).
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 360),
            )
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (entries.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "No notifications yet.\n\nProducers wired:\n  • Updater  — version-bump on launch\n  • Crash    — uncaught exceptions"
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), dp(ctx, 16))
            })
        } else {
            for (e in entries) list.addView(notificationRow(ctx, e))
        }
        scroll.addView(list)
        panel.addView(scroll)

        backdrop.addView(panel)
        return backdrop
    }

    private fun notificationRow(ctx: android.content.Context, e: NotificationStore.Entry): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 6) }
            layoutParams = lp
            // Severity-coloured stripe via background tint.
            val bg = when (e.severity) {
                NotificationStore.Sev.ERROR -> 0x55B91C1C.toInt()
                NotificationStore.Sev.WARN  -> 0x55D97706.toInt()
                else                        -> 0x331A0033
            }
            setBackgroundColor(bg)
        }
        // Title + small "<source> · <time-ago>" header.
        val meta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        meta.addView(TextView(ctx).apply {
            text = e.title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        meta.addView(TextView(ctx).apply {
            text = "${e.source} · ${fmtAgo(System.currentTimeMillis() - e.ts)}"
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
        row.addView(meta)
        row.addView(TextView(ctx).apply {
            text = e.body
            setTextColor(0xCCE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        return row
    }

    private fun fmtAgo(ms: Long): String = when {
        ms < 60_000           -> "just now"
        ms < 3_600_000        -> "${ms / 60_000}m ago"
        ms < 86_400_000       -> "${ms / 3_600_000}h ago"
        else                  -> "${ms / 86_400_000}d ago"
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val BACK_STACK_TAG = "notification_center"
        fun newInstance(): NotificationCenterFragment = NotificationCenterFragment()
    }
}
