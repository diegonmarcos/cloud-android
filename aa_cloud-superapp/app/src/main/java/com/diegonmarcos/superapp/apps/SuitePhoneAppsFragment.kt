package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.datamanager.AppUsageProvider

import android.app.Dialog
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    private data class Folder(val label: String, val packages: List<String>)
    private data class Group(
        val title: String,
        val packages: List<String>,
        val folders: List<Folder>,
    )
    /** Resolved app payload — populated after LauncherApps lookup so
     *  both the plain-grid tile and the folder-dialog tile can launch
     *  via the same `pkg` + `label` + `icon` tuple. */
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

        // ── Quickmarks — the curated groups/folders/active-apps content
        //    that used to be this fragment's entire page. Now the first
        //    of three stacked sections on one scrollable page.
        root.addView(subhead(ctx, "Quickmarks"))

        val groups = parseGroups()
        // READ THE WARM CACHE, DO NOT RE-ENUMERATE.
        //
        // This used to call LauncherApps.getActivityList(null, me) itself and
        // then info.getIcon(densityDpi) once per Quickmark — a full launcher
        // enumeration plus one icon DECODE per package, on the main thread, in
        // onCreateView. PhoneAppsFragment.warmUp already did exactly that work
        // on a background thread at launch (MainActivity.onCreate) and holds the
        // result, icons included, so every millisecond of it was being paid
        // twice: once early where nobody was waiting, and again here where the
        // user is staring at a frozen tab.
        //
        // snapshot() returns that cache, or enumerates once if the user somehow
        // beat the warm-up thread here — the same fall-through renderAllApps
        // below already relies on.
        val byPkg = PhoneAppsFragment.snapshot(ctx).associateBy { it.packageName }
        val columns = BuildConfig.UI_PHONE_GRID_COLUMNS

        fun resolve(pkg: String): AppInfo? {
            byPkg[pkg]?.let { app ->
                // icon is nullable on PhoneApp (getBadgedIcon can throw).
                val icon = app.icon
                    ?: runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
                if (icon != null) return AppInfo(pkg = pkg, label = app.label, icon = icon)
            }
            // The cache is a LOOKUP TABLE here, not the guest list. snapshot()
            // drops this app's own package and applies the launcher-profile
            // whitelist, and a Quickmark legitimately names both — one of the
            // declared groups points at com.diegonmarcos.superapp itself. The
            // old code enumerated raw LauncherApps and so showed them; falling
            // back to a direct PackageManager resolve keeps that behaviour
            // exactly, and costs one lookup for the handful of packages the
            // cache filters out instead of a full enumeration for all of them.
            val pm = ctx.packageManager
            return runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppInfo(pkg = pkg, label = pm.getApplicationLabel(ai).toString(), icon = pm.getApplicationIcon(ai))
            }.getOrNull()
        }

        var anyRendered = false
        for (group in groups) {
            val packageTiles = group.packages.mapNotNull(::resolve)
            // Resolve each folder's payload; SKIP folders whose contents
            // are all uninstalled (mirrors the "no dead headers" rule).
            val folderTiles = group.folders.mapNotNull { folder ->
                val resolved = folder.packages.mapNotNull(::resolve)
                if (resolved.isEmpty()) null else folder to resolved
            }
            if (packageTiles.isEmpty() && folderTiles.isEmpty()) continue
            anyRendered = true
            root.addView(subhead(ctx, group.title))

            // Render packages first, then folder tiles, in one continuous
            // grid — so the folder cards flow naturally as the next
            // column after the last app. Tile builders are heterogenous
            // (View, not the same type) so we use a List<View> and
            // chunk over it.
            val tiles = mutableListOf<View>()
            for (a in packageTiles) tiles.add(makeAppTile(ctx, a.pkg, a.label, a.icon))
            for ((folder, contents) in folderTiles) tiles.add(makeFolderTile(ctx, folder.label, contents))

            for (rowChunk in tiles.chunked(columns)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for (tile in rowChunk) row.addView(tile)
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
        // ── Active Apps — the most-recently-used apps, rendered below the
        //    last curated group (Configs) and above the More footer.
        //    Data-driven: build.json::sections[id=suite].active_apps →
        //    UsageStatsManager recency via libs:datamanager. Hidden when the
        //    usage-access grant is missing (recentUsed → empty) or nothing
        //    launchable matches. Own package filtered out (it's always hot).
        if (BuildConfig.UI_SUITE_ACTIVE_APPS_ENABLED) {
            val recent = AppUsageProvider.recentUsed(ctx)
                .asSequence()
                .filter { it != ctx.packageName }
                .mapNotNull { resolve(it) }
                .take(BuildConfig.UI_SUITE_ACTIVE_APPS_LIMIT.coerceAtLeast(1))
                .toList()
            if (recent.isNotEmpty()) {
                root.addView(subhead(ctx, BuildConfig.UI_SUITE_ACTIVE_APPS_TITLE))
                for (rowChunk in recent.chunked(columns)) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    }
                    for (a in rowChunk) row.addView(makeAppTile(ctx, a.pkg, a.label, a.icon))
                    repeat(columns - rowChunk.size) {
                        row.addView(View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        })
                    }
                    root.addView(row)
                }
            }
        }

        // ── All Apps — every installed app grouped by purpose. Same
        //    rendering PhoneAppsFragment uses standalone (the Phone tab
        //    of the swipe-up app drawer); embedded inline here instead
        //    of navigating to a separate "more" screen.
        root.addView(sectionDivider(ctx))
        // ── Below the fold: built AFTER the first frame ──────────────────
        //
        // All Apps renders a tile per app across ~31 folders and Smart Folders
        // renders several more — hundreds of ImageView+TextView pairs, none of
        // them recycled (this is a ScrollView, not a RecyclerView). Doing that
        // inline meant onCreateView did not return until every one existed, so
        // the tab stayed frozen on the PREVIOUS page for the whole build and
        // the page appeared already-scrolled-to-top and complete — the "opens
        // extremely slowly" symptom.
        //
        // Quickmarks is what the user actually looks at first and it is small,
        // so it stays inline: the page now appears as soon as it is built. The
        // two heavy sections are appended on later frames, one section per
        // frame so neither one blocks the other, and the scroll position does
        // not move because they are added BELOW the visible content.
        //
        // ponytail: one section per frame, not one row per frame. If All Apps
        // alone still drops frames on the slowest device, chunk its rows the
        // same way rather than reaching for a RecyclerView rewrite.
        root.post {
            if (!isAdded) return@post
            root.addView(subhead(ctx, "All Apps"))
            PhoneAppsFragment.renderAllApps(ctx, root)

            // ── Smart Folders — dynamic folders (Samsung, Google, Recent 7,
            //    …), same shared renderer as PhoneAppsFragment. Self-headed.
            root.post {
                if (!isAdded) return@post
                root.addView(sectionDivider(ctx))
                PhoneAppsFragment.renderSmartFolders(ctx, root)
            }
        }

        return scroll
    }

    private fun parseGroups(): List<Group> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_SUITE_PHONE_GROUPS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        // Plain mutable list + for-loop because `continue` inside an
        // inline-lambda body (buildList { ... }) is an experimental
        // Kotlin feature gated behind a compiler flag — using a
        // regular for-loop sidesteps the gate entirely.
        val out = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val pkgs = o.optJSONArray("packages") ?: continue
            val pkgList = mutableListOf<String>()
            for (j in 0 until pkgs.length()) {
                val pkg = pkgs.optString(j)
                if (pkg.isNotBlank()) pkgList.add(pkg)
            }
            // Optional `folders` array — One-UI-style nested folders
            // inside the group. Each folder = { label, packages[] }.
            // Absent or empty → group renders as a flat grid (legacy
            // behaviour, no schema break).
            val folderList = mutableListOf<Folder>()
            val foldersArr = o.optJSONArray("folders")
            if (foldersArr != null) {
                for (k in 0 until foldersArr.length()) {
                    val fo = foldersArr.optJSONObject(k) ?: continue
                    val fLabel = fo.optString("label")
                    if (fLabel.isBlank()) continue
                    val fPkgs = fo.optJSONArray("packages") ?: continue
                    val fPkgList = mutableListOf<String>()
                    for (m in 0 until fPkgs.length()) {
                        val pkg = fPkgs.optString(m)
                        if (pkg.isNotBlank()) fPkgList.add(pkg)
                    }
                    if (fPkgList.isNotEmpty()) folderList.add(Folder(fLabel, fPkgList))
                }
            }
            out.add(Group(title, pkgList, folderList))
        }
        out
    }.getOrDefault(emptyList())

    /** Thin separator line between the Quickmarks / All Apps / Smart
     *  Folders sections on the merged page. */
    private fun sectionDivider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1),
        ).apply { topMargin = dp(ctx, 16); bottomMargin = dp(ctx, 4) }
    }

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
        // Long-press → stock Android-style options menu (shortcuts +
        // App info + Uninstall). Mirrors what Pixel Launcher / One UI
        // do; same helper PhoneAppsFragment uses so behaviour is
        // identical across both Phone surfaces.
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
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    /** Folder tile rendered in-line with the package grid — same cell
     *  width as a single-app tile so it slots seamlessly. Shows a 2×2
     *  mini-icon preview of the first four installed apps inside; tap
     *  opens a fullscreen modal dialog with every app in the folder
     *  (5-column grid, same chrome as PhoneAppsFragment's expanded
     *  folder view). Mirrors One UI's app-folder UX. */
    private fun makeFolderTile(
        ctx: Context,
        label: String,
        contents: List<AppInfo>,
    ): View {
        val cellSize = dp(ctx, 52)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            val outVal = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
            setOnClickListener { showFolderDialog(ctx, label, contents) }
        }
        // Glass-bg square holding the 2×2 mini-preview — visually
        // distinguishes folder tiles from plain-icon tiles.
        val square = FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass)
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
        }
        val mini = GridLayout(ctx).apply {
            rowCount = 2; columnCount = 2
            val p = dp(ctx, 5); setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val miniSize = (cellSize - dp(ctx, 16)) / 2
        for (slot in 0 until 4) {
            val iv = ImageView(ctx).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = miniSize; height = miniSize
                    setMargins(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            contents.getOrNull(slot)?.icon?.let { iv.setImageDrawable(it) }
            mini.addView(iv)
        }
        square.addView(mini)
        column.addView(square)
        column.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFFL.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        return column
    }

    /** Modal dialog showing every app inside a folder, 5-col grid.
     *  Tap an app → launch + dismiss. Tap the dim outside area to
     *  dismiss without launching. */
    private fun showFolderDialog(
        ctx: Context,
        label: String,
        contents: List<AppInfo>,
    ) {
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE0A0A14.toInt())
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 16))
        })
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        scroll.addView(grid)
        // Match the phone grid column count (default 6), not a hardcoded 5.
        val cols = BuildConfig.UI_PHONE_GRID_COLUMNS
        for (rowChunk in contents.chunked(cols)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (a in rowChunk) row.addView(makeExpandedAppTile(ctx, a, dialog))
            repeat(cols - rowChunk.size) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            grid.addView(row)
        }
        sheet.addView(scroll)
        dialog.setContentView(sheet)
        dialog.show()
    }

    /** Full-size app tile inside the expanded folder dialog. Tap
     *  launches via PackageManager + dismisses the dialog. */
    private fun makeExpandedAppTile(
        ctx: Context,
        app: AppInfo,
        dialog: Dialog,
    ): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener {
                runCatching {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                    if (intent != null) ctx.startActivity(intent)
                }
                dialog.dismiss()
            }
            // Long-press inside folder modal — dismiss the folder
            // dialog first so the long-press menu sits cleanly on
            // its own dim backdrop, matching the PhoneAppsFragment
            // folder-tile behaviour.
            setOnLongClickListener {
                dialog.dismiss()
                AppLongPressMenu.show(ctx, app.pkg)
                true
            }
        }
        tile.addView(ImageView(ctx).apply {
            setImageDrawable(app.icon)
            val sz = dp(ctx, 48)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        })
        tile.addView(TextView(ctx).apply {
            text = app.label
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            val mt = dp(ctx, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = mt }
        })
        return tile
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = SuitePhoneAppsFragment() }
}
