package com.diegonmarcos.superapp.maps

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Lightweight haptics helper for libs:maps. Mirrors the contract of
 * app/.../Haptics.kt — single static `tap(view)` call gives the user
 * the standard "you pressed a thing" tactile bump.
 *
 * Duplicated here rather than depending on app/'s Haptics (which would
 * create a libs:maps → app/ back-reference, breaking the module
 * boundary). When Haptics gets promoted to libs:core in a future
 * cleanup pass, libs:maps can switch to importing it from there and
 * delete this file.
 */
internal object MapsHaptics {
    fun tap(view: View) {
        runCatching {
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
    }
}
