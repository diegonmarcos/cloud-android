package com.diegonmarcos.cloudnav

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-driven shell config. Single source of truth = build.json::ui.*,
 * baked into BuildConfig as base64 JSON by app/build.gradle (FIRE RULE #6 —
 * never hardcode the tab / island / category lists in Kotlin).
 */
data class NavTab(val id: String, val label: String, val icon: String)
data class SearchIsland(val id: String, val label: String, val icon: String, val action: String)
data class PlaceCategory(val id: String, val label: String, val icon: String, val query: String, val emoji: String)
data class SearchScope(val id: String, val label: String, val emoji: String, val radiusKm: Double)

object NavConfig {

    private fun decode(b64: String): String =
        try { String(Base64.decode(b64, Base64.DEFAULT)) } catch (t: Throwable) { "[]" }

    val tabs: List<NavTab> by lazy {
        val arr = JSONArray(decode(BuildConfig.UI_NAV_TABS_B64))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NavTab(o.getString("id"), o.getString("label"), o.optString("icon", o.getString("id")))
        }
    }

    val islands: List<SearchIsland> by lazy {
        val arr = JSONArray(decode(BuildConfig.UI_SEARCH_ISLANDS_B64))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchIsland(
                o.getString("id"), o.getString("label"),
                o.optString("icon", "star"), o.optString("action", ""),
            )
        }
    }

    val placeCategories: List<PlaceCategory> by lazy {
        val arr = JSONArray(decode(BuildConfig.UI_PLACES_CATEGORIES_B64))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PlaceCategory(
                o.getString("id"), o.getString("label"),
                o.optString("icon", "place"), o.optString("query", ""),
                o.optString("emoji", ""),
            )
        }
    }

    val searchScopes: List<SearchScope> by lazy {
        val arr = JSONArray(decode(BuildConfig.UI_SEARCH_SCOPES_B64))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchScope(o.getString("id"), o.optString("label", o.getString("id")),
                o.optString("emoji", ""), o.optDouble("radius_km", 0.0))
        }
    }

    val defaultScope: String get() = BuildConfig.UI_DEFAULT_SEARCH_SCOPE.ifBlank { "city" }

    val defaultTab: String get() = BuildConfig.UI_DEFAULT_TAB.ifBlank { tabs.firstOrNull()?.id ?: "routes" }
}
