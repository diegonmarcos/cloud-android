package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig

import android.util.Base64
import org.json.JSONArray

/**
 * Top-level section headers for the Home Apps/Phone tab. Data source =
 * build.json::ui.phone_sections, baked into BuildConfig.UI_PHONE_SECTIONS_B64
 * by app/build.gradle.
 *
 * Each section maps a single-character `prefix` to a `title`. Folders
 * whose label starts with that prefix bucket under the section's
 * subhead in PhoneAppsFragment. Convention shipped today:
 *   _  -> System
 *   -  -> Services
 *   .  -> Tools A
 *   >  -> Tools B
 *
 * Order in build.json = render order.
 */
object PhoneSections {

    data class Section(val title: String, val prefix: String)

    fun loadFromBuildConfig(): List<Section> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_PHONE_SECTIONS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        val out = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val prefix = o.optString("prefix")
            if (title.isBlank() || prefix.isBlank()) continue
            out.add(Section(title, prefix))
        }
        out
    }.getOrDefault(emptyList())
}
