package com.diegonmarcos.superapp

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * Runtime view of `build.json::ui.sections` — the single source of truth
 * for the navigation taxonomy. Baked into BuildConfig.UI_SECTIONS_JSON_B64
 * at gradle eval time, parsed lazily at first access.
 *
 * Adding / reordering sections, pages, or flipping `bottom_nav` is a
 * build.json edit — never a Kotlin edit.
 */
object Sections {

    data class Section(
        val id: String,
        val label: String,
        val iconName: String,
        val module: String?,
        val bottomNav: Boolean,
        val isMasterIndex: Boolean,
        val pages: List<Page>,
        val defaultChildren: List<String>,
    )

    data class Page(val id: String, val label: String)

    @Volatile private var cached: List<Section>? = null

    fun all(): List<Section> {
        cached?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_SECTIONS_JSON_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val parsed = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val pages = mutableListOf<Page>()
            o.optJSONArray("pages")?.let { pa ->
                for (j in 0 until pa.length()) {
                    val po = pa.getJSONObject(j)
                    pages.add(Page(po.getString("id"), po.getString("label")))
                }
            }

            val kids = mutableListOf<String>()
            o.optJSONArray("drawer_default_children")?.let { ka ->
                for (j in 0 until ka.length()) kids.add(ka.getString(j))
            }

            // `module` may be JSON null (e.g. the master Home index). org.json
            // surfaces it as the string "null" via optString → normalize.
            val rawModule = o.optString("module", "")
            val module = rawModule.takeIf { it.isNotEmpty() && it != "null" }

            parsed.add(
                Section(
                    id              = o.getString("id"),
                    label           = o.getString("label"),
                    iconName        = o.optString("icon", "ic_settings"),
                    module          = module,
                    bottomNav       = o.optBoolean("bottom_nav", false),
                    isMasterIndex   = o.optBoolean("is_master_index", false),
                    pages           = pages,
                    defaultChildren = kids,
                )
            )
        }
        cached = parsed
        return parsed
    }

    fun byId(id: String): Section? = all().firstOrNull { it.id == id }

    fun defaultSectionId(): String = BuildConfig.UI_DEFAULT_SECTION

    /** Resolve `icon` name from build.json to a drawable res id; 0 if missing. */
    fun iconResFor(ctx: Context, name: String): Int =
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
}
