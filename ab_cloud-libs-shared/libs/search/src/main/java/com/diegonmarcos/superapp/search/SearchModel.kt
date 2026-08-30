package com.diegonmarcos.superapp.search

import android.content.ComponentName
import android.os.UserHandle
import android.util.Base64
import org.json.JSONArray

/**
 * The vocabulary [SearchSheet] and its host speak. Kept apart from the sheet
 * so an app can build its index (and its command table) without touching a
 * Fragment — the index is testable data, the sheet is only a renderer.
 */

/** One chip. `kind` is the app's own label for "which builder fills this";
 *  the lib never interprets it, it only groups and filters by [Scope.id]. */
data class SearchScope(val id: String, val label: String, val kind: String)

/**
 * One result row. Exactly one payload is meant to be set — the sheet branches
 * on whichever is non-null, so a hit carries its own dispatch and the builders
 * stay free of UI.
 *
 *  - [phoneApp]          launch through LauncherApps
 *  - [settingsComponent] start that Android Settings activity
 *  - [copyValue]         copy to the clipboard
 *  - [target]            hand back to the host's own target grammar
 */
data class SearchHit(
    val label: String,
    val crumb: String,
    val source: String,
    val phoneApp: PhoneAppRef? = null,
    val settingsComponent: ComponentName? = null,
    val copyValue: String? = null,
    val target: String? = null,
)

/** Just enough to launch an installed app through LauncherApps. A component
 *  plus a user, rather than a LauncherActivityInfo, so an app can build hits
 *  from whatever it already enumerated — including a filtered list. */
data class PhoneAppRef(val component: ComponentName, val user: UserHandle)

/**
 * One `:`-command. [alias] is what the user types after the colon; it is
 * matched case-insensitively and is expected to be kebab-case, because that is
 * what reads well on a phone keyboard (`:update-all`, not `:update_all`).
 * [target] is opaque to the lib — it goes straight back to
 * [SearchSheet.Host.runCommand].
 */
data class SearchCommand(val alias: String, val label: String, val target: String, val crumb: String = "")

/** Scope chips declared in the consuming app's `build.json::ui.search_scopes`,
 *  baked into this library's own BuildConfig at build time. */
object SearchScopes {
    fun fromBuildConfig(): List<SearchScope> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SEARCH_SCOPES_B64, Base64.NO_WRAP)))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchScope(o.optString("id"), o.optString("label"), o.optString("kind"))
        }
    }.getOrDefault(emptyList())
}
