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
data class PlaceCategory(val id: String, val label: String, val icon: String, val query: String)

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
            )
        }
    }

    val defaultTab: String get() = BuildConfig.UI_DEFAULT_TAB.ifBlank { tabs.firstOrNull()?.id ?: "routes" }
}
