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
 * Suite section's Phone tab — flat grid of the user's curated phone
 * apps (mirrors the One UI home-screen taxonomy, folders flattened).
 * Source of truth = build.json::sections[id=suite].phone_apps, baked
 * into BuildConfig.UI_SUITE_PHONE_APPS_B64 by app/build.gradle.
 *
 * Different from PhoneAppsFragment (Home Apps swipe-up sheet):
 *   • PhoneAppsFragment shows ALL launchable apps grouped into
 *     folders via PhoneAppClassifier + build.json::ui.phone_folders.
 *   • THIS fragment shows ONLY the explicitly listed packages, in
 *     declared order, as a flat 6-col grid (UI_PHONE_GRID_COLUMNS).
 *
 * Missing apps (uninstalled, or wrong package guess in build.json)
 * are silently skipped — editing the JSON list never breaks render.
 */
class SuitePhoneAppsFragment : Fragment() {

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

        val packages = parseCuratedList()
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val me = Process.myUserHandle()
        // One enumeration pass — build pkg -> first launchable activity
        // index so the curated order is the render order.
        val byPkg = launcher.getActivityList(null, me)
            .groupBy { it.applicationInfo.packageName }
        val resolved = packages.mapNotNull { pkg ->
            val info = byPkg[pkg]?.firstOrNull() ?: return@mapNotNull null
            Triple(pkg, info.label.toString(), info.getIcon(ctx.resources.displayMetrics.densityDpi))
        }

        val columns = BuildConfig.UI_PHONE_GRID_COLUMNS
        if (resolved.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No curated apps installed yet — edit build.json::sections[id=suite].phone_apps to fix the package list, or install the apps."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            })
            return scroll
        }
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
            // row stays left-aligned (One UI behaviour).
            repeat(columns - rowChunk.size) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            root.addView(row)
        }
        return scroll
    }

    private fun parseCuratedList(): List<String> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_SUITE_PHONE_APPS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

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
        // Selector-free ripple via system attr ?attr/selectableItemBackgroundBorderless.
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
