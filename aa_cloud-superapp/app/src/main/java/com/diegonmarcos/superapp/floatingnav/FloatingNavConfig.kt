package com.diegonmarcos.superapp.floatingnav

import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-driven model for the Floating Top Nav Bar, decoded from
 * BuildConfig.UI_FLOATING_NAV_B64 (baked from build.json::ui.floating_nav).
 *
 * The expanded menu is a constant 3-line box, identical wherever it's shown,
 * with a strictly decreasing font size per line:
 *   line 1 = [root]     (the apex — Cloud SuperApp — biggest)
 *   line 2 = [parents]  (the sibling hubs — same everywhere — medium)
 *   line 3 = the matched context's [NavContext.children] (smallest; omitted
 *            when the context has none, e.g. forkless Cloud-Nav)
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
    /** Distance from the top edge (dp) — default just below the dynamic island. */
    val topOffsetDp: Int,
    /** Menu box width as a % of screen width. */
    val widthPct: Int,
    /** Line 1 apex — the constellation root (Cloud SuperApp), biggest font. */
    val root: NavItem?,
    /** Line 2 — the sibling hubs (Cloud IDE · Comms · Nav), medium font. */
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

        /** Parse a single NavItem object (e.g. the `root` apex), or null. */
        private fun item(o: JSONObject?): NavItem? {
            if (o == null) return null
            val label = o.optString("label"); val target = o.optString("target")
            if (label.isBlank() || target.isBlank()) return null
            return NavItem(label, target, o.optString("install_app"))
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
                topOffsetDp = o.optInt("top_offset_dp", 88).coerceIn(0, 600),
                widthPct = o.optInt("width_pct", 90).coerceIn(40, 100),
                root = item(o.optJSONObject("root")),
                parents = items(o.optJSONArray("parents")),
                actions = items(o.optJSONArray("actions")),
                compactActionCount = o.optInt("compact_action_count", 3).coerceAtLeast(1),
                contexts = contexts,
            )
        }
    }
}
