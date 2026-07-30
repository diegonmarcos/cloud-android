package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.settings.LauncherProfile
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherProfilePrefs
import com.diegonmarcos.superapp.search.SearchSheetFragment

import android.app.Dialog
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
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

/**
 * Phone tab of the Home Apps swipe-up sheet — Android-launcher-style
 * grouping of every installed (launchable) app into the folder
 * taxonomy declared in `build.json::ui.phone_folders`. Smart classifier
 * lives in [PhoneAppClassifier]; folder rendering follows the iOS-
 * glass-on-Samsung-grid spec the user pinned in
 * Screenshot_20260608_001142_One UI Home.jpg.
 *
 * Visual contract:
 *   • Grid of folder cards, 6 columns (from `phone_grid_columns`).
 *   • Each folder card: 2×2 mini-icon preview of its top apps inside a
 *     rounded glass-bg square, label beneath.
 *   • Tap a folder → modal dialog showing every app inside, 5 columns
 *     of full-size icons. Tap an app → launches via [LauncherApps].
 */
class PhoneAppsFragment : Fragment() {

    private lateinit var launcherApps: LauncherApps

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val rootCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, dp(ctx, 96))
        }
        scroll.addView(rootCol)

        // Content first, then a small centered refresh affordance at the
        // VERY BOTTOM — invalidates the app cache (picks up new installs
        // / uninstalls) and rebuilds the folder + smart-folder content
        // in place.
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rootCol.addView(content)
        buildContent(ctx, content)
        rootCol.addView(refreshBar(ctx) {
            invalidateCache()
            content.removeAllViews()
            buildContent(ctx, content)
        })
        return scroll
    }

    /** Centered "refresh app list" icon. */
    private fun refreshBar(ctx: Context, onRefresh: () -> Unit): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 8) }
        }
        bar.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_refresh)
            imageTintList = android.content.res.ColorStateList.valueOf(0xCCFFFFFF.toInt())
            val sz = dp(ctx, 22); val p = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(sz + 2 * p, sz + 2 * p)
            setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            contentDescription = "Refresh app list"
            val outVal = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
            setOnClickListener { Haptics.tap(it); onRefresh() }
        })
        return bar
    }

    /** true = A–Z folders (alphabetic), false = category + smart folders.
     *  In-memory only — resets to Categories each time the sheet opens. */
    private var alphaMode = false

    /** Dispatcher: segmented "Categories | Alphabetic" toggle under the
     *  host search bar, then the chosen layout. Re-invoked in place when
     *  the toggle or refresh fires (caller clears [rootCol] first). */
    private fun buildContent(ctx: Context, rootCol: LinearLayout) {
        rootCol.addView(modeToggle(ctx, rootCol))
        if (alphaMode) buildAlphabetic(ctx, rootCol) else buildCategories(ctx, rootCol)
    }

    /** Right-aligned minimalist icon toggle: folder (categories) vs
     *  sort-alpha. Active icon is bright, inactive dim. Tap flips mode
     *  and rebuilds [content] in place (same clear+rebuild shape as the
     *  refresh affordance). */
    private fun modeToggle(ctx: Context, content: LinearLayout): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 2); bottomMargin = dp(ctx, 2) }
        }
        fun rebuild() { content.removeAllViews(); buildContent(ctx, content) }
        fun iconBtn(res: Int, active: Boolean, desc: String, onPick: () -> Unit) = ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (active) 0xFFE9D8FD.toInt() else 0x55FFFFFF)
            val sz = dp(ctx, 20); val p = dp(ctx, 6)
            layoutParams = LinearLayout.LayoutParams(sz + 2 * p, sz + 2 * p)
            setPadding(p, p, p, p)
            contentDescription = desc
            isClickable = true; isFocusable = true
            val outVal = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
            setOnClickListener {
                Haptics.tap(it)
                if (active) return@setOnClickListener
                onPick(); rebuild()
            }
        }
        bar.addView(iconBtn(R.drawable.ic_p_folder, !alphaMode, "Categories") { alphaMode = false })
        bar.addView(iconBtn(R.drawable.ic_sort_alpha, alphaMode, "Alphabetic") { alphaMode = true })
        return bar
    }

    /** A–Z (plus a trailing "#" bucket for non-letter labels) folders of
     *  every launchable app, 6 per row. All 26 letter folders are always
     *  shown — empty ones render as empty glass squares, per spec. */
    private fun buildAlphabetic(ctx: Context, rootCol: LinearLayout) {
        val apps    = sCachedApps ?: collectLaunchableApps(ctx).also { sCachedApps = it }
        val columns = BuildConfig.UI_PHONE_GRID_COLUMNS
        val byLetter = LinkedHashMap<String, MutableList<PhoneApp>>()
        for (c in 'A'..'Z') byLetter[c.toString()] = mutableListOf()
        val hash = mutableListOf<PhoneApp>()
        for (app in apps) {
            val ch = app.label.trim().firstOrNull()?.uppercaseChar()
            if (ch != null && ch in 'A'..'Z') byLetter.getValue(ch.toString()).add(app) else hash.add(app)
        }
        val folders = mutableListOf<PhoneFolders.Folder>()
        val grouped = HashMap<String, List<PhoneApp>>()
        for ((letter, list) in byLetter) {
            folders.add(PhoneFolders.Folder("alpha:$letter", letter, letter, emptyList()))
            grouped["alpha:$letter"] = list.sortedBy { it.label.lowercase() }
        }
        if (hash.isNotEmpty()) {
            folders.add(PhoneFolders.Folder("alpha:#", "zz", "#", emptyList()))
            grouped["alpha:#"] = hash.sortedBy { it.label.lowercase() }
        }
        renderFolderGrid(rootCol, ctx, folders, grouped, columns)
    }

    /** Build (or rebuild) the folder grid + smart folders into [rootCol]. */
    private fun buildCategories(ctx: Context, rootCol: LinearLayout) {
        // Process-level cache populated by [warmUp] at app launch. By
        // the time the user navigates here it's usually already
        // primed → render is instant. If the user got here BEFORE the
        // warm-up thread finished (rare cold path), we fall back to a
        // synchronous load on the main thread — same blocking shape
        // as v1 of the Phone tab. The render path is otherwise
        // identical for hot + cold.
        val folders  = sCachedFolders ?: PhoneFolders.loadFromBuildConfig().also { sCachedFolders = it }
        val apps     = sCachedApps    ?: collectLaunchableApps(ctx).also { sCachedApps = it }
        val grouped  = sCachedGrouped ?: PhoneAppClassifier.groupByFolder(apps, folders).also { sCachedGrouped = it }
        val columns  = BuildConfig.UI_PHONE_GRID_COLUMNS

        // Skip empty folders entirely — One UI hides them too, and an
        // empty 2×2 placeholder reads as broken to the user. EXCEPT
        // folders marked `pin: true` in build.json::ui.phone_folders
        // (today: _Misc) — those stay visible as deliberate placeholders
        // so the user can drop apps into them via build.json keyword
        // edits.
        val visible = folders.filter { it.pinned || (grouped[it.id]?.size ?: 0) > 0 }

        // ── Bucket folders into top-level sections by label prefix
        //    (System _, Services -, Tools A ., Tools B >). Each section
        //    gets a subhead + its own grid below. Folders whose label
        //    starts with no known prefix fall through to "Other" so they
        //    don't disappear silently.
        val sections = PhoneSections.loadFromBuildConfig()
        val byPrefix = LinkedHashMap<String, MutableList<PhoneFolders.Folder>>()
        for (sec in sections) byPrefix[sec.title] = mutableListOf()
        val otherBucket = mutableListOf<PhoneFolders.Folder>()
        for (folder in visible) {
            val sec = sections.firstOrNull { folder.label.startsWith(it.prefix) }
            if (sec != null) byPrefix.getValue(sec.title).add(folder) else otherBucket.add(folder)
        }
        for (sec in sections) {
            val list = byPrefix[sec.title].orEmpty()
            if (list.isEmpty()) continue
            rootCol.addView(subhead(ctx, sec.title))
            renderFolderGrid(rootCol, ctx, list, grouped, columns)
        }
        if (otherBucket.isNotEmpty()) {
            rootCol.addView(subhead(ctx, "Other"))
            renderFolderGrid(rootCol, ctx, otherBucket, grouped, columns)
        }

        // ── Smart Folders — dynamic filters at the bottom (Samsung,
        //    Google, Alternative Stores). Same UX as a folder card:
        //    tap opens the same dialog with the filtered apps. Rules
        //    operate over the SAME master `apps` list so the contents
        //    track the source enumeration in lockstep.
        val smart = PhoneSmartFolders.loadFromBuildConfig()
        val visibleSmart = smart.mapNotNull { sf ->
            val matches = sf.select(ctx, apps)
            if (matches.isEmpty()) null else SmartRendered(sf, matches)
        }
        if (visibleSmart.isNotEmpty()) {
            rootCol.addView(subhead(ctx, "Smart Folders"))
            // Smaller "subtile" cells for Smart Folders — denser than the
            // A-Z/category grid above, and grouped under sub-labels
            // (Usage/Stores/Dev/Rank/…) per build.json's `group` field.
            val subtileCell = dp(ctx, 44)
            val subtileColumns = columns + 1
            visibleSmart.groupBy { it.spec.group ?: "Other" }.forEach { (group, rendered) ->
                rootCol.addView(subhead(ctx, group))
                // Synthesize a Folder per Smart Folder so the existing
                // renderFolderGrid + makeFolderCard helpers light up
                // unchanged. id prefixed with "smart:" so it can't collide
                // with a real folder id from build.json.
                val syntheticFolders = rendered.map { vs ->
                    PhoneFolders.Folder(
                        id            = "smart:${vs.spec.id}",
                        order         = "zz",
                        label         = vs.spec.title,
                        matchKeywords = emptyList(),
                    )
                }
                val smartGrouped = rendered.associate { vs -> "smart:${vs.spec.id}" to vs.apps }
                renderFolderGrid(rootCol, ctx, syntheticFolders, smartGrouped, subtileColumns, subtileCell)
            }
        }
    }

    private data class SmartRendered(
        val spec: PhoneSmartFolders.SmartFolder,
        val apps: List<PhoneApp>,
    )

    private fun subhead(ctx: Context, title: String) = TextView(ctx).apply {
        text = title
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 4))
    }

    /** Instance-side wrapper — delegates to the companion helper so
     *  warm-up + first-open can share the exact same enumeration code
     *  path. Kept as a fragment method so existing callers (none in
     *  Push 1, but kept symmetric) don't have to thread `ctx` twice. */
    private fun collectLaunchableApps(ctx: Context): List<PhoneApp> =
        collectLaunchableAppsStatic(ctx)

    /** Lay out [folders] as a fixed-column grid of folder cards. Uses
     *  nested LinearLayouts instead of GridLayout to avoid GridLayout's
     *  cell-size measure cycles when child widths vary. */
    private fun renderFolderGrid(
        parent: LinearLayout,
        ctx: Context,
        folders: List<PhoneFolders.Folder>,
        grouped: Map<String, List<PhoneApp>>,
        columns: Int,
        cellSize: Int = dp(ctx, 60),
    ) {
        folders.chunked(columns).forEach { rowFolders ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (folder in rowFolders) {
                val apps = grouped[folder.id].orEmpty()
                row.addView(makeFolderCard(ctx, folder, apps, cellSize))
            }
            // Pad the row with empty weighted spacers if it has fewer
            // than `columns` folders, so the last row stays left-aligned
            // (Samsung does the same — folders flow left to right, no
            // re-centring of the trailing row).
            val short = columns - rowFolders.size
            repeat(short) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            parent.addView(row)
        }
    }

    /** A single folder tile: glass-bg rounded square with a 2×2 mini-
     *  icon preview, label underneath. */
    private fun makeFolderCard(
        ctx: Context,
        folder: PhoneFolders.Folder,
        apps: List<PhoneApp>,
        cellSize: Int = dp(ctx, 60),
    ): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val side = dp(ctx, 2); val top = dp(ctx, 6)
            setPadding(side, top, side, top)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                showFolderDialog(ctx, folder, apps)
            }
        }
        // Glass square holding the 2x2 mini-preview.
        val square = FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass)
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
        }
        val mini = GridLayout(ctx).apply {
            rowCount = 2; columnCount = 2
            val p = dp(ctx, 6); setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val miniSize = (cellSize - dp(ctx, 18)) / 2  // 2 minis, 6dp pad ×3
        for (slot in 0 until 4) {
            val iv = ImageView(ctx).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = miniSize; height = miniSize
                    setMargins(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            apps.getOrNull(slot)?.icon?.let { iv.setImageDrawable(it) }
            mini.addView(iv)
        }
        square.addView(mini)
        column.addView(square)
        column.addView(TextView(ctx).apply {
            text = folder.label
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val mt = dp(ctx, 4)
            layoutParams = LinearLayout.LayoutParams(
                cellSize, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = mt }
        })
        return column
    }

    /** Open the folder in a modal dialog — full app list with launchable
     *  icons. 5 columns, like One UI's expanded folder view. */
    private fun showFolderDialog(
        ctx: Context,
        folder: PhoneFolders.Folder,
        apps: List<PhoneApp>,
    ) {
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE0A0A14.toInt())  // near-black, near-opaque
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
        }
        sheet.addView(TextView(ctx).apply {
            text = folder.label
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 16))
        })
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f,
            )
        }
        scroll.addView(grid)
        // Match the folder-grid column count (build.json::ui.phone_grid_columns,
        // default 6) instead of a hardcoded 5.
        val expandedCols = BuildConfig.UI_PHONE_GRID_COLUMNS
        apps.chunked(expandedCols).forEach { rowApps ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (app in rowApps) row.addView(makeExpandedAppTile(ctx, app, dialog))
            val short = expandedCols - rowApps.size
            repeat(short) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            grid.addView(row)
        }
        sheet.addView(scroll)
        // Tap outside (the column background) → dismiss.
        sheet.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet)
        dialog.show()
    }

    /** A single full-size launchable app tile inside the expanded
     *  folder dialog. Tap → launch via [LauncherApps.startMainActivity]
     *  + dismiss the dialog (Samsung's UX). */
    private fun makeExpandedAppTile(
        ctx: Context,
        app: PhoneApp,
        dialog: Dialog,
    ): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(ctx, 8); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                runCatching {
                    launcherApps.startMainActivity(app.activityComponent, app.user, null, null)
                }
                dialog.dismiss()
            }
            // Long-press → stock Android-style options menu (shortcuts +
            // App info + Uninstall). Mirrors what Pixel Launcher / One UI
            // do on a long-press. Dismiss the folder dialog so the menu
            // sits cleanly on its own backdrop.
            setOnLongClickListener {
                Haptics.tap(it)
                dialog.dismiss()
                AppLongPressMenu.show(ctx, app.packageName)
                true
            }
        }
        tile.addView(ImageView(ctx).apply {
            app.icon?.let { setImageDrawable(it) }
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

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance() = PhoneAppsFragment()

        /** Process-level write-ahead buffer for the Phone tab data.
         *  Populated by [warmUp] from MainActivity.onCreate so the
         *  list + folder classification is ready BEFORE the user ever
         *  swipes up to Home Apps. If the user races the warm-up
         *  thread, the synchronous fallback in [onCreateView] still
         *  works — just incurs the ~600ms load on the main thread the
         *  first time. @Volatile so the warm-up writer + the Fragment
         *  reader on different threads see consistent reference
         *  publication. */
        @Volatile private var sCachedFolders: List<PhoneFolders.Folder>? = null
        @Volatile private var sCachedApps:    List<PhoneApp>? = null
        @Volatile private var sCachedGrouped: Map<String, List<PhoneApp>>? = null

        /** Invalidate every cache slot — call from a LauncherApps.Callback
         *  if/when we wire package-change observation. Currently unused;
         *  killing + reopening the app rebuilds the cache organically. */
        fun invalidateCache() {
            sCachedFolders = null
            sCachedApps = null
            sCachedGrouped = null
        }

        /** Warm-up: kick a background Thread that enumerates installed
         *  apps + loads folder taxonomy + runs the classifier, then
         *  publishes the results to the @Volatile cache slots. Safe to
         *  call multiple times — the no-op fast path returns immediately
         *  if everything's already cached. MainActivity.onCreate fires
         *  this so the Phone tab is hot the moment the user reaches it.
         *
         *  NOT a coroutine — keeping it on a plain low-priority Thread
         *  avoids hauling in kotlinx-coroutines as an app/ dep just for
         *  one fire-and-forget warm-up. */
        fun warmUp(ctx: Context) {
            if (sCachedFolders != null && sCachedApps != null && sCachedGrouped != null) return
            Thread {
                runCatching {
                    val folders = sCachedFolders
                        ?: PhoneFolders.loadFromBuildConfig().also { sCachedFolders = it }
                    val apps = sCachedApps
                        ?: collectLaunchableAppsStatic(ctx.applicationContext)
                            .also { sCachedApps = it }
                    if (sCachedGrouped == null) {
                        sCachedGrouped = PhoneAppClassifier.groupByFolder(apps, folders)
                    }
                }
            }.apply {
                name = "PhoneAppsFragment.warmUp"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        /** Public snapshot for callers OUTSIDE this fragment that need
         *  the launchable-apps list — currently [SearchSheetFragment]'s
         *  "Phone (apps)" scope. Returns the warm-up cache when
         *  present (zero work), otherwise falls through to a fresh
         *  enumeration. Same profile-filter pipeline applies; the
         *  search scope therefore honours Guest-mode whitelisting
         *  automatically. */
        fun snapshot(ctx: Context): List<PhoneApp> =
            sCachedApps ?: collectLaunchableAppsStatic(ctx.applicationContext).also { sCachedApps = it }

        /** Static enumeration — derives LauncherApps from [ctx] each
         *  call so warm-up (no Fragment instance) + onCreateView (has
         *  one) share the exact same code path.
         *
         *  Profile filter applied last: when LauncherProfile = Guest,
         *  the behavior map (build.json::ui.launcher_profiles[guest]
         *  .behavior) declares app_filter=whitelist + whitelist=
         *  [browser], and [LauncherProfiles.allowedPackagesFor]
         *  resolves "browser" to every installed browser. The Phone
         *  tab in Guest mode therefore lists exactly the device's
         *  browsers — system browser, Chrome, Firefox, etc. — and
         *  nothing else. Personal / Work pass through (filter
         *  returns null = "no filter"). */
        private fun collectLaunchableAppsStatic(ctx: Context): List<PhoneApp> {
            val me = Process.myUserHandle()
            val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return emptyList()
            val allowed = LauncherProfiles.allowedPackagesFor(ctx, LauncherProfilePrefs(ctx).profile)
            return runCatching {
                launcher.getActivityList(null, me).mapNotNull { info ->
                    val pkg = info.applicationInfo.packageName
                    if (pkg == ctx.packageName) return@mapNotNull null
                    if (allowed != null && pkg !in allowed) return@mapNotNull null
                    PhoneApp(
                        packageName       = pkg,
                        activityComponent = info.componentName,
                        label             = info.label?.toString() ?: pkg,
                        icon              = runCatching { info.getBadgedIcon(0) }.getOrNull(),
                        user              = me,
                        firstInstallTime  = runCatching {
                            ctx.packageManager.getPackageInfo(pkg, 0).firstInstallTime
                        }.getOrDefault(0L),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
