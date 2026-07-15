package com.diegonmarcos.superapp.onehand

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Data-driven One-Hand config (One Hand Operation+ style). build.json seeds the
 * DEFAULT action per sector; [effective] overlays the user's per-swipe overrides
 * from [OneHandPrefs].
 *
 * Each side handle has 3 sectors keyed `top` | `center` | `down` (center =
 * straight inward, top/down = tilt up/down). Bottom edge → `left`|`center`|`right`.
 * Gesture values are [GestureAction] strings: an action id ("back") or "app:<pkg>".
 */
data class OneHandConfig(
    val handles: List<Handle>,
    val apps: List<AppOption>,
    val swipeThresholdDp: Int,
    val trigger: Trigger,
    val longPressMs: Int,
    val edgeInsetGestureDp: Int, // inset applied only when the device is in gesture-nav mode
    val radial: Radial,
) {
    enum class Edge { LEFT, RIGHT, BOTTOM }

    /** Two-finger radial menu (observe-only, needs Android 14+). */
    data class Radial(
        val enabled: Boolean,
        val holdMs: Int,
        val slopDp: Int,
        val radiusDp: Int,
        val items: List<GestureAction>,
    )

    /** How the option menu is summoned: hold the handle, or on first touch. */
    enum class Trigger { LONG_PRESS, TOUCH }

    data class Handle(
        val id: String,
        val edge: Edge,
        val positionPct: Int,
        val lengthPct: Int,
        val lengthDp: Int,      // fixed handle length; >0 overrides lengthPct
        val thicknessDp: Int,
        val transparency: Int,
        val edgeInsetDp: Int,
        val gestures: Map<String, GestureAction>,
    )

    data class Slot(val key: String, val label: String)
    data class AppOption(val label: String, val pkg: String)

    companion object {
        /** The 3 sectors of a handle, ordered top→center→down (UI + preview iterate these). */
        fun slotsFor(edge: Edge): List<Slot> = when (edge) {
            Edge.BOTTOM -> listOf(Slot("left", "Left"), Slot("center", "Center"), Slot("right", "Right"))
            else -> listOf(Slot("top", "Top"), Slot("center", "Center"), Slot("down", "Down"))
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
            val trigger = if (d.optString("trigger", "long_press").equals("touch", true))
                Trigger.TOUCH else Trigger.LONG_PRESS
            val rj = json.optJSONObject("radial") ?: JSONObject()
            val ritems = rj.optJSONArray("items")
            val radial = Radial(
                enabled = rj.optBoolean("enabled", false),
                holdMs = rj.optInt("two_finger_hold_ms", 250),
                slopDp = rj.optInt("slop_dp", 40),
                radiusDp = rj.optInt("radius_dp", 120),
                items = buildList {
                    for (i in 0 until (ritems?.length() ?: 0))
                        GestureAction.parse(ritems!!.optString(i))?.let { add(it) }
                },
            )
            return OneHandConfig(
                handles, apps, d.optInt("swipe_threshold_dp", 24),
                trigger, d.optInt("long_press_ms", 300),
                d.optInt("edge_inset_gesture_dp", 28), radial,
            )
        }

        /** Baked defaults with the user's overrides ([OneHandPrefs]) applied. */
        fun effective(ctx: Context): OneHandConfig {
            val base = decode(BuildConfig.ONEHAND_CONFIG_B64)
                .let { it.copy(trigger = OneHandPrefs.trigger(ctx, it.trigger)) }
            return base.copy(handles = base.handles.map { h ->
                val merged = LinkedHashMap<String, GestureAction>()
                for (slot in slotsFor(h.edge)) {
                    OneHandPrefs.actionFor(ctx, h.id, slot.key, h.gestures[slot.key])
                        ?.let { merged[slot.key] = it }
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
                lengthDp = h.optInt("length_dp", d.optInt("length_dp", 0)),
                thicknessDp = h.optInt("thickness_dp", d.optInt("thickness_dp", 20)),
                transparency = h.optInt("transparency", d.optInt("transparency", 0)),
                edgeInsetDp = h.optInt("edge_inset_dp", d.optInt("edge_inset_dp", 24)),
                gestures = gestures,
            )
        }
    }
}
