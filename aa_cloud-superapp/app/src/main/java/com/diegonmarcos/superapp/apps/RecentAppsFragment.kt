package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.datamanager.AppUsageProvider
import com.diegonmarcos.superapp.launcher.AppLongPressMenu

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Full-screen grid of the last 24 recently-opened Android apps, 3 per
 * row, icon + label. Data source: AppUsageProvider.recentUsed (ranked
 * most-recent first). Own package filtered out; packages that cannot be
 * resolved to a launchable activity are skipped silently.
 *
 * Reachable via tile target "page:recentapps/grid".
 */
class RecentAppsFragment : Fragment() {

    private data class AppInfo(val pkg: String, val label: String, val icon: Drawable)

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, dp(ctx, 96))
        }
        scroll.addView(root)

        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val me = Process.myUserHandle()
        val byPkg = launcher.getActivityList(null, me)
            .groupBy { it.applicationInfo.packageName }

        fun resolve(pkg: String): AppInfo? {
            val info = byPkg[pkg]?.firstOrNull() ?: return null
            return AppInfo(
                pkg   = pkg,
                label = info.label.toString(),
                icon  = info.getIcon(ctx.resources.displayMetrics.densityDpi),
            )
        }

        val recent = AppUsageProvider.recentUsed(ctx)
            .asSequence()
            .filter { it != ctx.packageName }
            .mapNotNull { resolve(it) }
            .take(24)
            .toList()

        if (recent.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No recent apps yet — grant usage access"
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 24), dp(ctx, 48), dp(ctx, 24), dp(ctx, 8))
            })
        } else {
            val columns = 3
            for (rowChunk in recent.chunked(columns)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for (a in rowChunk) row.addView(makeAppTile(ctx, a.pkg, a.label, a.icon))
                // Pad short trailing row with weighted spacers so the last
                // row stays left-aligned.
                repeat(columns - rowChunk.size) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                root.addView(row)
            }
        }

        return scroll
    }

    private fun makeAppTile(
        ctx: Context,
        pkg: String,
        label: String,
        icon: Drawable,
    ) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        isClickable = true
        isFocusable = true
        val outVal = android.util.TypedValue()
        ctx.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, outVal, true)
        if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)

        setOnClickListener {
            runCatching {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) ctx.startActivity(intent)
            }
        }
        setOnLongClickListener {
            AppLongPressMenu.show(ctx, pkg)
            true
        }
        addView(ImageView(ctx).apply {
            setImageDrawable(icon)
            val sz = dp(ctx, 52)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        })
        addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFFL.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = RecentAppsFragment() }
}
