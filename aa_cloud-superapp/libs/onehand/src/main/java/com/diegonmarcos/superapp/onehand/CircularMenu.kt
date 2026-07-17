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

        // Upward HALF-MOON: nodes spread across the top semicircle (180°=left →
        // 270°=straight up → 360°=right) since the star sits near the bottom and
        // there's no room below. i=0 is leftmost, i=n-1 rightmost.
        private val n = cfg.nodes.size
        private fun arcAngle(i: Int, count: Int): Double =
            if (count <= 1) Math.toRadians(270.0) else Math.toRadians(180.0 + 180.0 * i / (count - 1))
        private fun nodeAngle(i: Int) = arcAngle(i, n)
        private fun nodePos(i: Int): Pair<Float, Float> {
            val a = nodeAngle(i); return (cx + ring * cos(a)).toFloat() to (cy + ring * sin(a)).toFloat()
        }

        private var fx = cx; private var fy = cy
        private var active = -1                       // highlighted node index
        private var leaves: List<Leaf> = emptyList()
        private var activeLeaf = -1

        private fun recomputeSelection() {
            val dx = fx - cx; val dy = fy - cy
            if (hypot(dx, dy) < dead) { active = -1; leaves = emptyList(); activeLeaf = -1; return }
            val fa = atan2(dy, dx).toDouble()
            // nearest node by angular distance
            var best = 0; var bestD = Double.MAX_VALUE
            for (i in 0 until n) {
                var d = Math.abs(angDiff(fa, nodeAngle(i))); if (d < bestD) { bestD = d; best = i }
            }
            if (best != active) {
                active = best
                val sec = cfg.nodes[best].section
                leaves = if (sec != null) runCatching { host.pagesFor(sec) }.getOrDefault(emptyList()) else emptyList()
            }
            // pick a leaf if finger is past the ring and near one
            activeLeaf = -1
            if (leaves.isNotEmpty() && hypot(dx, dy) > ring + dp(20)) {
                var bl = -1; var blD = leafR * 1.6f
                leaves.forEachIndexed { i, _ ->
                    val (lx, ly) = leafPos(i)
                    val d = hypot(fx - lx, fy - ly); if (d < blD) { blD = d; bl = i }
                }
                activeLeaf = bl
            }
        }

        // The active node's children form their OWN upward half-moon, CENTRED (not
        // hung off the node) and further up the screen (subRing > ring) — that's
        // the "second level goes up, centred" cascade.
        private fun leafPos(leafIdx: Int): Pair<Float, Float> {
            val a = arcAngle(leafIdx, leaves.size)
            return (cx + subRing * cos(a)).toFloat() to (cy + subRing * sin(a)).toFloat()
        }

        private fun angDiff(a: Double, b: Double): Double {
            var d = a - b; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return d
        }

        override fun feed(x: Float, y: Float, action: Int) {
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    fx = x; fy = y; recomputeSelection(); invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val node = active; val leaf = activeLeaf
                    dismiss(this)
                    if (node >= 0) {
                        if (leaf >= 0 && leaf < leaves.size) host.navigate(leaves[leaf].target)
                        else if (cfg.nodes[node].section == null || leaves.isEmpty()) host.navigate(cfg.nodes[node].target)
                    }
                }
                MotionEvent.ACTION_CANCEL -> dismiss(this)
            }
        }

        // If the finger DOES land on the overlay directly, handle it too.
        override fun onTouchEvent(e: MotionEvent): Boolean { feed(e.x, e.y, e.actionMasked); return true }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            // arrow from centre toward finger (clamped to the ring)
            val dx = fx - cx; val dy = fy - cy; val len = hypot(dx, dy)
            if (len > dead) {
                val t = (ring - nodeR) / len
                c.drawLine(cx, cy, cx + dx * t, cy + dy * t, arrow)
            }
            // fan-out leaves of the active node (drawn under nodes)
            if (active >= 0) leaves.forEachIndexed { i, lf ->
                val (lx, ly) = leafPos(i)
                c.drawCircle(lx, ly, leafR, if (i == activeLeaf) hot else disc)
                icon(lf.iconName)?.let { c.drawBitmap(it, lx - it.width / 2f, ly - it.height / 2f, null) }
                c.drawText(lf.label, lx, ly + leafR + dp(13), label)
            }
            // top-level nodes
            cfg.nodes.forEachIndexed { i, nd ->
                val (nx, ny) = nodePos(i)
                c.drawCircle(nx, ny, nodeR, if (i == active) hot else disc)
                icon(nd.iconName)?.let { c.drawBitmap(it, nx - it.width / 2f, ny - it.height / 2f, null) }
                c.drawText(nd.label, nx, ny + nodeR + dp(13), label)
            }
        }
    }
}
