package com.diegonmarcos.superapp.notificationcenter
import com.diegonmarcos.devcontrol.CrashLogger
import com.diegonmarcos.superapp.App

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.kdeconnect.KdeDevicePrefs
import com.diegonmarcos.superapp.kdeconnect.KdeIdentity
import com.diegonmarcos.superapp.kdeconnect.KdePluginPrefs
import com.diegonmarcos.superapp.kdeconnect.KdePluginRegistry
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

        // ── Cloud SA - KDE — live KDE Connect status badge ─────────────────
        runCatching { panel.addView(buildKdeBadge(ctx)) }

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

    // Status-light colours (shared with the KDE page).
    private val OK   = 0xFF4CAF50.toInt()   // green — connected / paired / handled
    private val WARN = 0xFFFFB300.toInt()   // amber — paired but not connected / advertised-only
    private val IDLE = 0xFF9E9E9E.toInt()   // gray  — off / not paired

    /** A small round status light. */
    private fun dot(ctx: android.content.Context, color: Int, size: Int = 9, end: Int = 6): View =
        View(ctx).apply {
            val s = dp(ctx, size)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(ctx, end) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color)
            }
        }

    private fun line(ctx: android.content.Context, text: String, color: Int = 0xCCE9D8FD.toInt(),
                     size: Int = 12): TextView = TextView(ctx).apply {
        this.text = text; setTextColor(color); textSize = size.toFloat()
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    /**
     * "Cloud SA - KDE" — a rich, live KDE-Connect status badge: KDE logo,
     * overall light, this-device identity, a per-device connection+pairing
     * line, the plugin tally with a 28-dot grid (enabled+handled/advertised/
     * off), and paired-device count. Reads straight from KdeConnectManager /
     * KdeConnectConfig at open time. Tap to dismiss.
     */
    private fun buildKdeBadge(ctx: android.content.Context): View {
        KdeConnectManager.init(ctx)
        val cfg = KdeConnectConfig.get()
        val prefs = KdePluginPrefs(ctx)
        val dprefs = KdeDevicePrefs(ctx)
        val handledPackets = KdePluginRegistry.plugins.flatMap { it.incoming + it.outgoing }.toSet()

        val anyConnected = cfg.devices.any { it.id.isNotBlank() && KdeConnectManager.isConnected(it.id) }
        val anyPaired    = cfg.devices.any { it.id.isNotBlank() && KdeConnectManager.isPaired(it.id) }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 12); setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat(); setColor(0x33B794F4)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 6) }
            isClickable = true
            setOnClickListener { parentFragmentManager.popBackStack() }
        }

        // Header: KDE logo + title + overall status light.
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF0A0A0A.toInt())
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)).apply { marginEnd = dp(ctx, 8) }
        })
        head.addView(TextView(ctx).apply {
            text = "Cloud SA - KDE"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(dot(ctx, if (anyConnected) OK else if (anyPaired) WARN else IDLE, size = 11, end = 4))
        head.addView(TextView(ctx).apply {
            text = if (anyConnected) "Connected" else if (anyPaired) "Paired" else "Offline"
            setTextColor(0xCCE9D8FD.toInt()); textSize = 11f
        })
        card.addView(head)

        // This device identity.
        card.addView(line(ctx,
            "📱 This device: ${KdeIdentity.deviceName(ctx)} · ${KdeConnectManager.ownDeviceId().take(12)}…",
            color = 0x99FFFFFF.toInt(), size = 11))

        // Per declared device: two lights (connection + pairing) + endpoint.
        for (dev in cfg.devices) {
            val host = dprefs.host(dev.id, dev.wgIp)
            val port = dprefs.port(dev.id, cfg.discoveryPort)
            val connected = dev.id.isNotBlank() && KdeConnectManager.isConnected(dev.id)
            val paired    = dev.id.isNotBlank() && KdeConnectManager.isPaired(dev.id)
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 4), 0, 0)
            }
            r.addView(dot(ctx, if (connected) OK else IDLE))
            r.addView(dot(ctx, if (paired) OK else IDLE))
            r.addView(TextView(ctx).apply {
                text = "${dprefs.label(dev.id, dev.label)}   $host:$port"
                setTextColor(0xFFE9D8FD.toInt()); textSize = 12.5f
            })
            card.addView(r)
            card.addView(line(ctx,
                "      🔗 ${if (connected) "Connected" else "Not connected"}   " +
                    "🔒 ${if (paired) "Paired" else "Not paired"}",
                color = 0x99FFFFFF.toInt(), size = 11))
        }

        // Plugin tally + a 28-dot grid (green handled+on · amber advertised-only · gray off).
        val onCount      = cfg.plugins.count { prefs.isEnabled(it.id) }
        val handledCount = cfg.plugins.count { it.packet in handledPackets && prefs.isEnabled(it.id) }
        card.addView(line(ctx,
            "🔌 Plugins: $onCount enabled · $handledCount handled · ${cfg.plugins.size} total",
            color = 0xFFE9D8FD.toInt(), size = 12))
        var rowDots: LinearLayout? = null
        cfg.plugins.forEachIndexed { i, pl ->
            if (i % 14 == 0) {
                rowDots = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 3), 0, 0)
                }
                card.addView(rowDots)
            }
            val on = prefs.isEnabled(pl.id)
            val handled = pl.packet in handledPackets
            rowDots?.addView(dot(ctx, if (!on) IDLE else if (handled) OK else WARN, size = 8, end = 4))
        }

        // Paired-device count + hint.
        card.addView(line(ctx,
            "✅ Paired devices: ${KdeConnectManager.pairedDeviceIds().size}    ·    tap to close",
            color = 0x77FFFFFF.toInt(), size = 11))
        return card
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
