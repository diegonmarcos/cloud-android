package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * Home — centered 3D rotating cube, native counterpart to the
 * front/e-Root c-cube CSS scene.
 *
 * Implementation: Canvas-based filled-face rendering. Each face is
 * defined by its 4 vertices in cube-local space, projected through
 * a rotation matrix + perspective divide on each frame, drawn as a
 * filled Path. Faces are depth-sorted so back faces draw first and
 * front faces render on top — the classic painter's algorithm —
 * since Android Views do NOT compose 3D translations correctly when
 * children are translated along Z (we previously got six flat
 * squares stacked at the same depth).
 *
 * Drag-to-rotate intentionally NOT ported from CubeView.vue —
 * activity-level swipe gestures own all touches.
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
        val cube = RotatingCubeView(ctx).apply {
            val sz = dp(260)
            layoutParams = FrameLayout.LayoutParams(sz, sz, android.view.Gravity.CENTER)
        }
        root.addView(cube)
        return root
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = Home3DFragment() }
}

/** Filled-face 3D cube. Each face is a coloured-gradient quad with a
 *  faint stroke; painter's-algorithm depth sort keeps the rendering
 *  consistent through full rotations. */
class RotatingCubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fill   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0x88E9D8FD.toInt()
    }
    private val path = Path()

    private var rotY = -Math.PI.toFloat() / 5.5f      // initial isometric
    private var rotX =  Math.PI.toFloat() / 7f

    /** 8 unit-cube vertices. */
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

    /** 6 faces — each is the 4 vertex indices in counter-clockwise
     *  order when viewed from outside the cube. Two colour stops drive
     *  the per-face gradient fill. */
    private data class Face(
        val idx: IntArray,
        val c1: Int,
        val c2: Int,
    )
    private val faces = arrayOf(
        Face(intArrayOf(4, 5, 6, 7), 0xFF2D1B69.toInt(), 0xFF4C1D95.toInt()), // front  (+z)
        Face(intArrayOf(1, 0, 3, 2), 0xFF1A0033.toInt(), 0xFF312E81.toInt()), // back   (-z)
        Face(intArrayOf(5, 1, 2, 6), 0xFF4C1D95.toInt(), 0xFF7C3AED.toInt()), // right  (+x)
        Face(intArrayOf(0, 4, 7, 3), 0xFF312E81.toInt(), 0xFF6B21A8.toInt()), // left   (-x)
        Face(intArrayOf(4, 0, 1, 5), 0xFF7C3AED.toInt(), 0xFFB794F4.toInt()), // top    (-y)
        Face(intArrayOf(7, 6, 2, 3), 0xFF1E1B4B.toInt(), 0xFF4338CA.toInt()), // bottom (+y)
    )

    init {
        // ~16s for a full rotation — slow and contemplative like the
        // e-Root cube's idle motion.
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
        val scale = Math.min(width, height) / 3.4f
        val perspective = 4.5f

        val cy_ = Math.cos(rotY.toDouble()).toFloat()
        val sy_ = Math.sin(rotY.toDouble()).toFloat()
        val cx_ = Math.cos(rotX.toDouble()).toFloat()
        val sx_ = Math.sin(rotX.toDouble()).toFloat()

        // Project all 8 vertices to 2D.
        val px = FloatArray(8); val py = FloatArray(8); val pz = FloatArray(8)
        for (i in 0..7) {
            val v = vertices[i]
            val x1 = v[0] * cy_ + v[2] * sy_
            val z1 = -v[0] * sy_ + v[2] * cy_
            val y2 = v[1] * cx_ - z1 * sx_
            val z2 = v[1] * sx_ + z1 * cx_
            val k  = perspective / (perspective + z2)
            px[i] = cx + x1 * scale * k
            py[i] = cy + y2 * scale * k
            pz[i] = z2
        }

        // Painter's algorithm — draw faces back-to-front so visible ones
        // overdraw the hidden ones, no z-buffer needed.
        val sorted = faces.indices.sortedByDescending {
            val ix = faces[it].idx
            (pz[ix[0]] + pz[ix[1]] + pz[ix[2]] + pz[ix[3]]) / 4f
        }
        for (i in sorted) {
            val f = faces[i]
            val ix = f.idx
            path.reset()
            path.moveTo(px[ix[0]], py[ix[0]])
            path.lineTo(px[ix[1]], py[ix[1]])
            path.lineTo(px[ix[2]], py[ix[2]])
            path.lineTo(px[ix[3]], py[ix[3]])
            path.close()

            fill.shader = LinearGradient(
                px[ix[0]], py[ix[0]],
                px[ix[2]], py[ix[2]],
                f.c1, f.c2, Shader.TileMode.CLAMP,
            )
            fill.style = Paint.Style.FILL
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }
}
