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
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The libs:onehand "circular-menu" — a two-level radial pie, sibling to the
 * edge-menu. Triggered by the Canopus home star: a ring of top-level nodes with
 * a centre arrow that follows the finger; the pointed node highlights and its
 * section's pages fan out further in that direction; releasing on a leaf (or a
 * childless node) navigates.
 *
 * App-agnostic: everything app-specific arrives via [Host]. Node data is
 * data-driven from build.json::onehand.circular_menu (baked into this lib's
 * own BuildConfig.ONEHAND_CONFIG_B64 — NOT app BuildConfig).
 */
object CircularMenu {

    data class Leaf(val label: String, val iconName: String, val target: String)
    data class Node(
        val label: String, val iconName: String, val target: String, val section: String?,
    )
    data class Config(
        val enabled: Boolean,
        val starGlyph: String,
        val starSizeSp: Int,
        val showOnSection: String,
        val radiusDp: Int,
        val subRadiusDp: Int,
        /** Star vertical anchor as a fraction of screen height BELOW centre
         *  (0.5 ≈ near the bottom). Menu opens as an upward half-moon from here. */
        val starBottomPct: Float,
        /** Extra touch padding (dp) around the star so it's easy to hit. */
        val starTapPadDp: Int,
        val nodes: List<Node>,
    )

    /** What the app must provide so the lib can render + act without touching R,
     *  the app's Sections, or its navigation directly. */
    interface Host {
        fun navigate(target: String)
        fun iconBitmap(name: String, sizePx: Int): Bitmap?
        /** Live pages of [section] → the fan-out leaves. Empty = childless node. */
        fun pagesFor(section: String): List<Leaf>
    }

    /** Decode this lib's baked onehand config and pull the circular_menu subtree.
     *  Returns a disabled empty config if absent/malformed (never throws). */
    fun config(): Config = runCatching {
        val raw = String(Base64.decode(BuildConfig.ONEHAND_CONFIG_B64, Base64.DEFAULT))
        val cm = JSONObject(raw).optJSONObject("circular_menu") ?: return DISABLED
        val star = cm.optJSONObject("star") ?: JSONObject()
        val arr = cm.optJSONArray("nodes")
        val nodes = ArrayList<Node>(arr?.length() ?: 0)
        for (i in 0 until (arr?.length() ?: 0)) {
            val n = arr!!.getJSONObject(i)
            nodes.add(Node(
                label = n.optString("label"),
                iconName = n.optString("icon"),
                target = n.optString("target"),
                section = n.optString("section").ifBlank { null }.takeIf { it != "null" },
            ))
        }
        Config(
            enabled = cm.optBoolean("enabled", false),
            starGlyph = star.optString("glyph", "✦"),
            starSizeSp = star.optInt("size_sp", 18),
            showOnSection = star.optString("show_on_section", "home"),
            radiusDp = cm.optInt("radius_dp", 120),
            subRadiusDp = cm.optInt("sub_radius_dp", 104),
            starBottomPct = star.optDouble("bottom_pct", 0.38).toFloat(),
            starTapPadDp = star.optInt("tap_pad_dp", 22),
            nodes = nodes,
        )
    }.getOrDefault(DISABLED)

    private val DISABLED = Config(false, "✦", 18, "home", 120, 104, 0.38f, 22, emptyList())

    /** Drives an open menu from an EXTERNAL touch stream — the star forwards its
     *  own gesture so press → drag → release is one continuous motion (the
     *  overlay itself never receives the in-flight gesture that started on the
     *  star). [x]/[y] are in the decor's coordinate space. */
    interface Session { fun feed(x: Float, y: Float, action: Int) }

