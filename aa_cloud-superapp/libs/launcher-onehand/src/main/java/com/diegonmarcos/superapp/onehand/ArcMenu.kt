package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ARC-MENU — libs:launcher-onehand bottom-arc menu. Triggered by TWO home stars, both
 * lib-side (CanopusStar.kt / CentaurusStar.kt, this package):
 *   - Canopus  : items = children of build.json::onehand.arc_menu.section
 *     (default "config") — its Host is app-supplied (built in MainActivity
 *     from SectionPages), since the CONTENT is app-specific even though the
 *     star widget itself is not.
 *   - Centauri : items = last 9 recently-opened Android apps — its Host
 *     needs no app callback at all (pure platform API) and ignores the
 *     `section` param entirely.
 * Both share this exact same visual (radius, disc style, upward fan); only
 * the Host's itemsFor/iconBitmap/navigate implementation differs.
 *
 * Opens a fixed half-moon arc anchored at the bottom-centre of the screen.
 * Single level — no drill-down.
 *
 * Distinct from the other two onehand menus:
 *   - edge-menu  (GesturePreviewView): swipe-from-edge service overlay, 3 items
 *   - circular-menu (CircularMenu): full-screen scrim, multi-level, Sirius star
 *
 * Config lives in build.json::onehand.arc_menu → baked into ONEHAND_CONFIG_B64.
 */
object ArcMenu {

    data class Item(val label: String, val iconName: String, val target: String)

    data class Config(val enabled: Boolean, val section: String, val radiusDp: Int)

    /** App-side bridge: supply items + navigation without the lib touching R. */
    interface Host {
        fun navigate(target: String)
        fun iconBitmap(name: String, sizePx: Int): Bitmap?
        fun itemsFor(section: String): List<Item>
    }

    fun config(): Config = runCatching {
        val raw = String(Base64.decode(BuildConfig.ONEHAND_CONFIG_B64, Base64.DEFAULT))
        val am = JSONObject(raw).optJSONObject("arc_menu") ?: return DISABLED
        Config(
            enabled  = am.optBoolean("enabled", false),
            section  = am.optString("section", "config"),
            radiusDp = am.optInt("radius_dp", 140),
        )
    }.getOrDefault(DISABLED)

    private val DISABLED = Config(false, "config", 140)

    /** Inner-arc test. An entry whose target fires and returns isn't a page, so
     *  it belongs on the actions arc. Derived from the target grammar rather
     *  than a per-item flag — build.json needs no edit to opt in. */
    private fun isAction(target: String): Boolean =
        target.startsWith("action:") || target.startsWith("extapp:")

    /** Touch stream forwarded from the Canopus star (press → drag → release). */
    interface Session { fun feed(x: Float, y: Float, action: Int) }

