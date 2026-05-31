package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
        // Galaxy backdrop — stars + sweeping comets behind the cube.
        val galaxy = GalaxyBackdropView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(galaxy)

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

    private var rotY = -Math.PI.toFloat() / 5.5f
    private var rotX =  Math.PI.toFloat() / 7f

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

    init {
        ValueAnimator.ofFloat(0f, (Math.PI * 2f).toFloat()).apply {
            duration = 16_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                rotY = -Math.PI.toFloat() / 5.5f + t
                rotX =  Math.PI.toFloat() / 7f   + t * 0.4f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
    }
}
