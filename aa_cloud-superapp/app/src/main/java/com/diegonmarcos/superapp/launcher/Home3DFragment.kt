package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ScreenLocker

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * Home — neon-violet wireframe rotating cube. Edges only, no faces,
 * with a soft outer glow + sharp inner stroke for a "neon tube" feel
 * that matches the linktree palette without needing per-face shaders
 * (which would require a real GL textured cube — out of scope for the
 * Canvas pipeline).
 */
class Home3DFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // Galaxy backdrop now lives at the shell level (R.id.galaxy_backdrop
        // in activity_main.xml) so it extends edge-to-edge into the
        // status-bar area. Home3DFragment only draws the cube on top.
        val cube = RotatingCubeView(ctx).apply {
            // Bigger view so the cube's rotated corners don't clip
            // against the cube view's own bounds. The cube renders
            // inside this square; we keep the scene oversize.
            val sz = dp(360)
            layoutParams = FrameLayout.LayoutParams(sz, sz, android.view.Gravity.CENTER)
            // Disable hardware accel for this view so BlurMaskFilter on
            // the glow paint renders correctly (HW pipeline ignores
            // BlurMaskFilter on stroked paths).
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        root.addView(cube)
        // Double-tap anywhere on the home root → lock the screen
        // (DevicePolicyManager.lockNow via ScreenLocker). GestureDetector
        // only fires onDoubleTap on TWO down-up cycles within ~300ms,
        // so single taps on tiles / chips still propagate to their own
        // click handlers — ViewGroup dispatch checks children before
        // our root-level listener.
        ScreenLocker.attachDoubleTapLock(root)
        return root
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = Home3DFragment() }
}

/** Wireframe cube with a neon-glow stroke (outer soft halo + inner
 *  sharp line). 8 vertices, 12 edges, perspective projection,
 *  continuous rotation. */
class RotatingCubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = 0x88B794F4.toInt()
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val sharp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = 0xFFE9D8FD.toInt()
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        color = 0xFF7C3AED.toInt()
    }

    // Rotation is computed each frame from SystemClock.elapsedRealtime()
    // — never reset. Previous version used a ValueAnimator looping
    // 0 → 2π over 16s, then accumulating rotX as `t * 0.4`. At loop end
    // rotY had advanced 2π (continuous) but rotX had advanced 0.8π —
    // NOT a multiple of 2π — so the cube visibly snapped back -144° on
    // X every 16s. Time-based wrap guarantees both axes are always
    // continuous because we never reset the accumulator.
    private val rotY0 = -Math.PI.toFloat() / 5.5f
    private val rotX0 =  Math.PI.toFloat() / 7f
    private var rotY = rotY0
    private var rotX = rotX0
    /** Y full rotation period (ms) — 16s = matches the old feel. */
    private val periodYMs = 16_000.0
    /** X period — picked independently. With both axes driven from the
     *  same epoch and their own period, they always wrap cleanly: at no
     *  point does either angle skip a value. Different periods give a
     *  non-repeating wobble that reads as organic rather than mechanical. */
    private val periodXMs = 23_500.0
    private val animStartMs = SystemClock.elapsedRealtime()

    private val vertices = arrayOf(
        floatArrayOf(-1f, -1f, -1f),
        floatArrayOf( 1f, -1f, -1f),
        floatArrayOf( 1f,  1f, -1f),
        floatArrayOf(-1f,  1f, -1f),
        floatArrayOf(-1f, -1f,  1f),
        floatArrayOf( 1f, -1f,  1f),
        floatArrayOf( 1f,  1f,  1f),
        floatArrayOf(-1f,  1f,  1f),
    )
    private val edges = arrayOf(
        intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
        intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
        intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7),
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Time since first frame — drives both rotation axes. Reading the
        // monotonic system clock every frame instead of accumulating in a
        // ValueAnimator means the loop boundary is invisible: every
        // angle is reproducible from `elapsed` without any state to
        // discontinue.
        val elapsed = (SystemClock.elapsedRealtime() - animStartMs).toDouble()
        rotY = (rotY0 + (elapsed / periodYMs) * (2.0 * Math.PI)).toFloat()
        rotX = (rotX0 + (elapsed / periodXMs) * (2.0 * Math.PI)).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        // Smaller scale denominator → larger cube; pump it back to ~3.4f
        // gave clipping at rotated corners. 4.6 keeps the cube safely
        // inside the 360dp host on every rotation.
        val scale = Math.min(width, height) / 4.6f
        val perspective = 4.5f

        val cyR = Math.cos(rotY.toDouble()).toFloat()
        val syR = Math.sin(rotY.toDouble()).toFloat()
        val cxR = Math.cos(rotX.toDouble()).toFloat()
        val sxR = Math.sin(rotX.toDouble()).toFloat()

        val px = FloatArray(8); val py = FloatArray(8); val pz = FloatArray(8)
        for (i in 0..7) {
            val v = vertices[i]
            val x1 = v[0] * cyR + v[2] * syR
            val z1 = -v[0] * syR + v[2] * cyR
            val y2 = v[1] * cxR - z1 * sxR
            val z2 = v[1] * sxR + z1 * cxR
            val k  = perspective / (perspective + z2)
            px[i] = cx + x1 * scale * k
            py[i] = cy + y2 * scale * k
            pz[i] = z2
        }

        // Draw in 3 passes: outer glow → sharp → accent dots at vertices.
        for (e in edges) canvas.drawLine(px[e[0]], py[e[0]], px[e[1]], py[e[1]], glow)
        for (e in edges) {
            val avgZ = (pz[e[0]] + pz[e[1]]) / 2f
            // Edges in the back fade slightly so depth reads.
            sharp.alpha = (255 - (avgZ * 80f).coerceIn(0f, 120f)).toInt()
            canvas.drawLine(px[e[0]], py[e[0]], px[e[1]], py[e[1]], sharp)
        }
        for (i in 0..7) canvas.drawCircle(px[i], py[i], 4f, accent)
        // Schedule the next frame — VSYNC-aligned by the platform. When
        // the view detaches the framework drops these invalidations, so
        // there's no leak risk.
        postInvalidateOnAnimation()
    }
}