    /** Present the menu centred at ([cx],[cy]) on [decor] (the activity content
     *  frame). Returns a [Session] to feed touches; self-removes on release. */
    fun open(decor: android.view.ViewGroup, cx: Float, cy: Float, host: Host): Session? {
        val cfg = config()
        if (!cfg.enabled || cfg.nodes.isEmpty()) return null
        val v = MenuView(decor.context, cfg, host, cx, cy) { decor.removeView(it) }
        decor.addView(v, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        return v
    }

    // ── the view ──────────────────────────────────────────────────────────────
    private class MenuView(
        ctx: Context,
        val cfg: Config,
        val host: Host,
        val cx: Float,
        val cy: Float,
        val dismiss: (View) -> Unit,
    ) : View(ctx), Session {

        private val dm = resources.displayMetrics
        private fun dp(v: Int) = v * dm.density
        private val ring = dp(cfg.radiusDp)
        private val subRing = ring + dp(cfg.subRadiusDp)
        private val dead = dp(28)            // finger inside this → nothing selected
        private val nodeR = dp(26)
        private val leafR = dp(22)
        private val iconPx = (dp(30)).toInt()

        private val scrim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 163, 122, 244); strokeWidth = dp(3); strokeCap = Paint.Cap.ROUND
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 24, 20, 40) }
        private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 124, 58, 237) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(11)
        }
        private val iconCache = HashMap<String, Bitmap?>()
        private fun icon(name: String) = iconCache.getOrPut(name) { host.iconBitmap(name, iconPx) }

        // RECENTERING CASCADE. Each Level = a ring of items around a center. Level 0
        // is centred on the star. Dragging PAST a node (r > commitR) makes that node
        // the new center with its children fanning up around it — the arrow origin
        // moves onto it. Pull the finger back into a center's dead-zone to pop up a
        // level and pick a different node. i=0 leftmost → i=n-1 rightmost.
        private data class Level(val cx: Float, val cy: Float, val items: List<Node>)
        private val stack = ArrayList<Level>().apply {
            add(Level(cx, cy, cfg.nodes))   // Node already carries label/icon/target/section
        }
        private val kidCache = HashMap<String, List<Node>>()
        private fun childrenOf(it: Node): List<Node> {
            val sec = it.section ?: return emptyList()
            return kidCache.getOrPut(it.target) {
                runCatching { host.pagesFor(sec) }.getOrDefault(emptyList())
                    .map { l -> Node(l.label, l.iconName, l.target, null) } // leaves = terminal
            }
        }

        // Upward half-moon: 180°(left) → 270°(up) → 360°(right).
        private fun arcAngle(i: Int, count: Int): Double =
            if (count <= 1) Math.toRadians(270.0) else Math.toRadians(180.0 + 180.0 * i / (count - 1))
        // Grow the ring so icons never overlap: on a semicircle adjacent spacing is
        // π·R/(count-1), which must stay ≥ one icon + gap. Levels with many children
        // (e.g. Configs) get a bigger radius; small levels keep the base radius.
        private val minGap = nodeR * 2 + dp(14)
        private fun ringFor(count: Int): Float =
            if (count <= 1) ring else maxOf(ring, (minGap * (count - 1) / Math.PI).toFloat())
        private fun itemPos(lv: Level, i: Int): Pair<Float, Float> {
            val a = arcAngle(i, lv.items.size); val r = ringFor(lv.items.size)
            return (lv.cx + r * cos(a)).toFloat() to (lv.cy + r * sin(a)).toFloat()
        }

        private fun commitR(count: Int) = ringFor(count) + dp(44) // drag past → descend
        private var fx = cx; private var fy = cy
        private var active = -1               // highlighted item in the top level

        private fun recompute() {
            val lv = stack.last()
            val dx = fx - lv.cx; val dy = fy - lv.cy; val r = hypot(dx, dy)
            if (r < dead) {                   // finger in the center dead-zone
                if (stack.size > 1) stack.removeAt(stack.size - 1) // pop up a level
                active = -1; return
            }
            val fa = atan2(dy, dx).toDouble()
            var best = 0; var bestD = Double.MAX_VALUE
            for (i in lv.items.indices) {
                val d = Math.abs(angDiff(fa, arcAngle(i, lv.items.size))); if (d < bestD) { bestD = d; best = i }
            }
            active = best
            val sel = lv.items[best]
            if (r > commitR(lv.items.size) && sel.section != null) {   // drag past node → enter it
                val kids = childrenOf(sel)
                if (kids.isNotEmpty()) {
                    val (nx, ny) = itemPos(lv, best)
                    stack.add(Level(nx, ny, kids)); active = -1  // node becomes new center
                }
            }
        }

        private fun angDiff(a: Double, b: Double): Double {
            var d = a - b; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return d
        }

        override fun feed(x: Float, y: Float, action: Int) {
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    fx = x; fy = y; recompute(); invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val lv = stack.last(); val sel = active
                    dismiss(this)
                    if (sel in lv.items.indices) host.navigate(lv.items[sel].target)
                }
                MotionEvent.ACTION_CANCEL -> dismiss(this)
            }
        }

        // If the finger DOES land on the overlay directly, handle it too.
        override fun onTouchEvent(e: MotionEvent): Boolean { feed(e.x, e.y, e.actionMasked); return true }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            val lv = stack.last()
            // breadcrumb: a faint disc at each ancestor center (that's where "back" is)
            for (i in 0 until stack.size - 1) c.drawCircle(stack[i].cx, stack[i].cy, dp(8), disc)
            // arrow from the current center toward the finger (clamped to the ring)
            val dx = fx - lv.cx; val dy = fy - lv.cy; val len = hypot(dx, dy)
            if (len > dead) {
                val t = (ringFor(lv.items.size) - nodeR) / len
                c.drawLine(lv.cx, lv.cy, lv.cx + dx * t, lv.cy + dy * t, arrow)
            }
            // the current level's items
            lv.items.forEachIndexed { i, nd ->
                val (nx, ny) = itemPos(lv, i)
                c.drawCircle(nx, ny, nodeR, if (i == active) hot else disc)
                icon(nd.iconName)?.let { c.drawBitmap(it, nx - it.width / 2f, ny - it.height / 2f, null) }
                c.drawText(nd.label, nx, ny + nodeR + dp(13), label)
            }
        }
    }
}
