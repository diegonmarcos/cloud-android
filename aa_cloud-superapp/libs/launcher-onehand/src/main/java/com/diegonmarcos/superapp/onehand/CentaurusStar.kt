package com.diegonmarcos.superapp.onehand

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.diegonmarcos.superapp.datamanager.AppUsageProvider

/**
 * Home-screen Centauri star — the THIRD star, between Sirius and Canopus.
 * Opens the SAME [ArcMenu] Canopus uses (identical visual: radius, disc
 * style, upward fan) but with a different content source: the last 9
 * recently-opened Android apps (icon + tap-to-launch), not a build.json
 * section.
 *
 * Lives HERE in libs:launcher-onehand — unlike Sirius/Canopus (app/launcher package),
 * Centauri needs no app-side callback at all. Its content (recent packages,
 * app icons, launching an app) is pure platform API
 * (LauncherApps/AppUsageProvider/PackageManager), so it is fully
 * self-contained and app-agnostic, same as ArcMenu/CircularMenu themselves.
 *
 * Position: exactly midway between Sirius (screen centre, no runtime
 * translation) and Canopus (translated up to just above the bottom-nav
 * island). Computed by taking Canopus's own anchor formula and halving the
 * offset — the segment's midpoint.
 */
class CentaurusStar(
    private val activity: Activity,
    private val star: TextView,
    private val island: View?,
) {

    private val cfg get() = CircularMenu.config()   // same glyph/size source as Sirius+Canopus
    private var session: ArcMenu.Session? = null
    private var islandLayoutListener: View.OnLayoutChangeListener? = null

    /**
     * Recompute translationY to the midpoint between Sirius and Canopus's
     * anchor, and apply it — but only once both views actually have a
     * measured height.
     *
     * IMPORTANT: `getLocationInWindow` reports the CURRENT on-screen
     * position, which already includes any translationY from a previous
     * call. If `canopusOffset` were derived from that directly, a second
     * call would read back its own output and compute ~0 (star snaps to
     * its untranslated position), and a third call would move it again —
     * an oscillation. So we first subtract the star's own current
     * translationY to recover `base`, its UNTRANSLATED layout position,
     * and compute canopusOffset from that. This makes the whole function
     * idempotent: calling it any number of times in a row yields the same
     * translationY. Do not "simplify" this away.
     */
    private fun tryAnchor() {
        val isl = island ?: return
        if (isl.height <= 0 || star.height <= 0) return
        val s = IntArray(2); star.getLocationInWindow(s)
        val n = IntArray(2); isl.getLocationInWindow(n)
        val gap = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics)
        val base = s[1] - star.translationY
        val canopusOffset = (n[1] - gap - (base + star.height))
        star.translationY = canopusOffset * 0.5f
    }

    private val host = object : ArcMenu.Host {
        override fun navigate(target: String) {
            if (!target.startsWith("launch:")) return
            val pkg = target.removePrefix("launch:")
            runCatching {
                activity.packageManager.getLaunchIntentForPackage(pkg)?.let { activity.startActivity(it) }
            }
        }

        override fun iconBitmap(name: String, sizePx: Int): Bitmap? = runCatching {
            val d = activity.packageManager.getApplicationIcon(name)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, sizePx, sizePx); d.draw(Canvas(bmp))
            bmp
        }.getOrNull()

        // `section` is ignored — Centauri always shows recent apps, regardless
        // of build.json::onehand.arc_menu.section (that field is Canopus's).
        override fun itemsFor(section: String): List<ArcMenu.Item> =
            RecentAppsSource.last9(activity).map { (pkg, label) ->
                ArcMenu.Item(label, pkg, "launch:$pkg")
            }
    }

    /** Wire glyph/size/position/tap once; call [update] after for initial state. */
    fun setup() {
        val c = cfg
        if (!c.enabled) { star.visibility = View.GONE; return }
        star.text = c.starGlyph
        // Between Canopus (starSizeSp) and Sirius (starSizeSp + 4).
        star.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.starSizeSp.toFloat() + 2f)
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, c.starTapPadDp.toFloat(), activity.resources.displayMetrics).toInt()
        star.setPadding(pad, pad, pad, pad)
        // Midpoint between Sirius (translationY = 0, screen centre) and
        // Canopus's own anchor (translationY = just above the bottom-nav
        // island) — i.e. HALF of the same island-relative offset Canopus
        // computes for itself.
        if (island != null) {
            // Re-run the anchor on every REAL layout change of either view —
            // first measurement (no race with a "not measured yet" fallback
            // that lands somewhere else), window-insets settling, rotation,
            // gesture-nav vs 3-button nav, multi-window resize, and the
            // island showing/hiding per section. Safe to call repeatedly
            // because tryAnchor() is idempotent (see its kdoc).
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tryAnchor() }
            islandLayoutListener = listener
            island.addOnLayoutChangeListener(listener)
            star.addOnLayoutChangeListener(listener)
            tryAnchor() // covers the warm-start case where both are already laid out
            // Detach when the star leaves the window so we don't leak the Activity.
            star.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    island.removeOnLayoutChangeListener(listener)
                    star.removeOnLayoutChangeListener(listener)
                    star.removeOnAttachStateChangeListener(this)
                    islandLayoutListener = null
                }
            })
        } else {
            // Genuine fallback: no island at all to anchor against.
            star.translationY = star.rootView.height * (c.starBottomPct * 0.5f)
        }
        // Forward press→drag→release to the SAME ArcMenu as Canopus.
        star.setOnTouchListener { _, e ->
            val decor = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: return@setOnTouchListener false
            val s = IntArray(2); star.getLocationInWindow(s)
            val d = IntArray(2); decor.getLocationInWindow(d)
            val x = s[0] - d[0] + e.x
            val y = s[1] - d[1] + e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    session = ArcMenu.open(decor, x, y, host)
                    session?.feed(x, y, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_MOVE -> session?.feed(x, y, MotionEvent.ACTION_MOVE)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    session?.feed(x, y, e.actionMasked); session = null
                    star.performClick()
                }
            }
            true
        }
    }

    /** Show only on the `home` section (same as Sirius/Canopus). */
    fun update(currentSection: String) {
        val c = cfg
        star.visibility = if (c.enabled && currentSection == c.showOnSection) View.VISIBLE else View.GONE
    }
}

/** Pure-ish data source: last 9 recently-opened, still-installed, launchable
 *  apps (own package excluded). Filtering logic is factored into [pickRecent]
 *  so it's JVM-unit-testable without LauncherApps/PackageManager. */
private object RecentAppsSource {

    fun last9(ctx: Context): List<Pair<String, String>> {
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val byPkg = runCatching {
            launcher.getActivityList(null, Process.myUserHandle()).groupBy { it.applicationInfo.packageName }
        }.getOrDefault(emptyMap())
        val recentPkgs = AppUsageProvider.recentUsed(ctx)
        val picked = pickRecent(recentPkgs, byPkg.keys, ctx.packageName, limit = 9)
        return picked.mapNotNull { pkg ->
            val info = byPkg[pkg]?.firstOrNull() ?: return@mapNotNull null
            pkg to info.label.toString()
        }
    }
}

/** Ranked package list -> first [limit] distinct entries that are launchable
 *  and not [self]. Package-name-only logic, no Android framework calls —
 *  covered by CentaurusStarFilterTest.kt (JVM, no device). */
fun pickRecent(ranked: List<String>, launchable: Set<String>, self: String, limit: Int): List<String> =
    ranked.asSequence()
        .filter { it != self && it in launchable }
        .distinct()
        .take(limit)
        .toList()
