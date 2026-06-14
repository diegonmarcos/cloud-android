package com.diegonmarcos.cloudnav.maps

import android.util.Base64
import org.json.JSONArray

/**
 * Providers available to the Maps stack — reverse-geocoders (city +
 * neighborhood) and POI lookups (restaurant + shop names). The list is
 * data-driven from `build.json::ui.maps_providers` and baked into
 * BuildConfig at gradle config time. Add a new provider by editing
 * build.json + rebuilding — no Kotlin edits required here.
 *
 * `kind`         what slot(s) this provider fills — `"reverse"` /
 *                `"poi"` / both.
 * `host`         where it runs:
 *                  • `free-noauth`   public free endpoint, no account.
 *                  • `free-account`  free tier, requires signup + key.
 *                  • `paid`          billing account required.
 *                  • `self`          our own deployment (not necessarily
 *                                    online yet — check `endpoint`).
 * `requiresApiKey`  whether [MapsApiKeyPrefs] needs to look up a key
 *                   for this provider's [endpoint] template.
 * `endpoint`     URL template — placeholders `{lat}`, `{lon}`, `{key}`.
 *                Substituted at call time by [MapsProviderClient].
 */
object MapsProviders {
    data class Provider(
        val id: String,
        val label: String,
        val kinds: List<String>,
        val host: String,
        val freeTier: String,
        val requiresApiKey: Boolean,
        val endpoint: String,
    )

    /** Decode the base64 JSON blob baked at gradle-time into a typed
     *  list. Returns empty on parse failure (very defensive — should
     *  never happen because gradle would have failed first). */
    fun loadFromBuildConfig(): List<Provider> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_MAPS_PROVIDERS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val kindArr = o.optJSONArray("kind")
            val kinds = if (kindArr == null) emptyList() else
                (0 until kindArr.length()).map { kindArr.getString(it) }
            Provider(
                id             = o.optString("id"),
                label          = o.optString("label"),
                kinds          = kinds,
                host           = o.optString("host", "free-noauth"),
                freeTier       = o.optString("free_tier", ""),
                requiresApiKey = o.optBoolean("requires_api_key", false),
                endpoint       = o.optString("endpoint", ""),
            )
        }
    }.getOrDefault(emptyList())

    /** Filter [all] to providers that can serve the given slot id
     *  (`"reverse"` or `"poi"`). */
    fun forKind(all: List<Provider>, kind: String): List<Provider> =
        all.filter { it.kinds.contains(kind) }
}
