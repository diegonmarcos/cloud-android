package com.diegonmarcos.superapp

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-driven theme features decoder.
 *
 * BuildConfig.UI_LAUNCHER_THEMES_B64 was already baked in app/build.gradle
 * from build.json::ui.launcher_themes. This singleton parses it once at
 * first access and exposes a typed [Features] per theme id, so the rest
 * of the app reads
 *
 *   LauncherThemes.featuresFor("cloud_power_saving").bottomNav  // → false
 *
 * instead of an ad-hoc when-ladder. Adding a new feature toggle = one
 * key in build.json + one property here; nothing else.
 *
 * FIRE rule 6 — declarative, not hardcoded. The Cloud / Minimalist /
 * Power-Saving differences ARE the JSON; MainActivity just reads.
 */
object LauncherThemes {

    /** Mirrors the keys in build.json::ui.launcher_themes[*].features. */
    data class Features(
        val home3d:            Boolean,
        val bottomNav:         Boolean,
        val dynamicIsland:     Boolean,
        val animations:        Boolean,
        val drawer:            Boolean,
        val oledBlack:         Boolean,
        val grid:              String,
        val backgroundPause:   Boolean,
        val wireguardRequired: Boolean,
    ) {
        companion object {
            val SAFE_DEFAULT = Features(
                home3d            = true,
                bottomNav         = true,
                dynamicIsland     = true,
                animations        = true,
                drawer            = true,
                oledBlack         = false,
                grid              = "default",
                backgroundPause   = false,
                wireguardRequired = false,
            )
        }
    }

    private val byId: Map<String, Features> by lazy { parse() }

    fun featuresFor(themeId: String): Features = byId[themeId] ?: Features.SAFE_DEFAULT
    fun featuresFor(theme: LauncherTheme): Features = featuresFor(theme.id)

    private fun parse(): Map<String, Features> {
        val raw = runCatching {
            String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, Features>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val f = o.optJSONObject("features") ?: JSONObject()
            out[id] = Features(
                home3d            = f.optBoolean("home_3d",            true),
                bottomNav         = f.optBoolean("bottom_nav",         true),
                dynamicIsland     = f.optBoolean("dynamic_island",     true),
                animations        = f.optBoolean("animations",         true),
                drawer            = f.optBoolean("drawer",             true),
                oledBlack         = f.optBoolean("oled_black",         false),
                grid              = f.optString("grid",                "default"),
                backgroundPause   = f.optBoolean("background_pause",   false),
                wireguardRequired = f.optBoolean("wireguard_required", false),
            )
        }
        return out
    }
}
