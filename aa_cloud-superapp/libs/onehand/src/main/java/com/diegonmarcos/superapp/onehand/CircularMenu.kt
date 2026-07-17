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
        private val minGap = nodeR * 2 + dp(12)   // min center-to-center between icons
        private val rowStep = nodeR * 2 + dp(10)  // radial gap between concentric rings

        private data class Slot(val r: Float, val a: Double)
        // Fixed concentric radii (base ring, then +rowStep per ring). Each ring on
        // the semicircle holds ⌊π·R/minGap⌋+1 icons; overflow spills to the next,
        // larger ring. NO dependency on runtime view size/center — a screen-coord
        // clamp previously collapsed level 0 to a tiny arc.
        private fun layout(lv: Level): List<Slot> {
            val n = lv.items.size
            if (n <= 1) return listOf(Slot(ring, Math.toRadians(270.0)))
            val slots = ArrayList<Slot>(n)
            var placed = 0; var j = 0
            while (placed < n) {
                val r = ring + j * rowStep
                val capacity = maxOf(2, (Math.PI * r / minGap).toInt() + 1)
                val take = minOf(capacity, n - placed)
                for (k in 0 until take) {
                    val a = if (take <= 1) Math.toRadians(270.0)
                            else Math.toRadians(180.0 + 180.0 * k / (take - 1))
                    slots.add(Slot(r, a))
                }
                placed += take; j++
            }
            return slots
        }
        private fun slotPos(lv: Level, s: Slot): Pair<Float, Float> =
            (lv.cx + s.r * cos(s.a)).toFloat() to (lv.cy + s.r * sin(s.a)).toFloat()
        private fun outerR(lv: Level): Float = layout(lv).maxOf { it.r }

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
            // the current level's items — slots[i] ↔ items[i]
            slots.forEachIndexed { i, s ->
                val (nx, ny) = slotPos(lv, s)
                c.drawCircle(nx, ny, nodeR, if (i == active) hot else disc)
                val nd = lv.items[i]
                icon(nd.iconName)?.let { c.drawBitmap(it, nx - it.width / 2f, ny - it.height / 2f, null) }
                c.drawText(nd.label, nx, ny + nodeR + dp(13), label)
            }
        }
    }
}
