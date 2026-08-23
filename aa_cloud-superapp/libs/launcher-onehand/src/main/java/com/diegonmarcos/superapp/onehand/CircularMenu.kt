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
 * The libs:launcher-onehand "circular-menu" — a two-level radial pie, sibling to the
 * edge-menu. Triggered by the SIRIUS home star (SiriusStar.kt, this package —
 * corrected: an earlier version of this comment said Canopus, which actually
 * triggers ArcMenu instead): a ring of top-level nodes with a centre arrow
 * that follows the finger; the pointed node highlights and its section's
 * pages fan out further in that direction; releasing on a leaf (or a
 * childless node) navigates.
 *
 * App-agnostic: everything app-specific arrives via [Host]. Node data is
 * data-driven from build.json::onehand.circular_menu (baked into this lib's
 * own BuildConfig.ONEHAND_CONFIG_B64 — NOT app BuildConfig).
 */
object CircularMenu {

    /** [action] = render on the inner ring instead of the outer one. The host
     *  decides; the lib only reads the flag when laying the level out. */
    data class Child(
        val label: String, val iconName: String, val target: String, val childKey: String?,
        val action: Boolean = false,
    )
    data class Node(
        val label: String, val iconName: String, val target: String, val childKey: String?,
        val action: Boolean = false,
        /** Extra inner-ring entries declared in build.json for this node, merged
         *  in on descent. Lets the radial menu carry actions that are NOT pages
         *  of the section, so nothing else in the UI grows a phantom tile. */
        val actions: List<Node> = emptyList(),
    )
    data class Config(
        val enabled: Boolean,
        val starGlyph: String,
        val starSizeSp: Int,
        val showOnSection: String,
        /** Radius (dp) of the outer ring — the level's pages/sections. */
        val radiusDp: Int,
        /** Radius (dp) of the inner ring — the level's actions. */
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
        /** Children of [key] (a section id or a nested key like "suite/phone").
         *  A child whose childKey is non-null is itself expandable — descending
         *  re-queries childrenOf(childKey); a null childKey is a terminal leaf
         *  whose target is navigated on release. Empty = childless node. */
        fun childrenOf(key: String): List<Child>
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
            val actArr = n.optJSONArray("actions")
            val acts = ArrayList<Node>(actArr?.length() ?: 0)
            for (j in 0 until (actArr?.length() ?: 0)) {
                val a = actArr!!.getJSONObject(j)
                acts.add(Node(
                    label = a.optString("label"),
                    iconName = a.optString("icon"),
                    target = a.optString("target"),
                    childKey = null,
                    action = true,
                ))
            }
            nodes.add(Node(
                label = n.optString("label"),
                iconName = n.optString("icon"),
                target = n.optString("target"),
                childKey = n.optString("section").ifBlank { null }.takeIf { it != "null" },
                actions = acts,
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

        companion object {
            private const val TARGET_BACK = "__back__"
            private val BACK_NODE = Node("← Back", "ic_navigate_before", TARGET_BACK, null)
        }

        private val dm = resources.displayMetrics
        private fun dp(v: Int) = v * dm.density
        private val ring = dp(cfg.radiusDp)          // outer ring — pages/sections
        private val actionRing = dp(cfg.subRadiusDp) // inner ring — actions
        private val dead = dp(28)            // finger inside this → nothing selected
        private val nodeR = dp(26)
        private val leafR = dp(22)
        private val iconPx = (dp(30)).toInt()

        private val scrim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 163, 122, 244); strokeWidth = dp(3); strokeCap = Paint.Cap.ROUND
        }
        private val disc  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 24, 20, 40) }
        private val hot   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 124, 58, 237) }
        private val back  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 60, 60, 80) }
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
            val key = it.childKey ?: return emptyList()
            return kidCache.getOrPut(key) {
                runCatching { host.childrenOf(key) }.getOrDefault(emptyList())
                    .map { c -> Node(c.label, c.iconName, c.target, c.childKey, c.action) }
            }
        }

        // LAYOUT. A level draws as up to TWO concentric rings around its centre:
        // the OUTER ring carries pages/sections, the INNER ring carries actions
        // (Node.action). Each band is spread evenly on its own circle, so adding
        // actions never squeezes the pages. slots[i] maps 1:1 to items[i] and every
        // consumer — hit-testing, drawing, outerR — just walks what layout() returns.
        //
        // minGap = target center-to-center spacing that drives the radius. Must be COMFORTABLY
        // bigger than an icon (2·nodeR) or `ideal` computes smaller than the base ring
        // and max(ring, …) freezes the radius at the base — the radius then never grows
        // with item count (the bug that made every "bigger radius" attempt a no-op).
        private val minGap = nodeR * 2 + dp(40)   // 2·26 + 40 = 92dp
        private val margin = nodeR + dp(8)        // keep whole icon inside the screen
        private val ringSep = nodeR * 2 + dp(10)  // min radial gap so the bands can't touch

        private data class Slot(val r: Float, val a: Double)

        private fun layout(lv: Level): List<Slot> {
            val n = lv.items.size
            if (n <= 1) return listOf(Slot(ring, Math.toRadians(270.0)))
            // Radius clamps to the nearest screen edge so icons stay on screen.
            val edgeDist = minOf(lv.cx, dm.widthPixels - lv.cx, lv.cy, dm.heightPixels - lv.cy) - margin
            val rCap = maxOf(ring, edgeDist)
            val outer = ArrayList<Int>(n); val inner = ArrayList<Int>(n)
            for (i in 0 until n) (if (lv.items[i].action) inner else outer).add(i)

            fun ringRadius(count: Int) = maxOf(ring, minOf((count * minGap / (2 * Math.PI)).toFloat(), rCap))
            // One band only → the original single full-circle layout, untouched.
            if (inner.isEmpty() || outer.isEmpty()) {
                val r = ringRadius(n)
                return List(n) { i -> Slot(r, 2 * Math.PI * i / n - Math.PI / 2) } // start at top
            }
            // Two bands. The inner one takes its configured radius but is pulled in
            // far enough that the outer ring still fits on screen above it; the outer
            // one is pushed out far enough to clear the inner by a whole icon.
            val rIn = minOf(actionRing, rCap - ringSep).coerceAtLeast(dead + nodeR)
            val rOut = minOf(rCap, maxOf(ringRadius(outer.size), rIn + ringSep))

            val slots = arrayOfNulls<Slot>(n)
            // Inner band is offset half a step so its icons sit between the outer
            // ones rather than hiding directly beneath them.
            fun place(idx: List<Int>, r: Float, off: Double) =
                idx.forEachIndexed { k, i -> slots[i] = Slot(r, 2 * Math.PI * k / idx.size - Math.PI / 2 + off) }
            place(outer, rOut, 0.0)
            place(inner, rIn, Math.PI / inner.size)
            return slots.map { it!! }
        }
        private fun slotPos(lv: Level, s: Slot): Pair<Float, Float> =
            (lv.cx + s.r * cos(s.a)).toFloat() to (lv.cy + s.r * sin(s.a)).toFloat()
        private fun outerR(lv: Level): Float = layout(lv).maxOf { it.r }
        // Icon radius = half the neighbour spacing (so circles just touch at most),
        // capped at the full nodeR. Shrinks only when a ring is too packed. Measured
        // per BAND: with two rings, slots[0] and slots[1] can sit on different circles,
        // so the old first-pair measurement would size every icon off a meaningless gap.
        private fun nodeRadiusFor(lv: Level, slots: List<Slot>): Float {
            if (slots.size < 2) return nodeR
            val tightest = slots.groupBy { it.r }
                .filterValues { it.size >= 2 }
                .map { (r, band) -> (2.0 * r * sin(Math.PI / band.size)).toFloat() }
                .minOrNull() ?: return nodeR
            return (tightest / 2f - dp(3)).coerceIn(dp(12), nodeR)
        }

        private var fx = cx; private var fy = cy
        private var active = -1               // highlighted item in the top level

        private fun recompute() {
            val lv = stack.last()
            val dx = fx - lv.cx; val dy = fy - lv.cy; val r = hypot(dx, dy)
            if (r < dead) { active = -1; return }
            val slots = layout(lv)
            // nearest item by straight-line distance — correct across concentric rings
            var best = 0; var bestD = Float.MAX_VALUE
            slots.forEachIndexed { i, s ->
                val (px, py) = slotPos(lv, s)
                val d = hypot(fx - px, fy - py); if (d < bestD) { bestD = d; best = i }
            }
            active = best
            val sel = lv.items[best]
            // descend: drag past the outermost ring into a node with children.
            // Keep the same center — redraw the ring with children + a Back node.
            if (r > outerR(lv) + dp(44) && sel.childKey != null) {
                // Host children + the node's own build.json actions. Back sits on the
                // outer ring with the pages; everything flagged action lands inside.
                val kids = childrenOf(sel) + sel.actions
                if (kids.isNotEmpty()) {
                    stack.add(Level(lv.cx, lv.cy, listOf(BACK_NODE) + kids))
                    active = -1
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
                    val target = if (sel in lv.items.indices) lv.items[sel].target else null
                    if (target == TARGET_BACK) {
                        // pop back to parent level, keep menu open
                        if (stack.size > 1) stack.removeAt(stack.size - 1)
                        active = -1; invalidate()
                    } else {
                        dismiss(this)
                        if (target != null) host.navigate(target)
                    }
                }
                MotionEvent.ACTION_CANCEL -> dismiss(this)
            }
        }

        // If the finger DOES land on the overlay directly, handle it too.
        override fun onTouchEvent(e: MotionEvent): Boolean { feed(e.x, e.y, e.actionMasked); return true }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            val lv = stack.last()
            val slots = layout(lv)
            // arrow from the current center toward the finger (clamped to inner ring)
            val dx = fx - lv.cx; val dy = fy - lv.cy; val len = hypot(dx, dy)
            if (len > dead) {
                val t = (slots.minOf { it.r } - nodeR) / len
                c.drawLine(lv.cx, lv.cy, lv.cx + dx * t, lv.cy + dy * t, arrow)
            }
            val nr = nodeRadiusFor(lv, slots)
            slots.forEachIndexed { i, s ->
                val (nx, ny) = slotPos(lv, s)
                val nd = lv.items[i]
                val isBack = nd.target == TARGET_BACK
                val bgPaint = when {
                    i == active -> hot
                    isBack      -> back
                    else        -> disc
                }
                c.drawCircle(nx, ny, nr, bgPaint)
                icon(nd.iconName)?.let {
                    val h = iconPx / 2f * (nr / nodeR)
                    c.drawBitmap(it, null, RectF(nx - h, ny - h, nx + h, ny + h), null)
                }
                if (nr >= dp(19)) c.drawText(nd.label, nx, ny + nr + dp(13), label)
            }
        }
    }
}
