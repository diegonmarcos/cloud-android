package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig

import android.util.Base64
import org.json.JSONArray

/**
 * Folder taxonomy for the Phone tab of Home Apps. Captured 1:1 from
 * the user's One UI Home and baked into BuildConfig from
 * `build.json::ui.phone_folders`. Order is conveyed by both:
 *   • the explicit `order` field (a two-digit-block string, stable
 *     lexicographic sort), and
 *   • the leading prefix character on the label (_# > _ > - > .# > .a
 *     > .b > >) which is the user's launcher-naming sort convention —
 *     preserved verbatim so the UI reads identically to native One UI.
 *
 * Add a folder via build.json + rebuild — no Kotlin edits needed.
 * Adjust `match_keywords` to tune the [PhoneAppClassifier].
 */
object PhoneFolders {
    data class Folder(
        val id: String,
        val order: String,
        val label: String,
        val matchKeywords: List<String>,
        /** When `true`, PhoneAppsFragment shows this folder in the grid
         *  even if it currently holds zero apps. Used for hand-curated
         *  buckets like "No Categorie" that the user wants visible as an
         *  empty placeholder so they can drop apps into it later. */
        val pinned: Boolean = false,
        /** Explicit catch-all marker. The first folder with `sink: true`
         *  receives every app that matches no folder — deterministic,
         *  unlike "first empty-keyword folder" which depends on order
         *  when several empty-keyword buckets exist. */
        val sink: Boolean = false,
    )

    /** Folders in display order — by `order`, then `id` as a tie-breaker. */
    fun loadFromBuildConfig(): List<Folder> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_PHONE_FOLDERS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val kw = o.optJSONArray("match_keywords")
            val kws = if (kw == null) emptyList() else
                (0 until kw.length()).map { kw.getString(it).lowercase() }
            Folder(
                id            = o.optString("id"),
                order         = o.optString("order", "99"),
                label         = o.optString("label"),
                matchKeywords = kws,
                pinned        = o.optBoolean("pin", false),
                sink          = o.optBoolean("sink", false),
            )
        }.sortedWith(compareBy({ it.order }, { it.id }))
    }.getOrDefault(emptyList())

    /** id of the sink folder for apps that match no folder. Prefers the
     *  explicit `sink: true` folder ("No Categorie"); falls back to the
     *  first empty-keyword folder, then the literal "misc". */
    fun sinkFolderId(folders: List<Folder>): String =
        folders.firstOrNull { it.sink }?.id
            ?: folders.firstOrNull { it.matchKeywords.isEmpty() }?.id
            ?: "misc"
}
