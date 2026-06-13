package com.diegonmarcos.superapp.floatingnav

import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-driven model for the Floating Top Nav Bar, decoded from
 * BuildConfig.UI_FLOATING_NAV_B64 (baked from build.json::ui.floating_nav).
 *
 * The expanded menu is a constant 2-line box, identical wherever it's shown:
 *   line 1 = [parents]  (the three hubs — same everywhere)
 *   line 2 = the matched context's [NavContext.children]
 * [FloatingNavService] renders these verbatim and dispatches each item by its
 * [NavItem.target] scheme. Add a hub / child = edit build.json only.
 */
data class NavItem(
    val label: String,
    /** `self` | `app:<pkg>` | `section:<id>` | `action:<id>` | `page:<...>` */
    val target: String,
    /** ui.external_apps id used to install the companion APK when an `app:`
     *  target isn't present (e.g. the Cloud-IDE / Cloud-Comms hubs). */
    val installApp: String,
)

data class NavContext(
    val id: String,
    /** Foreground package matches when it equals one of these or starts with
     *  `prefix + "."`. Empty = the default fallback (Cloud-SuperApp pages). */
    val matchPrefixes: List<String>,
    val children: List<NavItem>,
) {
    fun matches(pkg: String): Boolean =
        matchPrefixes.any { p -> pkg == p || pkg.startsWith("$p.") }
}

data class FloatingNavConfig(
    val enabled: Boolean,
    val pollMs: Long,
    /** Push the whole menu box down from the top by this % of screen height. */
    val verticalOffsetPct: Int,
    val parents: List<NavItem>,
    /** Global utility actions. Compact menu shows the first [compactActionCount];
     *  the Expanded view shows them all. */
    val actions: List<NavItem>,
    val compactActionCount: Int,
    val contexts: List<NavContext>,
) {
    /** Context (line 2) for a foreground package; falls back to `default`. */
    fun contextFor(foregroundPkg: String?): NavContext? {
        if (foregroundPkg != null) {
            contexts.firstOrNull { it.matchPrefixes.isNotEmpty() && it.matches(foregroundPkg) }
                ?.let { return it }
        }
        return contexts.firstOrNull { it.matchPrefixes.isEmpty() } ?: contexts.firstOrNull()
    }

    companion object {
        fun get(): FloatingNavConfig = parse(decode())

        private fun decode(): String = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_FLOATING_NAV_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("{}")

        private fun items(arr: JSONArray?): List<NavItem> {
            val out = mutableListOf<NavItem>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label"); val target = o.optString("target")
                if (label.isBlank() || target.isBlank()) continue
                out.add(NavItem(label, target, o.optString("install_app")))
            }
            return out
        }

        /** Visible for tests — parse a raw JSON object string. */
        fun parse(raw: String): FloatingNavConfig {
            val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            val contexts = mutableListOf<NavContext>()
            val carr = o.optJSONArray("contexts")
            if (carr != null) for (i in 0 until carr.length()) {
                val c = carr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                if (id.isBlank()) continue
                val prefixes = mutableListOf<String>()
                c.optJSONArray("match_prefixes")?.let { pa ->
                    for (j in 0 until pa.length()) pa.optString(j).takeIf { it.isNotBlank() }?.let(prefixes::add)
                }
                contexts.add(NavContext(id, prefixes, items(c.optJSONArray("children"))))
            }
            return FloatingNavConfig(
                enabled = o.optBoolean("enabled", true),
                pollMs = o.optLong("poll_ms", 1000L).coerceAtLeast(250L),
                verticalOffsetPct = o.optInt("vertical_offset_pct", 9).coerceIn(0, 80),
                parents = items(o.optJSONArray("parents")),
                actions = items(o.optJSONArray("actions")),
                compactActionCount = o.optInt("compact_action_count", 3).coerceAtLeast(1),
                contexts = contexts,
            )
        }
    }
}
