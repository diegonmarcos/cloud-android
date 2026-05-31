package com.diegonmarcos.superapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * Home — solid-face 3D rotating cube, native counterpart to the linktree
 * `c-cube` CSS scene in front/e-Root/src. Six FrameLayout faces are
 * translated to ±cubeHalf along their respective axis, parented to a
 * single perspective FrameLayout whose rotationX/Y is animated.
 *
 * No shader backdrop on this surface — the gradient backdrop (window
 * level) shows through, the cube floats over it. The matrix-rain GL
 * scene is preserved in [ShaderBackgroundView] but no longer wired
 * into this fragment (it competed visually with the cube and looked
 * like noise per user feedback).
 *
 * Drag-to-rotate is intentionally NOT ported from the linktree
 * CubeView.vue — the activity-level swipe handlers (open drawer +
 * cycle bottom nav) own all touches here.
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
        val size = dp(220)
        val half = size / 2

        // Scene host — centred, perspective applied via cameraDistance.
        val scene = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER)
            cameraDistance = (resources.displayMetrics.density * 8000f)
        }
        // 6 faces — each a coloured-gradient FrameLayout that's then
        // translated into position via View.translationZ and pre-rotated
        // so its normal points outward.
        val faces = listOf(
            face(ctx, size, 0xFF2D1B69.toInt(), 0xFF4C1D95.toInt()),  // front
            face(ctx, size, 0xFF1A0033.toInt(), 0xFF312E81.toInt()),  // back
            face(ctx, size, 0xFF4C1D95.toInt(), 0xFF7C3AED.toInt()),  // right
            face(ctx, size, 0xFF312E81.toInt(), 0xFF6B21A8.toInt()),  // left
            face(ctx, size, 0xFF7C3AED.toInt(), 0xFFB794F4.toInt()),  // top
            face(ctx, size, 0xFF1E1B4B.toInt(), 0xFF4338CA.toInt()),  // bottom
        )
        // Front + back: translate ±z
        faces[0].translationZ = half.toFloat()
        faces[1].translationZ = -half.toFloat(); faces[1].rotationY = 180f
        // Right + left: rotate Y ±90 then translate
        faces[2].rotationY = 90f;  faces[2].translationX = half.toFloat()
        faces[3].rotationY = -90f; faces[3].translationX = -half.toFloat()
        // Top + bottom: rotate X ±90 then translate
        faces[4].rotationX = -90f; faces[4].translationY = -half.toFloat()
        faces[5].rotationX = 90f;  faces[5].translationY = half.toFloat()
        for (f in faces) scene.addView(f)
        root.addView(scene)

        // Continuous slow rotation around both axes — isometric-ish entry.
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 18_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val deg = va.animatedValue as Float
                scene.rotationY = deg
                scene.rotationX = deg * 0.55f
            }
            start()
        }
        return root
    }

    /** A single cube face — same-size FrameLayout with a linear gradient
     *  drawable and a 1dp violet stroke so edges read. */
    private fun face(ctx: Context, size: Int, startColor: Int, endColor: Int): FrameLayout {
        return FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(startColor, endColor),
            ).apply {
                cornerRadius = ctx.resources.displayMetrics.density * 8f
                setStroke(
                    (ctx.resources.displayMetrics.density * 1f).toInt(),
                    Color.argb(0x66, 0xB7, 0x94, 0xF4),
                )
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = Home3DFragment() }
}
