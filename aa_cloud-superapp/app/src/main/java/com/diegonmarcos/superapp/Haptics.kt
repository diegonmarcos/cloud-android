package com.diegonmarcos.superapp

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Named haptic helpers — mirrors the Gemini-app feel for bottom-nav
 * presses: GESTURE_START on tap, two SEGMENT_TICK pulses while the
 * fragment transitions, GESTURE_END when the new screen settles.
 *
 * Each method picks the best primitive available on the device and
 * gracefully falls back on older APIs:
 *   GESTURE_START / GESTURE_END  → API 30+        (R)  fallback VIRTUAL_KEY
 *   SEGMENT_TICK                  → API 33+        (T)  fallback EFFECT_TICK (API 29) → KEYBOARD_TAP
 *   PRIMITIVE_LOW_TICK            → API 31+        (S)
 *   EFFECT_TICK                   → API 29+        (Q)
 *
 * Constants live in HapticFeedbackConstants + VibrationEffect; the
 * mapping is encoded here so callers stay terse: `Haptics.gestureStart(view)`.
 */
object Haptics {

    fun gestureStart(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun gestureEnd(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** Subtle in-transit tick — the heartbeat under the fragment swap.
     *  On API 33+ the system handles it via SEGMENT_TICK; earlier we
     *  fall back to the predefined or composition primitives. */
    fun segmentTick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            return
        }
        val v = vibrator(view.context) ?: return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f, 0)
                    .compose()
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> v.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            )
            else -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