    /** Attach a full-screen arc-menu to [decor]; returns a [Session] to forward
     *  the star's in-flight gesture. Self-removes on ACTION_UP / ACTION_CANCEL. */
    /** [cx]/[cy]: star's position in decor coordinates — the arc fans upward from there. */
    fun open(decor: ViewGroup, cx: Float, cy: Float, host: Host): Session? {
        val cfg   = config()
        if (!cfg.enabled) return null
        // Same two-arc content the Sirius menu builds on descent: the section's
        // pages from the host, PLUS the actions declared for that section in
        // build.json::onehand.circular_menu.nodes[].actions (KDE Connect,
        // Animations, Copy Info, Update All ...). Declared once, shown by both
        // stars — the bottom star is the one that opens Configs, so this is
        // where they have to appear.
        val actions = CircularMenu.config().nodes
            .firstOrNull { it.childKey == cfg.section }
            ?.actions.orEmpty()
            .map { Item(it.label, it.iconName, it.target) }
        val items = host.itemsFor(cfg.section) + actions
        if (items.isEmpty()) return null
        val v = ArcView(decor.context, cfg.radiusDp, cx, cy, items, host) { decor.removeView(it) }
        decor.addView(v, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        return v
    }

    // ── view ──────────────────────────────────────────────────────────────────
    private class ArcView(
        ctx: Context,
        radiusDp: Int,
        private val cx: Float,   // star's X in decor coords
        private val cy: Float,   // star's Y in decor coords
        private val items: List<Item>,
        private val host: Host,
        private val dismiss: (View) -> Unit,
    ) : View(ctx), Session {

        private val dm  = resources.displayMetrics
        private fun dp(v: Float) = v * dm.density

        private val ring   = dp(radiusDp.toFloat())
        private val nodeR  = dp(26f)
        private val iconPx = dp(22f).toInt()  // icon above the label
        private val dead   = dp(20f)
        // Arc geometry. minGap = how much arc length one icon claims; margin
        // keeps a whole icon on screen; topInset clears status bar + toolbar;
        // ringGap separates the two arcs by at least a whole icon so they can
        // never overlap.
        private val minGap   = nodeR * 2 + dp(14f)
        private val margin   = nodeR + dp(10f)
        private val topInset = dp(72f)
        private val ringGap  = nodeR * 2 + dp(6f)

        private val scrim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val disc  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 24, 20, 40) }
        private val hot   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 124, 58, 237) }
        private val lbl   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            textSize = dp(9f); isFakeBoldText = true  // smaller font
        }

        private val iconCache = HashMap<String, Bitmap?>()
        private fun icon(name: String) = iconCache.getOrPut(name) { host.iconBitmap(name, iconPx) }

        /** One icon's placement: centre, plus the radius to draw it at. */
        private data class Slot(val x: Float, val y: Float, val nr: Float)

        // LAYOUT — TWO concentric upward half-moons centred on the star. The
        // OUTER arc carries the section's pages, the INNER one its actions
        // (target grammar decides — see [isAction]), each spread on its own
        // half-circle so adding actions never squeezes the pages. slots[i]
        // maps 1:1 to items[i].
        //
        // Was ONE arc at a fixed 200dp radius with items at frac 0 and 1 landing
        // exactly ON the horizontal — cx +/- (200 + 26)dp, off both edges of any
        // normal phone — and neighbour spacing shrinking without bound as the
        // item count grew. Radii are now clamped to the viewport and derived from
        // the count, and both ends are inset by half a step.
        private val slots: List<Slot> = run {
            // Largest radius keeping a whole icon on screen, in the only
            // directions a half-moon reaches: left, up, right.
            val cap = (minOf(cx, dm.widthPixels - cx, cy - topInset) - margin)
                .coerceAtLeast(dead + nodeR)
            val outer = ArrayList<Int>(); val inner = ArrayList<Int>()
            items.forEachIndexed { i, item -> (if (isAction(item.target)) inner else outer).add(i) }
            val out = arrayOfNulls<Slot>(items.size)
            // A half-moon's arc length is PI*r, so holding `count` icons at minGap
            // needs r = count*minGap/PI. Grows with the count, never past the clamp.
            fun radiusFor(count: Int): Float =
                minOf(cap, maxOf(ring, count * minGap / Math.PI.toFloat()))
            fun place(idx: List<Int>, r: Float) {
                // Icon radius = half the neighbour spacing (discs at most touch),
                // capped at nodeR and measured PER ARC — a crowded inner arc must
                // not shrink the roomy outer one.
                val nr = if (idx.size < 2) nodeR else
                    ((2.0 * r * sin(Math.PI / (2 * idx.size))).toFloat() / 2f - dp(2f))
                        .coerceIn(dp(12f), nodeR)
                idx.forEachIndexed { k, i ->
                    // (k + 0.5) insets both ends by half a step, so no icon sits ON
                    // the horizontal — exactly where the screen edge is nearest.
                    val ang = Math.PI * (1.0 - (k + 0.5) / idx.size)
                    out[i] = Slot((cx + r * cos(ang)).toFloat(), (cy - r * sin(ang)).toFloat(), nr)
                }
            }
            when {
                outer.isEmpty() -> place(inner, radiusFor(inner.size))
                inner.isEmpty() -> place(outer, radiusFor(outer.size))
                else -> {
                    val rOut = radiusFor(outer.size)
                    place(outer, rOut)
                    place(inner, (rOut - ringGap).coerceAtLeast(dead + nodeR))
                }
            }
            out.map { it!! }
        }

        private var fx = cx; private var fy = cy
        private var active = -1

        override fun feed(x: Float, y: Float, action: Int) {
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    fx = x; fy = y
                    active = if (hypot(fx - cx, fy - cy) < dead) -1 else
                        slots.indices.minByOrNull { i ->
                            hypot(fx - slots[i].x, fy - slots[i].y)
                        } ?: -1
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val sel = active; dismiss(this)
                    if (sel in items.indices) host.navigate(items[sel].target)
                }
                MotionEvent.ACTION_CANCEL -> dismiss(this)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            feed(e.x, e.y, e.actionMasked); return true
        }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            slots.forEachIndexed { i, s ->
                // Background disc
                c.drawCircle(s.x, s.y, s.nr, if (i == active) hot else disc)
                val bmp = icon(items[i].iconName)
                if (bmp != null) {
                    // Icon in upper half of disc, label below inside disc
                    val h = minOf(iconPx / 2f, s.nr * 0.62f)
                    val iconCy = s.y - dp(5f)
                    c.drawBitmap(bmp, null, RectF(s.x - h, iconCy - h, s.x + h, iconCy + h), null)
                    c.drawText(items[i].label, s.x, s.y + s.nr - dp(2f), lbl)
                } else {
                    // No icon: just centered label
                    c.drawText(items[i].label, s.x, s.y + dp(4f), lbl)
                }
            }
        }
    }
}
