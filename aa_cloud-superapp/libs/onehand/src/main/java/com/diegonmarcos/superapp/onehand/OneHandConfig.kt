package com.diegonmarcos.superapp.onehand

import android.util.Base64
import org.json.JSONObject

/**
 * Data-driven One-Hand config (One Hand Operation+ style), decoded once from
 * BuildConfig.ONEHAND_CONFIG_B64 (the build.json `onehand` block). Multiple
 * handles, each on an edge with its own geometry + gesture→action map. Every
 * knob is a build.json edit — never a Kotlin one (Pillar: DATA-DRIVEN).
 *
 * Gesture keys are `<magnitude>_<direction>`:
 *   magnitude ∈ short | long | hold
 *   direction (side edges left/right) ∈ in | up | down
 *   direction (bottom edge)           ∈ up | left | right
 * `in` = perpendicular-inward from the edge. Values are [OneHandAction] ids.
 */
data class OneHandConfig(
    val handles: List<Handle>,
    val swipeThresholdDp: Int,
    val longSwipeDp: Int,
    val velocityThreshold: Int,
    val holdMs: Int,
) {
    enum class Edge { LEFT, RIGHT, BOTTOM }

    data class Handle(
        val edge: Edge,
        val positionPct: Int,    // offset of the handle's center along the edge
        val lengthPct: Int,      // handle length as % of the edge
        val thicknessDp: Int,    // handle width
        val transparency: Int,   // 0 = invisible … 100 = solid (debug)
        val gestures: Map<String, OneHandAction>,
    )

    companion object {
        fun decode(b64: String): OneHandConfig {
            val json = if (b64.isBlank()) JSONObject()
                       else JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            val d = json.optJSONObject("defaults") ?: JSONObject()
            val handlesJson = json.optJSONArray("handles")
            val handles = buildList {
                for (i in 0 until (handlesJson?.length() ?: 0)) {
                    add(parseHandle(handlesJson!!.getJSONObject(i), d))
                }
            }
            return OneHandConfig(
                handles = handles,
                swipeThresholdDp = d.optInt("swipe_threshold_dp", 24),
                longSwipeDp = d.optInt("long_swipe_dp", 120),
                velocityThreshold = d.optInt("velocity_threshold", 100),
                holdMs = d.optInt("hold_ms", 300),
            )
        }

        private fun parseHandle(h: JSONObject, d: JSONObject): Handle {
            val g = h.optJSONObject("gestures") ?: JSONObject()
            val gestures = buildMap {
                for (key in g.keys()) OneHandAction.from(g.optString(key))?.let { put(key, it) }
            }
            return Handle(
                edge = when (h.optString("edge", "right").lowercase()) {
                    "left" -> Edge.LEFT
                    "bottom" -> Edge.BOTTOM
                    else -> Edge.RIGHT
                },
                positionPct = h.optInt("position_pct", 50),
                lengthPct = h.optInt("length_pct", d.optInt("length_pct", 40)),
                thicknessDp = h.optInt("thickness_dp", d.optInt("thickness_dp", 20)),
                transparency = h.optInt("transparency", d.optInt("transparency", 0)),
                gestures = gestures,
            )
        }
    }
}
