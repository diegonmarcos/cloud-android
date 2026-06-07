package com.diegonmarcos.superapp

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Cloud Minimalist Black — the terminal-style home pane rendered when
 * LauncherTheme.CloudMinimalistBlack is active. Pure black background,
 * monospace green text, one column scroll listing every launchable
 * app on the device. Tapping a row launches its main activity via
 * [LauncherApps].
 *
 * No chrome — no toolbar, no bottom nav, no dynamic island. The
 * activity hides those when the theme is active so the screen reads
 * as a real terminal.
 *
 * Filter row at the top is a single-line monospace prompt
 * ("$ filter:") that does a case-insensitive label substring filter
 * against the loaded app list — KISS-style index search, not regex.
 */
class MinimalistBlackFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private var allApps: List<AppRow> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Terminal prompt / header
        root.addView(TextView(ctx).apply {
            text = "$ ~/apps"
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF00FF66.toInt())   // bright terminal green
            textSize = 16f
            setPadding(dp(ctx, 16), dp(ctx, 28), dp(ctx, 16), dp(ctx, 8))
        })

        // Filter input — a single line, monospace, no border.
        root.addView(EditText(ctx).apply {
            hint = "filter:"
            setHintTextColor(0xFF335533.toInt())
            setTextColor(0xFF99FF99.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 14f
            background = null
            setSingleLine(true)
            setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    rebuildList(s?.toString().orEmpty())
                }
            })
        })

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        scroll.addView(listContainer)
        root.addView(scroll)

        // Footer — terminal-style status line
        root.addView(TextView(ctx).apply {
            text = "—  esc / home → reload  •  taps to launch"
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF335533.toInt())
            textSize = 11f
            gravity = Gravity.START
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 24))
        })

        // Load apps async-ish (LauncherApps.getActivityList is fast on
        // a populated device but not free — do it on the UI thread for
        // now since it's already cached internally by the system).
        allApps = loadApps(ctx)
        rebuildList("")

        return root
    }

    private fun rebuildList(filter: String) {
        listContainer.removeAllViews()
        val ctx = listContainer.context
        val needle = filter.trim().lowercase()
        val visible = if (needle.isBlank()) allApps
                      else allApps.filter { it.label.lowercase().contains(needle) }
        for (app in visible) {
            listContainer.addView(TextView(ctx).apply {
                text = app.label
                typeface = Typeface.MONOSPACE
                setTextColor(0xFFB8E6B8.toInt())  // soft green app text
                textSize = 14f
                setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Haptics.tap(it)
                    runCatching { app.launch() }
                }
            })
        }
        if (visible.isEmpty()) {
            listContainer.addView(TextView(ctx).apply {
                text = "— no matches —"
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF335533.toInt())
                textSize = 13f
                setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            })
        }
    }

    private data class AppRow(
        val label: String,
        val launch: () -> Unit,
    )

    private fun loadApps(ctx: Context): List<AppRow> {
        val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val user = Process.myUserHandle()
        return runCatching {
            launcherApps.getActivityList(null, user)
                .map { info ->
                    val label = info.label?.toString().orEmpty()
                    val component = info.componentName
                    AppRow(
                        label = label.ifBlank { component.packageName },
                        launch = {
                            launcherApps.startMainActivity(component, user, null, null)
                        },
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MinimalistBlackFragment() }
}
