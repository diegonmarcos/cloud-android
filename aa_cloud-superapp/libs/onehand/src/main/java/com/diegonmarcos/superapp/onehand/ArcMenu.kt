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
 * ARC-MENU — libs:onehand bottom-arc menu. Triggered by TWO home stars:
 *   - Canopus  (app/launcher): items = children of build.json::onehand
 *     .arc_menu.section (default "config").
 *   - Centauri (libs:onehand, CentaurusStar): items = last 9 recently-opened
 *     Android apps — its Host ignores the `section` param entirely.
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

    /** Touch stream forwarded from the Canopus star (press → drag → release). */
    interface Session { fun feed(x: Float, y: Float, action: Int) }

    /** Attach a full-screen arc-menu to [decor]; returns a [Session] to forward
     *  the star's in-flight gesture. Self-removes on ACTION_UP / ACTION_CANCEL. */
    /** [cx]/[cy]: star's position in decor coordinates — the arc fans upward from there. */
    fun open(decor: ViewGroup, cx: Float, cy: Float, host: Host): Session? {
        val cfg   = config()
        if (!cfg.enabled) return null
        val items = host.itemsFor(cfg.section)
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

        private val scrim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val disc  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 24, 20, 40) }
        private val hot   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 124, 58, 237) }
        private val lbl   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            textSize = dp(9f); isFakeBoldText = true  // smaller font
        }

        private val iconCache = HashMap<String, Bitmap?>()
        private fun icon(name: String) = iconCache.getOrPut(name) { host.iconBitmap(name, iconPx) }

        // Slots computed once: upper semicircle fanning upward from the star centre.
        private val slots: List<Pair<Float, Float>> = run {
            val n = items.size
            List(n) { i ->
                val frac = if (n <= 1) 0.5 else i.toDouble() / (n - 1)
                // Sweep PI (left) → PI/2 (up) → 0 (right); negate sin for screen Y (Y↓)
                val ang = Math.PI * (1.0 - frac)
                Pair(
                    (cx + ring * cos(ang)).toFloat(),
                    (cy - ring * sin(ang)).toFloat(),
                )
            }
        }

        private var fx = cx; private var fy = cy
        private var active = -1

        override fun feed(x: Float, y: Float, action: Int) {
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    fx = x; fy = y
                    active = if (hypot(fx - cx, fy - cy) < dead) -1 else
                        slots.indices.minByOrNull { i ->
                            val (px, py) = slots[i]; hypot(fx - px, fy - py)
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
            slots.forEachIndexed { i, (px, py) ->
                // Background disc
                c.drawCircle(px, py, nodeR, if (i == active) hot else disc)
                val bmp = icon(items[i].iconName)
                if (bmp != null) {
                    // Icon in upper half of disc, label below inside disc
                    val h = iconPx / 2f
                    val iconCy = py - dp(5f)
                    c.drawBitmap(bmp, null, RectF(px - h, iconCy - h, px + h, iconCy + h), null)
                    c.drawText(items[i].label, px, py + nodeR - dp(2f), lbl)
                } else {
                    // No icon: just centered label
                    c.drawText(items[i].label, px, py + dp(4f), lbl)
                }
            }
        }
    }
}
