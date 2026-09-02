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
/** A tab. [pages] is non-empty only for a container tab — one that holds a
 *  second strip instead of content of its own, which is what Buro > Fin is:
 *  Acct, Budget and Portfolio are three views of one question and do not each
 *  deserve a top-level tab. */
data class Page(
    val id: String,
    val label: String,
    val icon: String,
    val pages: List<Page> = emptyList(),
) {
    /** The tab that actually renders when this one is selected. */
    fun leaf(): Page = pages.firstOrNull()?.leaf() ?: this
}

data class Section(
    val id: String,
    val label: String,
    val icon: String,
    val bottomNav: Boolean,
    val toolbar: Boolean,
    val order: Int,
    /** Non-blank ⇒ this bar/drawer item is a launch target, not a page host:
     *  the string goes to [MainActivity.onTarget] and nothing in this app is
     *  shown. Wallet is the one today — the card deck lives in Cloud Wallet,
     *  and a bar slot pointing at it beats a second copy of it here. */
    val target: String,
    val pages: List<Page>,
) {
    /** Resolves a page id against the tab strip AND every sub-strip, so a
     *  `page:buro/acct` target lands on a sub-page as readily as on a tab. */
    fun page(id: String?): Page? =
        pages.flatMap { listOf(it) + it.pages }.firstOrNull { it.id == id } ?: pages.firstOrNull()

    /** The top-level tab holding [id] — itself, when [id] is already a tab. */
    fun parentOf(id: String?): Page? =
        pages.firstOrNull { it.id == id || it.pages.any { sub -> sub.id == id } }
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

    /**
     * One page's content list, read from assets when the page opens.
     *
     * NOT baked into BuildConfig: the stacks grow with the data — a real
     * profile is tens of kilobytes on its own — and javac caps a String
     * constant at 64KB. The navigation shape is bounded and stays in
     * BuildConfig; the content is a file, read once per page open.
     *
     * A sub-page lives one folder deeper, under its container tab. Missing or
     * malformed yields an empty list and a page that says so, never a crash —
     * the build already refuses a page with no file, so reaching the fallback
     * means the asset was lost after the build, not that JSON went untested.
     */
    fun stack(ctx: Context, sectionId: String, pageId: String): JSONArray {
        val section = byId(sectionId) ?: return JSONArray()
        val parent = section.parentOf(pageId)
        val path = if (parent != null && parent.id != pageId) {
            "${'$'}sectionId/${'$'}{parent.id}/${'$'}pageId.json"
        } else {
            "${'$'}sectionId/${'$'}pageId.json"
        }
        return runCatching {
            JSONArray(ctx.assets.open(path).bufferedReader().use { it.readText() })
        }.getOrDefault(JSONArray())
    }

    /** One level of `pages`, plus whatever `pages` each of those declares.
     *  Two levels is all the shell draws, so it is all this reads. */
    private fun parsePages(arr: JSONArray?, nested: Boolean = false): List<Page> {
        val out = mutableListOf<Page>()
        for (j in 0 until (arr?.length() ?: 0)) {
            val p = arr!!.optJSONObject(j) ?: continue
            val pid = p.optString("id")
            if (pid.isBlank()) continue
            out.add(Page(
                id = pid,
                label = p.optString("label", pid),
                icon = p.optString("icon"),
                pages = if (nested) emptyList() else parsePages(p.optJSONArray("pages"), true),
            ))
        }
        return out
    }

    private fun load(): List<Section> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT)))
        val out = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue

            val pages = parsePages(o.optJSONArray("pages"))

            out.add(
                Section(
                    id = id,
                    label = o.optString("label", id),
                    icon = o.optString("icon"),
                    bottomNav = o.optBoolean("bottom_nav", false),
                    toolbar = o.optBoolean("toolbar", false),
                    order = o.optInt("order", Int.MAX_VALUE),
                    target = o.optString("target"),
                    pages = pages,
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
