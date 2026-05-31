package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment

/**
 * Home — centered 3D rotating cube. Pure visual surface; the
 * activity owns the pull-up gesture that opens the app drawer
 * (see [MainActivity.installNavSwipeGesture]).
 *
 * The cube is a custom Canvas view ([RotatingCubeView]) — 8
 * vertices projected through a rotation matrix on each frame,
 * drawn as 12 line edges with the brand violet accent.
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
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val cube = RotatingCubeView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
        }
        column.addView(cube)
        root.addView(column)
        return root
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = Home3DFragment() }
}

/** 3D wireframe cube spinning around the Y + X axes. Pure Canvas — no
 *  Lottie / GL dependency. Vertices live in unit-cube space, get
 *  rotated each frame, then projected to 2D with a simple perspective
 *  divide. */
class RotatingCubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = 0xFFB794F4.toInt()  // brand violet
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = 0xFF7C3AED.toInt()  // deeper violet for highlight edges
    }

    private var rotY = 0f
    private var rotX = 0f

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
    /** Pairs of vertex indices that share an edge. */
    private val edges = arrayOf(
        intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
        intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
        intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7),
    )

    init {
        ValueAnimator.ofFloat(0f, (Math.PI * 2f).toFloat()).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                rotY = va.animatedValue as Float
                rotX = (va.animatedValue as Float) * 0.4f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val scale = (Math.min(width, height) / 3.5f)
        val perspective = 4f  // distance from camera in cube units

        val cy_ = Math.cos(rotY.toDouble()).toFloat()
        val sy_ = Math.sin(rotY.toDouble()).toFloat()
        val cx_ = Math.cos(rotX.toDouble()).toFloat()
        val sx_ = Math.sin(rotX.toDouble()).toFloat()

        val projected = vertices.map { v ->
            val (vx, vy, vz) = Triple(v[0], v[1], v[2])
            // Y-axis rotation
            val x1 = vx * cy_ + vz * sy_
            val z1 = -vx * sy_ + vz * cy_
            // X-axis rotation
            val y2 = vy * cx_ - z1 * sx_
            val z2 = vy * sx_ + z1 * cx_
            // Perspective
            val k  = perspective / (perspective + z2)
            floatArrayOf(cx + x1 * scale * k, cy + y2 * scale * k, z2)
        }

        for ((i, e) in edges.withIndex()) {
            val a = projected[e[0]]
            val b = projected[e[1]]
            val avgZ = (a[2] + b[2]) / 2f
            val p = if (avgZ < 0) accent else paint
            canvas.drawLine(a[0], a[1], b[0], b[1], p)
        }
    }
}
