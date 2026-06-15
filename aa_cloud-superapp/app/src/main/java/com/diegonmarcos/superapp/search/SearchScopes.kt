package com.diegonmarcos.superapp.search
import com.diegonmarcos.superapp.HomeAppsTabs

import android.util.Base64
import org.json.JSONArray

/**
 * Scope chips of the top search sheet ([SearchSheetFragment]). Source of
 * truth is `build.json::ui.search_scopes`, baked into BuildConfig as a
 * base64 JSON blob. DECOUPLED from [HomeAppsTabs] (the Home-Apps body
 * tabs) so renaming a search scope never renames those tabs.
 *
 * [Scope.kind] selects which index builder fills that scope's hits:
 *   • cloud_apps     — cloud sections + tiles + home actions
 *   • phone_apps     — installed launchable apps (LauncherApps)
 *   • cloud_configs  — section sub-pages + cached consolidated.json entries
 *   • phone_configs  — installed Android Settings activities
 *
 * Add / reorder / rename a scope there and the chips follow on next build
 * with no Kotlin edits.
 */
object SearchScopes {
    data class Scope(val id: String, val label: String, val kind: String)

    fun loadFromBuildConfig(): List<Scope> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_SEARCH_SCOPES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Scope(
                id    = o.optString("id"),
                label = o.optString("label"),
                kind  = o.optString("kind"),
            )
        }
    }.getOrDefault(emptyList())
}
