package com.diegonmarcos.cloudnav.configs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.maps.MapsStopsFragment
import java.io.File

/**
 * Cache tab: reports real on-disk size for the app's actual cache
 * mechanisms and offers a real clear action for each, rather than one
 * vague "clear cache" button. There are exactly three, per what this app
 * actually uses (verified against the code, not assumed):
 *
 *  1. MapLibre Native's own offline/ambient tile cache (`mbgl-offline.db*`
 *     in [Context.getFilesDir]) -- used by the main native map
 *     ([com.diegonmarcos.cloudnav.maps.MapsMapFragment]).
 *  2. Android WebView's HTTP cache -- shared by every WebView-hosted
 *     screen ([com.diegonmarcos.cloudnav.TerrainActivity],
 *     [com.diegonmarcos.cloudnav.SpaceViewActivity]). Android does not
 *     expose a way to separate this by which page/style filled it (it's
 *     one per-app Chromium cache, not per-Activity), so this is reported
 *     and cleared as one bucket -- not faked as a per-map breakdown the
 *     platform doesn't actually provide.
 *  3. The app's general cache directory ([Context.getCacheDir]), which
 *     the WebView cache technically lives inside of on current Android
 *     versions, plus anything else that lands there. Reported separately
 *     from (2) since it also can be cleared as a whole via the OS-level
 *     cache-dir convention (same directory Android itself may purge under
 *     storage pressure).
 */
class CacheConfigFragment : Fragment() {

    private lateinit var mapLibreRow: TextView
    private lateinit var webViewRow: TextView
    private lateinit var appCacheRow: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        root.addView(header(ctx, "Cache"))
        root.addView(caption(ctx, "Real on-disk sizes for this app's actual cache mechanisms — not an estimate."))

        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(header(ctx, "Native map (MapLibre)"))
        root.addView(caption(ctx, "Vector/raster tiles cached by the main map screen."))
        mapLibreRow = valueRow(ctx, root)
        root.addView(clearButton(ctx, "Clear native map cache") {
            val n = clearMapLibreCache(ctx)
            Toast.makeText(ctx, if (n > 0) "Cleared" else "Already empty", Toast.LENGTH_SHORT).show()
            refresh(ctx)
        })

        root.addView(spacer(ctx, dp(ctx, 20)))
        root.addView(header(ctx, "WebView maps (Terrain, Galaxy)"))
        root.addView(caption(ctx, "Terrain's map/satellite tiles are fetched over the network and land here; the Galaxy view is fully bundled and barely touches this. Android caches this per-app, not per-screen, so it can't be split further than this."))
        webViewRow = valueRow(ctx, root)
        root.addView(clearButton(ctx, "Clear WebView cache") {
            clearWebViewCache(ctx)
            Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
            refresh(ctx)
        })

        root.addView(spacer(ctx, dp(ctx, 20)))
        root.addView(header(ctx, "App cache directory"))
        root.addView(caption(ctx, "Everything under this app's general cache dir (includes the WebView cache above, plus anything else cached there). Same directory the OS itself may purge under low storage."))
        appCacheRow = valueRow(ctx, root)
        root.addView(clearButton(ctx, "Clear app cache directory") {
            val n = dirSize(ctx.cacheDir)
            ctx.cacheDir.deleteRecursively()
            Toast.makeText(ctx, if (n > 0) "Cleared" else "Already empty", Toast.LENGTH_SHORT).show()
            refresh(ctx)
        })

        refresh(ctx)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        if (::mapLibreRow.isInitialized) refresh(requireContext())
    }

    private fun refresh(ctx: Context) {
        mapLibreRow.text = sizeStr(mapLibreCacheSize(ctx))
        webViewRow.text = sizeStr(dirSize(File(ctx.applicationInfo.dataDir, "app_webview")))
        appCacheRow.text = sizeStr(dirSize(ctx.cacheDir))
    }

    // ── real cache mechanisms ────────────────────────────────────────
    // MapLibre Native's offline/ambient cache DB: verified against the
    // pinned org.maplibre.gl:android-sdk:11.7.0's own documented default
    // (mbgl-offline.db in Context.getFilesDir(), plus SQLite WAL/SHM
    // sidecar files while a connection is open).
    private fun mapLibreCacheSize(ctx: Context): Long =
        (ctx.filesDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("mbgl-offline.db") }
            .sumOf { it.length() }

    private fun clearMapLibreCache(ctx: Context): Long {
        val before = mapLibreCacheSize(ctx)
        (ctx.filesDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("mbgl-offline.db") }
            .forEach { it.delete() }
        return before
    }

    // WebView.clearCache(true) is the real, documented Android API for
    // this — it clears the cache for the whole app's WebViews even
    // without an active on-screen instance (confirmed: it's a per-app
    // Chromium cache, not per-WebView-instance), so a throwaway instance
    // works.
    private fun clearWebViewCache(ctx: Context) {
        val wv = WebView(ctx)
        wv.clearCache(true)
        wv.destroy()
    }

    private fun dirSize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun sizeStr(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ── layout helpers, same visual style as MapsConfigFragment ───────
    private fun valueRow(ctx: Context, root: LinearLayout): TextView {
        val tv = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            setPadding(0, 0, 0, dp(ctx, 8))
        }
        root.addView(tv)
        return tv
    }

    private fun clearButton(ctx: Context, text: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setTextColor(MapsStopsFragment.COL_PRIMARY)
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    private fun caption(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        setTextColor(MapsStopsFragment.COL_SECONDARY)
        setPadding(0, 0, 0, dp(ctx, 12))
    }

    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
