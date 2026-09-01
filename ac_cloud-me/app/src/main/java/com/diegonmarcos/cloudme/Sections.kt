package com.diegonmarcos.cloudme

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole navigation graph of Cloud Me, decoded once from
 * [BuildConfig.UI_SECTIONS_B64] — which app/build.gradle bakes from
 * build.json::ui.sections at build time.
 *
 * There is deliberately no other list of destinations in this app: the bottom
 * bar, the drawer, the toolbar gear and every tab strip are all built from
 * [all]. A section moves between the bar and the drawer by flipping one
 * boolean in JSON, and a new tab is a new `pages` entry plus the matching
 * `stack_<id>` — no Kotlin edit, no menu resource, nothing to keep in step.
 *
 * Parsing is fail-soft throughout. A malformed blob yields an empty list and
 * an app that opens on a blank page, never one that crashes on launch.
 */
data class Page(val id: String, val label: String, val icon: String)

data class Section(
    val id: String,
    val label: String,
    val icon: String,
    val bottomNav: Boolean,
    val toolbar: Boolean,
    val order: Int,
    val pages: List<Page>,
    /** page id → that page's content list, kept as raw JSON because
     *  [StackFragment] switches on `kind` and modelling nine block shapes
     *  twice would only create a second thing to keep correct. */
    val stacks: Map<String, JSONArray>,
) {
    fun page(id: String?): Page? = pages.firstOrNull { it.id == id } ?: pages.firstOrNull()
    fun stackFor(pageId: String): JSONArray = stacks[pageId] ?: JSONArray()
}

object Sections {

    private val entries: List<Section> by lazy { load() }
    private val index: Map<String, Section> by lazy { entries.associateBy { it.id } }

    fun all(): List<Section> = entries

    fun byId(id: String?): Section? = if (id == null) null else index[id]

    /** The bottom bar, in declared `order`. Capped at five because Material's
     *  BottomNavigationView silently drops the sixth item — a cap that fails
     *  loudly in JSON review beats one that fails invisibly on the phone. */
    fun bottom(): List<Section> =
        entries.filter { it.bottomNav }.sortedBy { it.order }.take(5)

    /** Everything reachable from the hamburger: the sections the bar has no
     *  room for, plus any bottom-bar overflow beyond five. */
    fun drawer(): List<Section> {
        val inBar = bottom().map { it.id }.toSet()
        return entries.filter { !it.toolbar && it.id !in inBar }
    }

    fun toolbarSection(): Section? = entries.firstOrNull { it.toolbar }

    fun default(): Section? = bottom().firstOrNull() ?: entries.firstOrNull()

    private fun load(): List<Section> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT)))
        val out = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue

            val pages = mutableListOf<Page>()
            o.optJSONArray("pages")?.let { pa ->
                for (j in 0 until pa.length()) {
                    val p = pa.optJSONObject(j) ?: continue
                    val pid = p.optString("id")
                    if (pid.isBlank()) continue
                    pages.add(Page(pid, p.optString("label", pid), p.optString("icon")))
                }
            }

            // stack_<page id>. Keyed off the page id rather than a positional
            // index so reordering tabs in JSON can never re-point a tab at
            // another tab's content.
            val stacks = mutableMapOf<String, JSONArray>()
            for (key in o.keys()) {
                if (!key.startsWith("stack_")) continue
                o.optJSONArray(key)?.let { stacks[key.removePrefix("stack_")] = it }
            }

            out.add(
                Section(
                    id = id,
                    label = o.optString("label", id),
                    icon = o.optString("icon"),
                    bottomNav = o.optBoolean("bottom_nav", false),
                    toolbar = o.optBoolean("toolbar", false),
                    order = o.optInt("order", Int.MAX_VALUE),
                    pages = pages,
                    stacks = stacks,
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}

/**
 * `extapp:<id>` → the package to launch, from build.json::ui.external_apps.
 *
 * Cloud Me links out far more than it stores, so this table decides whether a
 * tile does anything. An id missing here, or an app that is not installed,
 * makes the tile a no-op rather than a crash — which is also the honest
 * behaviour when the constellation member simply is not on this phone.
 */
object ExternalApps {

    private val packages: Map<String, String> by lazy {
        runCatching {
            val arr = JSONArray(String(Base64.decode(BuildConfig.UI_EXTERNAL_APPS_B64, Base64.DEFAULT)))
            val out = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val pkg = o.optString("package")
                if (id.isNotBlank() && pkg.isNotBlank()) out[id] = pkg
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** Target grammar is `extapp:<id>` or `extapp:<id>/<hint>`; the hint after
     *  the slash is a destination inside the other app that no launch intent
     *  can currently express, so it is parsed off and ignored rather than
     *  turned into a package lookup that would always miss. */
    fun packageFor(target: String): String? =
        packages[target.removePrefix("extapp:").substringBefore('/')]

    fun launch(ctx: Context, target: String): Boolean {
        val pkg = packageFor(target) ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(intent)
        return true
    }
}
