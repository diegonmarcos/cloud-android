package com.diegonmarcos.superapp.launcher

import android.util.Base64
import org.json.JSONArray

/**
 * Tabs displayed at the top of the Home Apps swipe-up sheet
 * ([AppDrawerSheetFragment]). Source of truth is
 * `build.json::ui.home_apps_tabs`, baked into BuildConfig as a base64
 * JSON blob — add / reorder a tab there and the UI follows on next
 * build with no Kotlin edits.
 */
object HomeAppsTabs {
    data class Tab(val id: String, val label: String)

    fun loadFromBuildConfig(): List<Tab> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_HOME_APPS_TABS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Tab(
                id    = o.optString("id"),
                label = o.optString("label"),
            )
        }
    }.getOrDefault(emptyList())
}
