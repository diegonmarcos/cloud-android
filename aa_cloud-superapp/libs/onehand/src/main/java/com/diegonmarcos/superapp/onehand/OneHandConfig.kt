package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Data-driven One-Hand config (One Hand Operation+ style). build.json seeds the
 * DEFAULT action per swipe slot (baked to BuildConfig.ONEHAND_CONFIG_B64);
 * [effective] overlays the user's per-swipe overrides from [OneHandPrefs].
 *
 * Slot keys are `<magnitude>_<direction>`:
 *   magnitude ∈ short | long | hold
 *   direction (side edges) ∈ in | up | down   (in = perpendicular inward)
 *   direction (bottom edge) ∈ up | left | right
 * Gesture values are [GestureAction] strings: an action id ("back") or "app:<pkg>".
 */
data class OneHandConfig(
    val handles: List<Handle>,
    val apps: List<AppOption>,
    val swipeThresholdDp: Int,
    val longSwipeDp: Int,
    val velocityThreshold: Int,
    val holdMs: Int,
) {
    enum class Edge { LEFT, RIGHT, BOTTOM }

    data class Handle(
        val id: String,
        val edge: Edge,
        val positionPct: Int,
        val lengthPct: Int,
        val thicknessDp: Int,
        val transparency: Int,
        val gestures: Map<String, GestureAction>,
    )

    /** A user-editable swipe slot: its pref key + a human label. */
    data class Slot(val key: String, val label: String)

    /** An app offered in the per-swipe action picker. */
    data class AppOption(val label: String, val pkg: String)

    companion object {
        /** The swipe slots the engine actually honors for an edge (UI iterates these). */
        fun slotsFor(edge: Edge): List<Slot> {
            val dirs = when (edge) {
                Edge.BOTTOM -> listOf("up" to "up", "left" to "up-left", "right" to "up-right")
                else -> listOf("in" to "inward", "up" to "inward-up", "down" to "inward-down")
            }
            val holdDir = if (edge == Edge.BOTTOM) "up" else "in"
            return buildList {
                for ((d, dl) in dirs) {
                    add(Slot("short_$d", "Short swipe $dl"))
                    add(Slot("long_$d", "Long swipe $dl"))
                }
                add(Slot("hold_$holdDir", "Swipe & hold"))
            }
        }

        fun decode(b64: String): OneHandConfig {
            val json = if (b64.isBlank()) JSONObject()
                       else JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            val d = json.optJSONObject("defaults") ?: JSONObject()
            val handlesJson = json.optJSONArray("handles")
            val handles = buildList {
                for (i in 0 until (handlesJson?.length() ?: 0)) {
                    add(parseHandle(handlesJson!!.getJSONObject(i), d, i))
                }
            }
            val appsJson = json.optJSONArray("apps")
            val apps = buildList {
                for (i in 0 until (appsJson?.length() ?: 0)) {
                    val a = appsJson!!.getJSONObject(i)
                    add(AppOption(a.optString("label"), a.optString("package")))
                }
            }
            return OneHandConfig(
                handles = handles,
                apps = apps,
                swipeThresholdDp = d.optInt("swipe_threshold_dp", 24),
                longSwipeDp = d.optInt("long_swipe_dp", 120),
                velocityThreshold = d.optInt("velocity_threshold", 100),
                holdMs = d.optInt("hold_ms", 350),
            )
        }

        /** Baked defaults with the user's per-swipe overrides ([OneHandPrefs]) applied. */
        fun effective(ctx: Context): OneHandConfig {
            val base = decode(BuildConfig.ONEHAND_CONFIG_B64)
            return base.copy(handles = base.handles.map { h ->
                val merged = LinkedHashMap<String, GestureAction>()
                for (slot in slotsFor(h.edge)) {
                    val default = h.gestures[slot.key]
                    OneHandPrefs.actionFor(ctx, h.id, slot.key, default)?.let { merged[slot.key] = it }
                }
                h.copy(gestures = merged)
            })
        }

        private fun parseHandle(h: JSONObject, d: JSONObject, idx: Int): Handle {
            val g = h.optJSONObject("gestures") ?: JSONObject()
            val gestures = buildMap {
                for (key in g.keys()) GestureAction.parse(g.optString(key))?.let { put(key, it) }
            }
            val edge = when (h.optString("edge", "right").lowercase()) {
                "left" -> Edge.LEFT
                "bottom" -> Edge.BOTTOM
                else -> Edge.RIGHT
            }
            return Handle(
                id = h.optString("id", "h$idx"),
                edge = edge,
                positionPct = h.optInt("position_pct", 50),
                lengthPct = h.optInt("length_pct", d.optInt("length_pct", 40)),
                thicknessDp = h.optInt("thickness_dp", d.optInt("thickness_dp", 20)),
                transparency = h.optInt("transparency", d.optInt("transparency", 0)),
                gestures = gestures,
            )
        }
    }
}
