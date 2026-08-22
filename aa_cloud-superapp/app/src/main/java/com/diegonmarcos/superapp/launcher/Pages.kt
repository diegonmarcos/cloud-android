package com.diegonmarcos.superapp.launcher

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray

/**
 * First-class Page registry — the data-driven index of every named navigation
 * destination in the launcher. Baked from build.json::ui.pages into
 * [BuildConfig.UI_PAGES_B64] (base64-JSON) by app/build.gradle.
 *
 * A [Page] maps a stable `id` (e.g. "suite-phone-quickmarks", "constellation")
 * to the raw navigation `target` that reaches it (e.g. "tab:phone:suite").
 * [byTarget] is the reverse lookup used by the single dispatch chokepoint in
 * MainActivity.onTileClicked to record `action:`/`tab:`/`mode:` opens into the
 * App Tabs recents shelf — destinations that goSection/openSectionPage never
 * recorded, so they were invisible to "Tabs" (e.g. Constellation).
 *
 * All parsing is fail-soft: a malformed blob yields an empty registry, never a
 * crash. Editing build.json can never break navigation or the build.
 */
data class Page(
    val id: String,
    val label: String,
    val icon: String,
    val target: String,
)

object Pages {

    private val entries: List<Page> by lazy { load() }
    private val targetIndex: Map<String, Page> by lazy { entries.associateBy { it.target } }
    private val idIndex: Map<String, Page> by lazy { entries.associateBy { it.id } }

    fun byTarget(target: String): Page? = targetIndex[target]

    fun byId(id: String): Page? = idIndex[id]

    fun all(): List<Page> = entries

    private fun load(): List<Page> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_PAGES_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        val out = mutableListOf<Page>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val target = o.optString("target")
            if (id.isBlank() || target.isBlank()) continue
            out.add(
                Page(
                    id = id,
                    label = o.optString("label"),
                    icon = o.optString("icon"),
                    target = target,
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}
