package com.diegonmarcos.superapp.ui
import com.diegonmarcos.superapp.R

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

    /** OEM haptic prefs are often turned OFF by default — performHapticFeedback
     *  silently no-ops then. We force-fire through the direct Vibrator API
     *  using composition primitives where available, falling back to short
     *  one-shot effects. View-level callbacks are kept only for haptic-feedback
     *  metadata (a11y); the actual buzz is direct. */
    fun gestureStart(view: View) {
        runCatching {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
        fire(view.context, intensity = 0.5f, durationMs = 20)
    }

    fun gestureEnd(view: View) {
        runCatching {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
        fire(view.context, intensity = 0.7f, durationMs = 25)
    }

    /** Subtle in-transit tick. */
    fun segmentTick(view: View) {
        fire(view.context, intensity = 0.3f, durationMs = 15)
    }

    /** Lightweight single-tick "press" — for tile clicks and lighter
     *  gestures where firing the whole gesture-pattern would be too
     *  heavy and stack up under rapid input. */
    fun tap(view: View) {
        runCatching {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
        fire(view.context, intensity = 0.45f, durationMs = 18)
    }

    /** Direct-to-vibrator: composition primitive on API 31+, predefined
     *  EFFECT_TICK on 29+, plain one-shot on older. Wrapped in
     *  runCatching because PRIMITIVE_LOW_TICK throws on OEMs that
     *  don't expose composition primitives — the silent failure is
     *  better than a crash. */
    private fun fire(ctx: android.content.Context, intensity: Float, durationMs: Long) {
        runCatching {
            val v = vibrator(ctx) ?: return
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                            intensity.coerceIn(0f, 1f),
                            0,
                        ).compose()
                    v.vibrate(effect)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(durationMs)
                }
            }
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
