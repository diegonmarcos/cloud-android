package com.diegonmarcos.superapp.floatingnav

import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * Data-driven model for the Floating Top Nav Bar, decoded from
 * BuildConfig.UI_FLOATING_NAV_B64 (baked from build.json::ui.floating_nav).
 *
 * The whole point is that the contexts + links are NOT hardcoded in the
 * service: [FloatingNavService] asks [contextFor] which context to render
 * for the current foreground package, and renders [NavContext.homeLabel] +
 * [NavContext.links] verbatim. Add a context / link = edit build.json only.
 */
data class NavLink(
    val label: String,
    val pkg: String,
    /** Optional ui.external_apps id used to install the companion APK when
     *  `pkg` isn't present (e.g. the default context's Cloud-Comms / Cloud-IDE
     *  hubs). Blank for links that should only launch-if-installed. */
    val installApp: String,
)

data class NavContext(
    val id: String,
    val homeLabel: String,
    /** Package brought to front by the ✲ home chip. "self" = Cloud-SuperApp. */
    val homePackage: String,
    /** A foreground package matches this context when it equals one of these
     *  prefixes or starts with `prefix + "."`. Empty = the default fallback. */
    val matchPrefixes: List<String>,
    val links: List<NavLink>,
) {
    fun matches(pkg: String): Boolean =
        matchPrefixes.any { p -> pkg == p || pkg.startsWith("$p.") }
}

data class FloatingNavConfig(
    val enabled: Boolean,
    val pollMs: Long,
    val contexts: List<NavContext>,
) {
    /**
     * Resolve the context for a foreground package. First context whose
     * [NavContext.matches] is true wins; otherwise the `default` context (the
     * one with empty match_prefixes). Null only if no default is declared.
     */
    fun contextFor(foregroundPkg: String?): NavContext? {
        if (foregroundPkg != null) {
            contexts.firstOrNull { it.matchPrefixes.isNotEmpty() && it.matches(foregroundPkg) }
                ?.let { return it }
        }
        return contexts.firstOrNull { it.matchPrefixes.isEmpty() }
            ?: contexts.firstOrNull()
    }

    companion object {
        fun get(): FloatingNavConfig = parse(decode())

        private fun decode(): String = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_FLOATING_NAV_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("{}")

        /** Visible for tests — parse a raw JSON object string. */
        fun parse(raw: String): FloatingNavConfig {
            val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            val contexts = mutableListOf<NavContext>()
            val arr = o.optJSONArray("contexts")
            if (arr != null) for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                if (id.isBlank()) continue
                val prefixes = mutableListOf<String>()
                c.optJSONArray("match_prefixes")?.let { pa ->
                    for (j in 0 until pa.length()) pa.optString(j).takeIf { it.isNotBlank() }?.let(prefixes::add)
                }
                val links = mutableListOf<NavLink>()
                c.optJSONArray("links")?.let { la ->
                    for (j in 0 until la.length()) {
                        val l = la.optJSONObject(j) ?: continue
                        val label = l.optString("label"); val pkg = l.optString("package")
                        if (label.isBlank() || pkg.isBlank()) continue
                        links.add(NavLink(label, pkg, l.optString("install_app")))
                    }
                }
                contexts.add(
                    NavContext(
                        id = id,
                        homeLabel = c.optString("home_label", id),
                        homePackage = c.optString("home_package", "self"),
                        matchPrefixes = prefixes,
                        links = links,
                    ),
                )
            }
            return FloatingNavConfig(
                enabled = o.optBoolean("enabled", true),
                pollMs = o.optLong("poll_ms", 1000L).coerceAtLeast(250L),
                contexts = contexts,
            )
        }
    }
}
