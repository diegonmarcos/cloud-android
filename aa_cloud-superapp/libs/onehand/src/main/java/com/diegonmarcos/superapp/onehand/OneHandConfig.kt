package com.diegonmarcos.superapp.onehand

import android.util.Base64
import org.json.JSONObject

/**
 * Data-driven One-Hand config, decoded once from BuildConfig.ONEHAND_CONFIG_B64
 * (the build.json `onehand` block baked in by build.gradle). Tuning the edge
 * handle or the gesture→action map is a build.json edit — never a Kotlin one.
 *
 * `gestures` keys are edge-relative logical gestures:
 *   swipe_in   — inward from the edge (right-edge → left, left-edge → right)
 *   swipe_up   — upward along the edge
 *   swipe_down — downward along the edge
 * Values are action ids understood by [OneHandAction].
 */
data class OneHandConfig(
    val edge: Edge,
    val handleWidthDp: Int,
    val handleHeightPct: Int,
    val swipeThresholdDp: Int,
    val velocityThreshold: Int,
    val gestures: Map<String, OneHandAction>,
) {
    enum class Edge { LEFT, RIGHT }

    companion object {
        fun decode(b64: String): OneHandConfig {
            val json = if (b64.isBlank()) JSONObject()
                       else JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            val g = json.optJSONObject("gestures") ?: JSONObject()
            val gestures = buildMap {
                for (key in g.keys()) {
                    OneHandAction.from(g.optString(key))?.let { put(key, it) }
                }
            }
            return OneHandConfig(
                edge = if (json.optString("edge", "right").equals("left", true))
                           Edge.LEFT else Edge.RIGHT,
                handleWidthDp = json.optInt("handle_width_dp", 20),
                handleHeightPct = json.optInt("handle_height_pct", 40),
                swipeThresholdDp = json.optInt("swipe_threshold_dp", 24),
                velocityThreshold = json.optInt("velocity_threshold", 100),
                gestures = gestures,
            )
        }
    }
}
