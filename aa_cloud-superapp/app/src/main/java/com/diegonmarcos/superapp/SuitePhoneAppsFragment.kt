package com.diegonmarcos.superapp

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.json.JSONArray

/**
 * Suite section's Phone tab — curated phone apps grouped under the
 * SAME titled sections used by Suite/Cloud (AI · Data Primary · Data
 * Apps · Tools Primary · Tools Dashboards · Configs). Source of truth
 * = build.json::sections[id=suite].phone_app_groups, baked into
 * BuildConfig.UI_SUITE_PHONE_GROUPS_B64 by app/build.gradle.
 *
 * Render shape mirrors GroupedTilesFragment (Cloud-side):
 *   • One subhead per group title.
 *   • UI_PHONE_GRID_COLUMNS-col grid of icon tiles below it.
 *   • Empty groups (every package missing / uninstalled) silently
 *     skipped so the visual layout never has dead headers.
 *
 * Missing packages (uninstalled, or wrong package guess) silently
 * skipped — editing build.json never breaks render OR build.
 */
class SuitePhoneAppsFragment : Fragment() {

    private data class Group(val title: String, val packages: List<String>)

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

        val groups = parseGroups()
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val me = Process.myUserHandle()
        val byPkg = launcher.getActivityList(null, me)
            .groupBy { it.applicationInfo.packageName }
        val columns = BuildConfig.UI_PHONE_GRID_COLUMNS

        var anyRendered = false
        for (group in groups) {
            val resolved = group.packages.mapNotNull { pkg ->
                val info = byPkg[pkg]?.firstOrNull() ?: return@mapNotNull null
                Triple(pkg, info.label.toString(), info.getIcon(ctx.resources.displayMetrics.densityDpi))
            }
            if (resolved.isEmpty()) continue
            anyRendered = true
            root.addView(subhead(ctx, group.title))
            for (rowChunk in resolved.chunked(columns)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for ((pkg, label, icon) in rowChunk) {
                    row.addView(makeAppTile(ctx, pkg, label, icon))
                }
                // Pad short trailing row with weighted spacers so the last
                // row stays left-aligned within its group.
                repeat(columns - rowChunk.size) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                root.addView(row)
            }
        }
        if (!anyRendered) {
            root.addView(TextView(ctx).apply {
                text = "No curated apps installed yet — edit build.json::sections[id=suite].phone_app_groups to fix the package list, or install the apps."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            })
        }
        return scroll
    }

    private fun parseGroups(): List<Group> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_SUITE_PHONE_GROUPS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").ifBlank { continue }
                val pkgs = o.optJSONArray("packages") ?: continue
                val pkgList = buildList {
                    for (j in 0 until pkgs.length()) {
                        val pkg = pkgs.optString(j)
                        if (pkg.isNotBlank()) add(pkg)
                    }
                }
                add(Group(title, pkgList))
            }
        }
    }.getOrDefault(emptyList())

    private fun subhead(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 4))
    }

    private fun makeAppTile(
        ctx: Context,
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable,
    ) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
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
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = SuitePhoneAppsFragment() }
}
