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

        // LAYOUT. Items sit on an upward half-moon (180°=left → 270°=up → 360°=right)
        // around the level's center. The radius is clamped to what fits ON SCREEN;
        // when a level has more children than fit on one arc at readable spacing
        // (e.g. Configs' 12), the overflow wraps onto a second, larger concentric
        // ring — nothing is ever pushed off-screen. slots[i] maps 1:1 to items[i].
        // Target center-to-center spacing that drives the radius. Must be COMFORTABLY
        // bigger than an icon (2·nodeR) or `ideal` computes smaller than the base ring
        // and max(ring, …) freezes the radius at the base — the radius then never grows
        // with item count (the bug that made every "bigger radius" attempt a no-op).
        private val minGap = nodeR * 2 + dp(40)   // 2·26 + 40 = 92dp
        private val margin = nodeR + dp(8)        // keep whole icon inside the screen

        private data class Slot(val r: Float, val a: Double)

        private fun onScreen(cx: Float, cy: Float, r: Float, ang: Double): Boolean {
            val x = cx + r * cos(ang); val y = cy + r * sin(ang)
            return x >= margin && x <= dm.widthPixels - margin &&
                   y >= margin && y <= dm.heightPixels - margin
        }
        // From straight-up (270°) walk toward 180° (dir=-1) or 360° (dir=+1) until
        // the arc leaves the screen; return that boundary angle. The on-screen arc
        // is contiguous around 270° because we cap the radius so the top stays visible.
        private fun edge(cx: Float, cy: Float, r: Float, dir: Int): Double {
            val stop = Math.toRadians(if (dir < 0) 180.0 else 360.0)
            val stepA = Math.toRadians(1.5) * dir
            var a = Math.toRadians(270.0)
            while (if (dir < 0) a > stop else a < stop) {
                val next = a + stepA
                if (!onScreen(cx, cy, r, next)) break
                a = next
            }
            return a
        }

        // A bigger circle bulges further up into the screen, giving MORE on-screen
        // arc to spread icons over — but only until the semicircle just fits; past
        // that the sides clip it and the visible arc SHRINKS. So the useful radius is
        // capped where the full upper half still fits (min of half-width and the
        // height above the center). The radius grows with count up to that cap; the
        // visible span [lo,hi] is measured so icons never fall off the edges.
        private fun isRoot(lv: Level) = stack.isNotEmpty() && stack[0] === lv
        private fun layout(lv: Level): List<Slot> {
            val n = lv.items.size
            if (n <= 1) return listOf(Slot(ring, Math.toRadians(270.0)))
            if (isRoot(lv)) {
                // Level 0: upward half-moon from the bottom star. Radius grows with
                // count up to where the semicircle still fits (half-width / height
                // above center); icons spread across the on-screen span.
                val rCap = maxOf(ring, minOf(dm.widthPixels / 2f - margin, lv.cy - margin))
                val ideal = (minGap * (n - 1) / Math.PI).toFloat()
                val r = maxOf(ring, minOf(ideal, rCap))
                val lo = edge(lv.cx, lv.cy, r, -1); val hi = edge(lv.cx, lv.cy, r, +1)
                return List(n) { i -> Slot(r, lo + (hi - lo) * i / (n - 1)) }
            }
            // Deeper levels re-center ON the node — often near the top with no upward
            // room. So they open as a FULL circle around the node: 2× the arc length
            // for the same radius, so crowded levels (Configs' 12) get a much bigger
            // radius AND full-size icons. Radius clamps to the nearest screen edge so
            // the whole circle stays visible.
            val edgeDist = minOf(lv.cx, dm.widthPixels - lv.cx, lv.cy, dm.heightPixels - lv.cy) - margin
            val rCap = maxOf(ring, edgeDist)
            val ideal = (n * minGap / (2 * Math.PI)).toFloat()   // full-circle spacing
            val r = maxOf(ring, minOf(ideal, rCap))
            return List(n) { i -> Slot(r, 2 * Math.PI * i / n - Math.PI / 2) } // start at top
        }
        private fun slotPos(lv: Level, s: Slot): Pair<Float, Float> =
            (lv.cx + s.r * cos(s.a)).toFloat() to (lv.cy + s.r * sin(s.a)).toFloat()
        private fun outerR(lv: Level): Float = layout(lv).maxOf { it.r }
        // Icon radius = half the neighbour spacing (so circles just touch at most),
        // capped at the full nodeR. Shrinks only when the arc is too packed.
        private fun nodeRadiusFor(lv: Level, slots: List<Slot>): Float {
            if (slots.size < 2) return nodeR
            val (x0, y0) = slotPos(lv, slots[0]); val (x1, y1) = slotPos(lv, slots[1])
            return (hypot(x1 - x0, y1 - y0) / 2f - dp(3)).coerceIn(dp(12), nodeR)
        }

        private var fx = cx; private var fy = cy
        private var active = -1               // highlighted item in the top level

        private fun recompute() {
            val lv = stack.last()
            val dx = fx - lv.cx; val dy = fy - lv.cy; val r = hypot(dx, dy)
            if (r < dead) {                   // finger in the center dead-zone
                if (stack.size > 1) stack.removeAt(stack.size - 1) // pop up a level
                active = -1; return
            }
            val slots = layout(lv)
            // nearest item by straight-line distance — correct across concentric rings
            var best = 0; var bestD = Float.MAX_VALUE
            slots.forEachIndexed { i, s ->
                val (px, py) = slotPos(lv, s)
                val d = hypot(fx - px, fy - py); if (d < bestD) { bestD = d; best = i }
            }
            active = best
            val sel = lv.items[best]
            // descend: drag past the outermost ring into a node with children. Leaf
            // levels carry no sections, so packed rings never trigger this.
            if (r > outerR(lv) + dp(44) && sel.section != null) {
                val kids = childrenOf(sel)
                if (kids.isNotEmpty()) {
                    val (nx, ny) = slotPos(lv, slots[best])
                    stack.add(Level(nx, ny, kids)); active = -1  // node becomes new center
                }
            }
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
            val slots = layout(lv)
            // arrow from the current center toward the finger (clamped to inner ring)
            val dx = fx - lv.cx; val dy = fy - lv.cy; val len = hypot(dx, dy)
            if (len > dead) {
                val t = (slots.minOf { it.r } - nodeR) / len
                c.drawLine(lv.cx, lv.cy, lv.cx + dx * t, lv.cy + dy * t, arrow)
            }
            // Radius is already at the screen-limited max; when the visible arc still
            // can't fit full-size icons at this count, shrink them to the neighbour
            // spacing so they never overlap (few-item levels keep full size).
            val nr = nodeRadiusFor(lv, slots)
            slots.forEachIndexed { i, s ->
                val (nx, ny) = slotPos(lv, s)
                c.drawCircle(nx, ny, nr, if (i == active) hot else disc)
                val nd = lv.items[i]
                icon(nd.iconName)?.let {
                    // native icon size (iconPx), scaled down only when the node shrank
                    val h = iconPx / 2f * (nr / nodeR)
                    c.drawBitmap(it, null, RectF(nx - h, ny - h, nx + h, ny + h), null)
                }
                if (nr >= dp(19)) c.drawText(nd.label, nx, ny + nr + dp(13), label)
            }
        }
    }
}
